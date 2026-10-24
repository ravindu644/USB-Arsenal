package com.usbarsenal.app.ui

import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.core.content.edit
import com.usbarsenal.app.backend.Backend
import com.usbarsenal.app.backend.BackendState
import com.usbarsenal.app.backend.CHANGE_TIMEOUT_MS
import com.usbarsenal.app.backend.Cache
import com.usbarsenal.app.backend.Capabilities
import com.usbarsenal.app.backend.Controller
import com.usbarsenal.app.backend.Droidspaces
import com.usbarsenal.app.backend.GadgetStatus
import com.usbarsenal.app.backend.Probe
import com.usbarsenal.app.backend.Root
import com.usbarsenal.app.backend.UA
import com.usbarsenal.app.backend.backendState
import com.usbarsenal.app.backend.plain
import com.usbarsenal.app.backend.readCheck
import com.usbarsenal.app.backend.readDroidspaces
import com.usbarsenal.app.backend.readProfile
import com.usbarsenal.app.backend.readStatus
import com.usbarsenal.app.backend.shellQuote
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

// A failed command, where it was acted on (null: the whole gadget), with stderr's last line;
// [identity] is an identity command's, which Identity shows and Home does not
data class Fault(val where: Fn?, val message: String, val identity: Boolean = false)

// The command running, as typed after `usb-arsenal`, for which function, whether it stops it,
// and what it does in a few words (the bottom bar's line; the typed one goes to the log)
data class Busy(val fn: Fn?, val typed: String, val stopping: Boolean, val says: String = typed)

// A command in a few words. Read from its first two words, never from a path after them
fun says(args: List<String>): String {
    val what = args.firstOrNull().orEmpty()
    val verb = args.getOrNull(1)
    val name = if (what == "hid") "HID" else what
    return when {
        what == "stop" -> "stopping everything"
        what == "identity" && verb == "unset" -> "back to built-in identity"
        what == "identity" -> "switching identity"
        verb == "eject" -> "ejecting a drive"
        verb == "start" -> "starting $name"
        verb == "apply" -> "applying $name"
        verb == "stop" -> "stopping $name"
        else -> args.joinToString(" ")
    }
}

// The functions Home lists, in its order; [prefix] is their keys' in the setup
enum class Fn(val label: String, val prefix: String) { STORAGE("Storage", "storage_"), TETHER("Tether", "tether_"), HID("HID", "hid_") }

// What a log on screen keeps; the files keep the rest
const val LOG_KEEP = 2_000

fun MutableList<String>.keepLast() {
    if (size > LOG_KEEP) subList(0, size - LOG_KEEP).clear()
}

/*
 * The gadget as the screens see it, cache-first: every value starts as the last read (Cache),
 * one status loop and each command's own post-read replace it, and a failed read leaves it. The
 * setup (what each function starts with) is the backend's; the screens edit it through [set],
 * which shows the edit at once and sends it on the change lane a beat later. Commands run on
 * that lane one after another; status reads never wait on them.
 */
object Gadget {
    var status by mutableStateOf(Cache.status)
    var caps by mutableStateOf(Cache.caps)

    // The active identity profile's keys, as `identity show` prints them
    var profile by mutableStateOf(Cache.profile)
    var busy by mutableStateOf<Busy?>(null)

    // The installer's verdict on the backend, as last read
    var backend by mutableStateOf(Cache.backend)

    // Whether Droidspaces is there, and its running containers, for Tether's gateway
    var droidspaces by mutableStateOf(Cache.droidspaces)

    // Status reads that failed in a row; the bar says so after a few
    var misses by mutableIntStateOf(0)
        private set

    // How many times the port went from Android to the gadget: each one plugs Pin in with a thud
    var binds by mutableIntStateOf(0)
        private set

    // How many commands failed: each one runs Pin into the port's edge
    var fails by mutableIntStateOf(0)
        private set

    // Settled reads in a row that saw the port Android's since it was last seen bound; null
    // until the first read, so a launch onto a gadget already bound plays nothing
    private var down: Int? = null

    // Commands finished so far, and the status reads begun and applied: a read is applied only
    // when nothing newer has been and no command finished since it began
    private var finished = 0
    private var reads = 0
    private var applied = 0

    // An apply at work, so Back does not ask again meanwhile
    var applying by mutableStateOf<Fn?>(null)
    var fault by mutableStateOf<Fault?>(null)
    val cli = mutableStateListOf<String>()

    // Commands outlive the screen that started them, as the install does
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    // Setup edits not sent yet, over the setup the last status read (the first frame shows them)
    private val edits = mutableStateMapOf<String, String>().also { it.putAll(Cache.edits) }
    private var flushJob: Job? = null

    val setup: Map<String, String> get() = status?.setup.orEmpty() + edits

    fun setupValue(key: String) = edits[key] ?: status?.setup?.get(key).orEmpty()

    // Whether an edit of [fn]'s keys has not reached the backend yet
    fun unsent(fn: Fn) = edits.keys.any { it.startsWith(fn.prefix) }

    // Edits the setup: shown at once, sent as one `setup set` a beat later (or before the next
    // command). A kill in that beat keeps them in the cache for the next launch
    fun set(vararg kv: Pair<String, String>) {
        kv.forEach { (k, v) -> if (setupValue(k) != v) edits[k] = v }
        Cache.edits = edits
        flushJob?.cancel()
        flushJob = scope.launch {
            delay(400)
            flush()
        }
    }

    suspend fun flush() {
        val batch = edits.toMap().ifEmpty { return }
        val r = Root.change("$UA setup set " + batch.entries.joinToString(" ") { shellQuote("${it.key}=${it.value}") })
        if (r.ok) {
            // The backend has them: the status map takes them, so dropping the edits moves nothing
            status = status?.copy(setup = status!!.setup + batch)
            batch.forEach { (k, v) -> if (edits[k] == v) edits.remove(k) }
            Cache.edits = edits
        } else {
            cli += "# usb-arsenal setup set ..."
            (r.out + r.err).forEach { cli += plain(it) }
            fault = Fault(null, r.err.lastOrNull()?.let(::plain) ?: "[-] setup set failed (exit ${r.code})")
            fails++
        }
    }

    // After an identity command, so every screen shows the profile now in force
    fun reloadProfile() {
        // A read that fails keeps what was known, rather than showing Built-in for it
        scope.launch { readProfile().takeIf { it.isNotEmpty() }?.let { profile = it; Cache.profile = it } }
    }

    /*
     * One status read. A poll begun while a command runs would read the kernel mid-change, so it
     * is skipped; a result is dropped when a newer one was applied already or a command finished
     * since it began. A failed read keeps the last status and counts.
     */
    suspend fun refresh(command: Boolean = false) {
        if (!command && busy != null) return
        val n = ++reads
        val seen = finished
        val idle = busy == null
        val r = readStatus()
        if (n < applied || (!command && seen != finished)) return
        applied = n
        r.onSuccess { s ->
            misses = 0
            status = s
            Cache.status = s
            Controller.mirror(s.setup)
            // A running keyboard shows whether its interval took, which outranks check's probe
            // Kept once seen, so it never flips back with HID's run state on a backend without the probe
            s.hid.firstOrNull { it.function == "keyboard" }?.let { HidSetup.keyboardFixed = it.interval < 0 }
            if (idle && busy == null) settle(sure = false)
        }.onFailure { misses++ }
    }

    // Only settled states count: a replug unbinds and binds inside its command, which is never
    // seen as the port leaving Android. A command's own read is [sure]; a replug from the command
    // line can show a poll one unbound read, so polls need two in a row before a bind thuds
    private fun settle(sure: Boolean) {
        val now = status?.bound ?: return
        val d = down
        if (now) {
            if (d != null && d >= 2) binds++
            down = 0
        } else {
            down = if (d == null || sure) 2 else d + 1
        }
    }

    // Once a launch: the backend's state, what the kernel can do, the profile, Droidspaces. Each
    // replaces its cached value only when it was read
    suspend fun launchReads(context: Context) {
        backendState(context).let { backend = it; Cache.backend = it }
        if (backend?.state == BackendState.NO_ROOT || backend?.state == BackendState.NOT_INSTALLED) return
        readCheckOnce()
        readProfile().takeIf { it.isNotEmpty() }?.let { profile = it; Cache.profile = it }
        readDroidspaces().let { droidspaces = it; Cache.droidspaces = it }
    }

    // check probes configfs, so it goes through the change lane and runs once per ask
    suspend fun readCheckOnce() {
        readCheck().onSuccess { caps = it; Cache.caps = it }
    }

    /*
     * Runs `usb-arsenal ARGS` for [fn] (null: the whole gadget). Its output goes to the CLI log
     * as printed; a failure leaves stderr's last line as the fault under [fn]'s row.
     * [onDone] hears whether it worked. False when refused because another runs: it never
     * starts, and [onDone] is never called. [quiet] commands change no gadget, so the bar says
     * nothing while they run.
     */
    fun run(fn: Fn?, vararg args: String, quiet: Boolean = false, onDone: (Boolean) -> Unit = {}): Boolean {
        if (busy != null) {
            // Refused, as a tap that lands in the beat before the keys are held: say so
            fault = Fault(fn, "[-] Another command is running", identity = args.firstOrNull() == "identity")
            fails++
            return false
        }
        val typed = args.joinToString(" ")
        // `stop` and `<function> stop`; never read from the typed line, whose path could end in it
        if (!quiet) busy = Busy(fn, typed, stopping = args.firstOrNull() == "stop" || args.getOrNull(1) == "stop", says = says(args.toList()))
        fault = null
        scope.launch {
            // The setup lands before the command that may start from it
            flushJob?.cancel()
            flush()
            val r = Root.change("$UA " + args.joinToString(" ", transform = ::shellQuote))
            // Root's prompt: every command runs as root
            cli += "# usb-arsenal $typed"
            (r.out + r.err).forEach { cli += plain(it) }
            cli.keepLast()
            if (!r.ok) {
                val why = when {
                    r.timedOut -> "[-] Timed out (${CHANGE_TIMEOUT_MS / 1000}s)"
                    else -> r.err.lastOrNull()?.let(::plain) ?: "[-] Failed (exit ${r.code})"
                }
                fault = Fault(fn, why, identity = args.firstOrNull() == "identity")
                fails++
            }
            refresh(command = true)
            busy = null
            finished++
            settle(sure = true)
            onDone(r.ok)
        }
        return true
    }
}

// Whether what runs is not what the setup says: the backend compares the two files, and an edit
// not sent yet counts while the function runs
fun pending(fn: Fn) = Gadget.status?.pending?.get(fn.name.lowercase()) == true || (Gadget.status.isOn(fn) && Gadget.unsent(fn))

// Start a function with its setup, or stop it
fun flip(fn: Fn, on: Boolean, onDone: (Boolean) -> Unit = {}) {
    Gadget.run(fn, fn.name.lowercase(), if (on) "start" else "stop", onDone = onDone)
}

// The setup back to what runs, so nothing waits any more
fun discard(fn: Fn) {
    Gadget.run(fn, "setup", "revert", fn.name.lowercase(), quiet = true)
}

// Bring what runs to the setup: the backend decides against the kernel
fun apply(fn: Fn) {
    Gadget.applying = fn
    if (!Gadget.run(fn, fn.name.lowercase(), "apply") { Gadget.applying = null }) Gadget.applying = null
}

// A setup that cannot start holds the apply, as Home's switches are held, except storage with
// nothing picked: that stops it
fun canApply(fn: Fn) = Gadget.busy == null && !Gadget.backend.holds() && (startable(fn) || fn == Fn.STORAGE)

// What applying does, for the sheet that asks: the consequence on the host
private fun applyNote(fn: Fn) = when (fn) {
    Fn.STORAGE -> "The host still sees the drives as they were. Apply loads your changes, and may reconnect every drive; Discard puts your picks back."
    Fn.TETHER -> "Tether still runs as it was. Apply reconnects the gadget, every drive and function with it, for a moment; Discard puts your settings back."
    Fn.HID -> "HID still runs as it was. Apply reconnects the gadget, every drive and function with it, for a moment."
}

/*
 * Back with a change not applied, while the function runs: the screen would be left showing a
 * setup that is not what runs, so it asks, and only here is a change applied. Apply leaves
 * with it applied (Home's bar shows it replugging); Discard puts the setup back as it started.
 * Goes in the screen's root after its own BackHandler, and before its other sheets, so theirs
 * win while they are open.
 */
@Composable
fun UnappliedSheet(fn: Fn, back: () -> Unit) {
    NotAppliedSheet(pending(fn) && Gadget.applying != fn, applyNote(fn), canApply(fn), { discard(fn) }, { apply(fn) }, back)
}

// The sheet itself, for anything whose change waits on the gadget: Identity's profile too
@Composable
fun NotAppliedSheet(pending: Boolean, note: String, canApply: Boolean, onDiscard: () -> Unit, onApply: () -> Unit, back: () -> Unit) {
    var asking by rememberSaveable { mutableStateOf(false) }
    val discardKey = remember { FocusRequester() }
    BackHandler(enabled = pending) { asking = true }
    Sheet(
        asking,
        onDismiss = { asking = false },
        entry = discardKey,
        footer = {
            Key("Discard", Modifier.weight(1f).focusRequester(discardKey)) {
                asking = false
                onDiscard()
                back()
            }
            Key("Apply", Modifier.weight(1f), primary = true, enabled = canApply) {
                asking = false
                onApply()
                back()
            }
        },
    ) {
        Title("Not applied")
        Note(note)
    }
}

// Whether a start has anything to start: storage needs a pick, tether a setup the backend takes
fun startable(fn: Fn) = when (fn) {
    Fn.STORAGE -> picks().isNotEmpty()
    Fn.TETHER -> tetherPlan().ready(Gadget.caps)
    Fn.HID -> true
}

// Whether the backend stops the switches: until it is known, and while it needs a fix other
// than an update
fun Backend?.holds() = this == null || (state != BackendState.READY && state != BackendState.UPDATE_AVAILABLE)

// Whether the kernel has the function at all; null caps (not read yet) counts as yes
fun Capabilities?.has(fn: Fn) = this == null || when (fn) {
    Fn.STORAGE -> massStorage != Probe.MISSING
    Fn.TETHER -> ncm != Probe.MISSING || rndis != Probe.MISSING
    Fn.HID -> hid != Probe.MISSING
}

// On means linked in the gadget: storage with every drive empty is still on, and `storage
// stop` is what turns it off
fun GadgetStatus?.isOn(fn: Fn) = this != null && when (fn) {
    Fn.STORAGE -> storage.isNotEmpty()
    Fn.TETHER -> tether != null
    Fn.HID -> hid.isNotEmpty()
}

/*
 * A row's two status lines: live facts while on, what the function does while off (the
 * switch already says off), why while the kernel lacks it, and the command while it runs.
 */
fun rowLines(fn: Fn, status: GadgetStatus?, caps: Capabilities?, busy: Busy?): Pair<String, String> {
    if (!caps.has(fn)) {
        return when (fn) {
            Fn.STORAGE -> "no mass storage in this kernel" to "CONFIG_USB_CONFIGFS_MASS_STORAGE"
            Fn.TETHER -> "no ncm or rndis in this kernel" to "CONFIG_USB_CONFIGFS_NCM"
            Fn.HID -> "no hid in this kernel" to "CONFIG_USB_CONFIGFS_F_HID"
        }
    }
    if (busy != null && busy.fn == fn) return (if (busy.stopping) "stopping" else "starting") to busy.typed
    val on = status.isOn(fn)
    return when (fn) {
        Fn.STORAGE -> if (on) {
            val luns = status!!.storage.filter { it.file.isNotEmpty() }.map { "LUN${it.lun} ${it.file.substringAfterLast('/')} · ${it.mode}" }
            if (luns.isEmpty()) "${status.storage.size} empty drive${if (status.storage.size == 1) "" else "s"}" to "no medium" else luns[0] to luns.drop(1).joinToString("  ")
        } else {
            "ISOs and disk images" to "as drives for the host"
        }
        Fn.TETHER -> {
            val t = status?.tether
            if (t == null) {
                "this phone's internet" to "for the host, over USB"
            } else {
                val ip = t.address.substringBefore('/')
                val line1 = hostOf(t.address)?.let { "${t.function} $ip → .${it.substringAfterLast('.')}" } ?: "${t.function} ${t.iface}"
                val line2 = when {
                    t.gateway -> "gateway ${t.gatewayContainer}".trimEnd()
                    !t.running -> "no network side"
                    t.upstream.isNotEmpty() -> "via ${t.upstream}"
                    else -> "no upstream yet"
                }
                line1 to line2
            }
        }
        Fn.HID -> if (on) {
            val node = { f: String -> status!!.hid.firstOrNull { it.function == f }?.node.orEmpty() }
            "keyboard ${node("keyboard")}" to "mouse ${node("mouse")}"
        } else {
            "a keyboard and a mouse" to "for the host"
        }
    }
}

// An image set up for a LUN: the setup's `storage_lunN=MODE:PATH`
data class Pick(val lun: Int, val mode: String, val path: String) {
    val spec get() = "$mode:$path"
}

const val LUNS = 8

// The picks a setup holds, by LUN
fun picksOf(setup: Map<String, String>): List<Pick> = (0 until LUNS).mapNotNull { lun ->
    val spec = setup["storage_lun$lun"].orEmpty()
    val colon = spec.indexOf(':').takeIf { it > 0 } ?: return@mapNotNull null
    Pick(lun, spec.substring(0, colon), spec.substring(colon + 1))
}

fun picks() = picksOf(Gadget.setup)

// The drives that run, as the backend recorded them at the last start
fun runningPicks() = picksOf(Gadget.status?.running.orEmpty())

fun setPick(pick: Pick) = Gadget.set("storage_lun${pick.lun}" to pick.spec)

fun dropPick(lun: Int) = Gadget.set("storage_lun$lun" to "")

/*
 * What Tether sets up for the next start, as the setup's `tether_*` keys read: the function is
 * null for the profile's; a gateway is a container's name; an empty address, DNS or upstream
 * leaves it to the profile; [net] false is --no-net.
 */
data class TetherPlan(
    val function: String? = null,
    val container: String = "",
    // Empty is the container's usb0, as tether.c takes it
    val iface: String = "",
    val address: String = "",
    val dns: String = "",
    val upstream: String = "",
    val net: Boolean = true,
) {
    val gateway get() = container.isNotEmpty()

    // Whether the backend takes it as it stands, so a start cannot fail on the setup itself
    // (--no-net sends none of the network fields, so they cannot stop it; NAT needs the kernel's)
    fun ready(caps: Capabilities?) = when {
        gateway -> iface.isBlank() || validIface(iface)
        !net -> true
        else -> caps.natOk() && (address.isBlank() || validAddress(address)) && (dns.isBlank() || validDns(dns)) && (upstream.isBlank() || validIface(upstream))
    }

    // The setup's keys for it
    fun keys() = listOf(
        "tether_function" to function.orEmpty(),
        "tether_gateway" to container,
        "tether_gateway_iface" to iface.trim(),
        "tether_address" to address.trim(),
        "tether_dns" to dns.trim(),
        "tether_upstream" to upstream.trim(),
        "tether_net" to if (net) "1" else "0",
    )
}

fun tetherPlanOf(setup: Map<String, String>) = TetherPlan(
    function = setup["tether_function"]?.ifEmpty { null },
    container = setup["tether_gateway"].orEmpty(),
    iface = setup["tether_gateway_iface"].orEmpty(),
    address = setup["tether_address"].orEmpty(),
    dns = setup["tether_dns"].orEmpty(),
    upstream = setup["tether_upstream"].orEmpty(),
    net = setup["tether_net"] != "0",
)

fun tetherPlan() = tetherPlanOf(Gadget.setup)

fun setPlan(next: TetherPlan) = Gadget.set(*next.keys().toTypedArray())

const val DEFAULT_ADDRESS = "192.168.42.129/24"

// Whether this kernel can run NCM: missing, or unsafe as Samsung's f_ncm is, means RNDIS
fun Capabilities?.ncmOk() = this == null || ncm == Probe.OK

// RNDIS left GKI after 5.10, so a kernel can have NCM alone
fun Capabilities?.rndisOk() = this == null || rndis != Probe.MISSING

// Whether the kernel can NAT at all; without it only --no-net and a gateway start
fun Capabilities?.natOk() = this == null || nat != Probe.MISSING

// The function a start brings up: Tether's pick, else the profile's, else NCM, and the other
// one where the kernel cannot run it (the backend falls back from NCM the same way, tether.c)
fun tetherFunction(plan: TetherPlan, profile: Map<String, String>, caps: Capabilities?): String {
    val wanted = plan.function ?: profile["tether_function"] ?: "ncm"
    return when {
        wanted == "ncm" && !caps.ncmOk() -> "rndis"
        wanted == "rndis" && !caps.rndisOk() && caps.ncmOk() -> "ncm"
        else -> wanted
    }
}

private val OCTET = Regex("0|[1-9][0-9]?|1[0-9]{2}|2[0-4][0-9]|25[0-5]")

// An IPv4 address as inet_pton reads it: four decimal octets, no leading zeros
fun validIp(s: String) = s.split('.').let { p -> p.size == 4 && p.all { OCTET.matches(it) } }

// As tether.c's parse_address: a host address with a /8 to /30 prefix (24 when left out)
fun validAddress(cidr: String) = hostOf(cidr.trim()) != null

// As config.c's ua_valid_dns: IPv4 addresses, comma-separated, in the 127 characters its field holds
fun validDns(s: String) = s.trim().length <= 127 && s.split(',').filter { it.isNotEmpty() }.let { p -> p.isNotEmpty() && p.all { validIp(it.trim()) } }

// An interface name the kernel takes, as dev_valid_name: 1 to 15 characters, not . or .., no
// slash, colon or space
fun validIface(s: String) = s.trim().let { n -> n.length in 1..15 && n != "." && n != ".." && n.none { it == '/' || it == ':' || it.isWhitespace() } }

/*
 * Identity's pick: the profile the screen has chosen, a canonical path, or null for the built-in
 * defaults; and whether the running profile's file was edited since it was set. Not stored: the
 * backend's link is what persists, and [chosen] starts at it.
 */
object IdentitySetup {
    // From the cached profile, so the first frame picks what was last seen in force
    var chosen by mutableStateOf(activeProfile(Cache.profile))
    var edited by mutableStateOf(false)

    // The running profile's file as it was before an edit made while bound, for Discard
    var undo: Pair<String, String>? = null

    // Discard's write outlives the screen, which Back leaves at once
    fun restore(path: String, text: String) {
        Gadget.scope.launch { com.usbarsenal.app.backend.writeText(path, text) }
    }
}

// The profile `identity show` says is in force; null for the built-in defaults
fun activeProfile(profile: Map<String, String>): String? = profile["profile"]?.ifEmpty { null }

// The name Settings shows for it: the file's, without .conf
fun profileName(profile: Map<String, String>) = activeProfile(profile)?.substringAfterLast('/')?.removeSuffix(".conf") ?: "Built-in"

// A pick waits only while the host sees the gadget: otherwise it is set at once, with no replug
fun identityPending(bound: Boolean, chosen: String?, active: String?, edited: Boolean) = bound && (chosen != active || edited)

const val DEFAULT_INTERVAL = 4

/*
 * HID's setup: the keyboard's poll interval exponent, 1 to 16 (4 is the kernel's 1 ms), as the
 * setup's `hid_interval` (0 there is the kernel's default). Speed and Natural scroll are the
 * trackpad's, app-side, in prefs.
 */
object HidSetup {
    private var prefs: android.content.SharedPreferences? = null

    fun init(context: Context) {
        val p = context.getSharedPreferences("hid", Context.MODE_PRIVATE)
        prefs = p
        speed = p.getInt("speed", 4).coerceIn(1, 16)
        natural = p.getBoolean("natural", true)
    }

    val interval: Int get() = Gadget.setupValue("hid_interval").toIntOrNull()?.takeIf { it > 0 }?.coerceIn(1, 16) ?: DEFAULT_INTERVAL

    // This kernel keeps the stock 1 ms whatever is asked (no f_hid interval control); the
    // interval is then not a setting at all. A running keyboard shows it; before HID runs,
    // check's probe does (a backend older than the probe knows nothing until then)
    val fixed get() = keyboardFixed ?: Gadget.caps?.hidInterval?.let { it != Probe.OK } ?: false
    var keyboardFixed by mutableStateOf<Boolean?>(null)

    // The trackpad's speed in quarters: 4 is 1.0x, half a host pixel per pixel of drag
    var speed by mutableIntStateOf(4)
        private set

    // The wheel strip moves content with the finger, as a touchscreen does
    var natural by mutableStateOf(true)
        private set

    // The kernel's default is written as 0, so the file and what runs compare equal at 1 ms
    fun set(next: Int) = Gadget.set("hid_interval" to next.coerceIn(1, 16).let { if (it == DEFAULT_INTERVAL) "0" else "$it" })

    fun speed(next: Int) {
        speed = next.coerceIn(1, 16)
        prefs?.edit { putInt("speed", speed) }
    }

    fun natural(next: Boolean) {
        natural = next
        prefs?.edit { putBoolean("natural", next) }
    }
}

/*
 * The address the phone offers the host for its own CIDR, as tether.c picks it: the next one,
 * or the one before when the next is the broadcast. Null for no address (gateway, --no-net).
 */
fun hostOf(cidr: String): String? {
    val addr = cidr.substringBefore('/')
    if (!validIp(addr)) return null
    // No prefix is a /24, as parse_address takes it
    val prefix = (if ('/' in cidr) cidr.substringAfter('/').toIntOrNull() else 24)?.takeIf { it in 8..30 } ?: return null
    val n = addr.split('.').fold(0L) { a, b -> a * 256 + b.toLong() }
    val mask = (0xffffffffL shl (32 - prefix)) and 0xffffffffL
    val net = n and mask
    val bcast = net or (mask.inv() and 0xffffffffL)
    // The phone cannot be the network or the broadcast, and the host's offer cannot be the network
    val offer = if (n + 1 < bcast) n + 1 else n - 1
    if (n == net || n == bcast || offer == net) return null
    return (3 downTo 0).joinToString(".") { "${(offer shr (8 * it)) and 0xff}" }
}
