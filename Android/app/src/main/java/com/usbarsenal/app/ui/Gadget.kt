package com.usbarsenal.app.ui

import android.content.Context
import androidx.compose.runtime.remember
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.usbarsenal.app.backend.Backend
import com.usbarsenal.app.backend.BackendState
import com.usbarsenal.app.backend.CHANGE_TIMEOUT_MS
import androidx.core.content.edit
import com.usbarsenal.app.backend.Capabilities
import com.usbarsenal.app.backend.GadgetStatus
import com.usbarsenal.app.backend.Lun
import com.usbarsenal.app.backend.Probe
import com.usbarsenal.app.backend.Root
import com.usbarsenal.app.backend.UA
import com.usbarsenal.app.backend.readCheck
import com.usbarsenal.app.backend.readProfile
import com.usbarsenal.app.backend.readStatus
import com.usbarsenal.app.backend.shellQuote
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

// A failed command, where it was acted on (null: the whole gadget), with stderr's last line
data class Fault(val where: Fn?, val message: String)

// The command running, as typed after `usb-arsenal`, for which function, and whether it stops it
data class Busy(val fn: Fn?, val typed: String, val stopping: Boolean)

// The functions Home lists, in its order
enum class Fn(val label: String) { STORAGE("Storage"), TETHER("Tether"), HID("HID") }

// What a log on screen keeps; the files keep the rest
const val LOG_KEEP = 2_000

fun MutableList<String>.keepLast(n: Int = LOG_KEEP) {
    if (size > n) subList(0, size - n).clear()
}

// The C side colours its [+] lines even through a pipe
private val ANSI = Regex("\\u001B\\[[0-9;]*[A-Za-z]")

fun plain(line: String) = line.replace(ANSI, "")

/*
 * The gadget as the screens see it, kept for the process like Setup's results: the last
 * `status --json`, what the kernel can do, the command running, this session's command output
 * (Log's CLI tab) and the last failure. Commands run on Root's change lane, one after another;
 * status reads never wait on them.
 */
object Gadget {
    var status by mutableStateOf<GadgetStatus?>(null)
    var caps by mutableStateOf<Capabilities?>(null)

    // The active identity profile's keys, as `identity show` prints them
    var profile by mutableStateOf<Map<String, String>>(emptyMap())
    var busy by mutableStateOf<Busy?>(null)

    // How many times the port went from Android to the gadget: each one plugs Pin in with a thud
    var binds by mutableIntStateOf(0)
        private set

    // How many commands failed: each one runs Pin into the port's edge
    var fails by mutableIntStateOf(0)
        private set

    // Bound or not as last seen with no command running; null until the first read, so a launch
    // onto a gadget already bound plays nothing
    private var wasBound: Boolean? = null

    // Commands finished so far: a status read that saw one finish took its state mid-command
    private var finished = 0

    // The installer's verdict on the backend, as Home last read it; null until it has
    var backend by mutableStateOf<Backend?>(null)

    // The setup each function was last started with from here: a screen's setup that now
    // differs from it is what leaving the screen offers to apply, and Discard goes back to it.
    // Setups, not command lines, so a profile change edits nothing. Kept for the process: a
    // function the app did not start (the CLI, an earlier process) has none, and its changes
    // wait for Home's switch. [applying] marks an apply at work
    var startedPicks by mutableStateOf<List<Pick>?>(null)
    var startedPlan by mutableStateOf<TetherPlan?>(null)
    var startedInterval by mutableStateOf<Int?>(null)
    var applying by mutableStateOf<Fn?>(null)

    // What [fn] runs now is [setups]' part for it
    fun recordStart(fn: Fn, setups: Setups) = when (fn) {
        Fn.STORAGE -> startedPicks = setups.picks
        Fn.TETHER -> startedPlan = setups.plan
        Fn.HID -> startedInterval = setups.interval
    }

    // We no longer know what runs: after a stop, or an apply that failed part of the way
    fun forgetStart(fn: Fn? = null) {
        if (fn == null || fn == Fn.STORAGE) startedPicks = null
        if (fn == null || fn == Fn.TETHER) startedPlan = null
        if (fn == null || fn == Fn.HID) startedInterval = null
    }
    var fault by mutableStateOf<Fault?>(null)
    val cli = mutableStateListOf<String>()

    // Commands outlive the screen that started them, as the install does
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    // After an identity command, so every screen shows the profile now in force
    fun reloadProfile() {
        // A read that fails keeps what was known, rather than showing Built-in for it
        scope.launch { readProfile().takeIf { it.isNotEmpty() }?.let { profile = it } }
    }

    // A status that cannot be read is not shown: claiming a gadget is still bound would be worse
    suspend fun refresh() {
        val idle = busy == null
        val seen = finished
        status = readStatus().getOrNull()
        // A running keyboard says whether this kernel lets the interval be set at all
        status?.hid?.firstOrNull { it.function == "keyboard" }?.let { HidSetup.fixed = it.interval < 0 }
        if (idle && busy == null && seen == finished) settle()
    }

    // Only settled states count: a replug unbinds and binds inside its command, which is never
    // seen as the port leaving Android. A stop never settles from its own command either, so
    // Apply's stop then start is a replug; a poll after a stop that stays stopped settles it.
    // ponytail: a replug run from the CLI while the app polls could show one unbound read and
    // play the moment; two unbound polls in a row would rule it out
    private fun settle() {
        val now = status?.bound ?: return
        if (wasBound == false && now) binds++
        wasBound = now
    }

    // Once a process: what the kernel can do (Check's answer, when setup ran), and the profile
    suspend fun loadOnce() {
        if (caps == null) readCheck(onLane = true).onSuccess { caps = it }
        if (profile.isEmpty()) profile = readProfile()
    }

    /*
     * Runs `usb-arsenal ARGS` for [fn] (null: the whole gadget). Its output goes to the CLI log
     * as printed; a failure leaves stderr's last line as the fault under [fn]'s row.
     */
    // [onDone] hears whether it worked. False when refused because another runs: it never
    // starts, and [onDone] is never called
    fun run(fn: Fn?, vararg args: String, onDone: (Boolean) -> Unit = {}): Boolean {
        if (busy != null) return false
        val typed = args.joinToString(" ")
        // `stop` and `<function> stop`; never read from the typed line, whose path could end in it
        busy = Busy(fn, typed, stopping = args.firstOrNull() == "stop" || args.getOrNull(1) == "stop")
        fault = null
        scope.launch {
            val r = Root.change("$UA " + args.joinToString(" ", transform = ::shellQuote))
            cli += "$ usb-arsenal $typed"
            (r.out + r.err).forEach { cli += plain(it) }
            cli.keepLast()
            if (!r.ok) {
                val why = when {
                    r.timedOut -> "[-] Timed out (${CHANGE_TIMEOUT_MS / 1000}s)"
                    else -> r.err.lastOrNull()?.let(::plain) ?: "[-] Failed (exit ${r.code})"
                }
                fault = Fault(fn, why)
                fails++
            }
            refresh()
            val stopping = busy?.stopping == true
            busy = null
            finished++
            if (!stopping) settle()
            onDone(r.ok)
        }
        return true
    }
}

// The start a function's setup gives now
fun setupArgs(fn: Fn): List<String> = when (fn) {
    Fn.STORAGE -> startArgs(Picks.all)
    Fn.TETHER -> tetherArgs(TetherSetup.plan, tetherFunction(TetherSetup.plan, Gadget.profile, Gadget.caps))
    Fn.HID -> listOf("hid", "start") + if (!HidSetup.fixed && HidSetup.interval != DEFAULT_INTERVAL) listOf("--interval", "${HidSetup.interval}") else emptyList()
}

// Every screen's setup as it stands, taken when a start is sent: an edit made while it runs
// then shows as a change
data class Setups(val picks: List<Pick>, val plan: TetherPlan, val interval: Int)

fun setupsNow() = Setups(Picks.all.sortedBy { it.lun }, TetherSetup.plan, HidSetup.interval)

// Whether what runs is not what the screen now sets up: an edit made back again is no change
fun pending(fn: Fn) = Gadget.status.isOn(fn) && when (fn) {
    Fn.STORAGE -> Gadget.startedPicks?.let { it != Picks.all.sortedBy { p -> p.lun } }
    Fn.TETHER -> Gadget.startedPlan?.let { it != TetherSetup.plan }
    Fn.HID -> Gadget.startedInterval?.let { !HidSetup.fixed && it != HidSetup.interval }
} == true

// Start a function with what its screen set up, or stop it
fun flip(fn: Fn, on: Boolean, onDone: (Boolean) -> Unit = {}) {
    val args = if (on) setupArgs(fn) else listOf(fn.name.lowercase(), "stop")
    val setups = setupsNow()
    Gadget.run(fn, *args.toTypedArray()) { ok ->
        if (ok && on) Gadget.recordStart(fn, setups)
        if (ok && !on) Gadget.forgetStart(fn)
        onDone(ok)
    }
}

// Back to the setup the function was started with: what runs, so nothing waits any more
fun discard(context: Context, fn: Fn) {
    when (fn) {
        Fn.STORAGE -> Gadget.startedPicks?.let { Picks.replace(context, it) }
        Fn.TETHER -> Gadget.startedPlan?.let { TetherSetup.set(context, it) }
        Fn.HID -> Gadget.startedInterval?.let { HidSetup.set(context, it) }
    }
}

/*
 * What applying Storage's setup takes, LUN by LUN against the picks it last started with, so
 * the host keeps every drive it can. Compared as the app gave them, not as status prints them:
 * the same file can show under another mount, and auto's choice is not a mode. [live] only says
 * which LUNs exist and which hold a medium.
 * - A LUN no longer picked and still loaded is ejected in place. It stays an empty drive until
 *   storage stops: a LUN cannot be removed without a replug.
 * - A pick that changed, or whose LUN the host ejected itself, is loaded again. On a LUN that
 *   exists, each gets a start of its own, since the backend swaps in place only for one image
 *   (a new type, or a drive the host holds locked, still replugs).
 * - Picks for LUNs that do not exist yet go in one start: one replug for all of them.
 * With nothing picked, storage is set up to be off.
 */
fun storageApply(before: List<Pick>, picks: List<Pick>, live: List<Lun>): List<List<String>> {
    if (picks.isEmpty()) return listOf(listOf("storage", "stop"))
    val loaded = live.filter { it.file.isNotEmpty() }.map { it.lun }
    val ejects = before.filter { b -> b.lun in loaded && picks.none { it.lun == b.lun } }.map { listOf("storage", "eject", "--lun", "${it.lun}") }
    val (swaps, added) = picks.filter { it !in before || it.lun !in loaded }.partition { p -> live.any { it.lun == p.lun } }
    return ejects + swaps.map { startArgs(listOf(it)) } + if (added.isEmpty()) emptyList() else listOf(startArgs(added))
}

// Bring what runs to the setup: Storage LUN by LUN, the others by a stop and a start. The
// commands run one after another and stop at the first that fails, whose fault shows as usual
fun apply(fn: Fn) {
    val steps = when (fn) {
        Fn.STORAGE -> storageApply(Gadget.startedPicks ?: return, Picks.all, Gadget.status?.storage.orEmpty())
        else -> listOf(listOf(fn.name.lowercase(), "stop"), setupArgs(fn))
    }
    // The setup as it is now is what runs once every step works; one taken later would hide an
    // edit made meanwhile
    val setups = setupsNow()
    val on = startable(fn)
    Gadget.applying = fn
    fun step(i: Int) {
        if (i == steps.size) {
            Gadget.forgetStart(fn)
            if (on) Gadget.recordStart(fn, setups)
            Gadget.applying = null
            return
        }
        val accepted = Gadget.run(fn, *steps[i].toTypedArray()) { ok ->
            if (ok) {
                step(i + 1)
            } else {
                // Part of it ran: what runs is no longer what any setup says, so nothing is
                // offered against it until Home's switch starts it again
                Gadget.forgetStart(fn)
                Gadget.applying = null
            }
        }
        if (!accepted) Gadget.applying = null
    }
    step(0)
}

// A setup that cannot start holds the apply, as Home's switches are held, except storage with
// nothing picked: that stops it
fun canApply(fn: Fn) = Gadget.busy == null && !Gadget.backend.holds() && (startable(fn) || fn == Fn.STORAGE)

// What applying does, for the sheet that asks: the consequence on the host
private fun applyNote(fn: Fn) = when (fn) {
    Fn.STORAGE -> "The host still sees the drives as they were. Apply loads your changes, and may reconnect every drive; Discard puts your picks back."
    Fn.TETHER -> "Tether still runs as it was. Apply restarts it, and the host loses its link for a moment; Discard puts your settings back."
    Fn.HID -> "HID still runs as it was. Apply restarts it, and the host loses the keyboard and mouse for a moment."
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
    val context = LocalContext.current
    NotAppliedSheet(pending(fn) && Gadget.applying != fn, applyNote(fn), canApply(fn), { discard(context, fn) }, { apply(fn) }, back)
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
    Fn.STORAGE -> Picks.all.isNotEmpty()
    Fn.TETHER -> TetherSetup.plan.ready()
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

fun GadgetStatus?.isOn(fn: Fn) = this != null && when (fn) {
    Fn.STORAGE -> storage.any { it.file.isNotEmpty() }
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
            luns[0] to luns.drop(1).joinToString("  ")
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
                    t.gateway -> "gateway ${t.gatewayContainer}"
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

// An image picked for a LUN, kept across launches so Home's switch can serve it again
data class Pick(val lun: Int, val mode: String, val path: String)

/*
 * The picks, shared by Home and Storage and kept in SharedPreferences "storage". One per LUN;
 * a pick stays when storage stops, so Home's switch brings the same drives back.
 */
object Picks {
    val all = mutableStateListOf<Pick>()
    private var loaded = false

    fun load(context: Context) {
        if (loaded) return
        loaded = true
        all += decodePicks(prefs(context).getString("picks", "").orEmpty())
    }

    fun set(context: Context, pick: Pick) {
        all.removeAll { it.lun == pick.lun }
        all += pick
        save(context)
    }

    fun replace(context: Context, picks: List<Pick>) {
        all.clear()
        all += picks
        save(context)
    }

    fun drop(context: Context, lun: Int) {
        all.removeAll { it.lun == lun }
        save(context)
    }

    private fun save(context: Context) = prefs(context).edit { putString("picks", encodePicks(all)) }

    private fun prefs(context: Context) = context.getSharedPreferences("storage", Context.MODE_PRIVATE)
}

/*
 * What Tether sets up for the next start: every option `tether start` takes. The function is
 * null until picked there; an empty address, DNS or upstream leaves it to the profile, or to
 * the backend's default; [net] false is --no-net.
 */
data class TetherPlan(
    val function: String? = null,
    val gateway: Boolean = false,
    val container: String = "",
    val iface: String = "usb0",
    val address: String = "",
    val dns: String = "",
    val upstream: String = "",
    val net: Boolean = true,
) {
    // Whether the backend takes it as it stands, so a start cannot fail on the setup itself
    // (--no-net sends none of the network fields, so they cannot stop it)
    fun ready() = when {
        gateway -> container.isNotBlank() && validIface(iface)
        !net -> true
        else -> (address.isBlank() || validAddress(address)) && (dns.isBlank() || validDns(dns)) && (upstream.isBlank() || validIface(upstream))
    }
}

const val DEFAULT_ADDRESS = "192.168.42.129/24"

object TetherSetup {
    var plan by mutableStateOf(TetherPlan())
        private set
    private var loaded = false

    fun load(context: Context) {
        if (loaded) return
        loaded = true
        val p = prefs(context)
        plan = TetherPlan(
            p.getString("function", null),
            p.getBoolean("gateway", false),
            p.getString("container", null) ?: plan.container,
            p.getString("iface", null) ?: plan.iface,
            p.getString("address", null).orEmpty(),
            p.getString("dns", null).orEmpty(),
            p.getString("upstream", null).orEmpty(),
            p.getBoolean("net", true),
        )
    }

    fun set(context: Context, next: TetherPlan) {
        if (next == plan) return
        plan = next
        prefs(context).edit {
            putString("function", next.function)
            putBoolean("gateway", next.gateway)
            putString("container", next.container)
            putString("iface", next.iface)
            putString("address", next.address)
            putString("dns", next.dns)
            putString("upstream", next.upstream)
            putBoolean("net", next.net)
        }
    }

    private fun prefs(context: Context) = context.getSharedPreferences("tether", Context.MODE_PRIVATE)
}

// Whether this kernel can run NCM: missing, or unsafe as Samsung's f_ncm is, means RNDIS
fun Capabilities?.ncmOk() = this == null || ncm == Probe.OK

// RNDIS left GKI after 5.10, so a kernel can have NCM alone
fun Capabilities?.rndisOk() = this == null || rndis != Probe.MISSING

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

// `tether start` for the setup, naming the function every time: without --ncm, a profile that
// says RNDIS would win over Tether's pick. A gateway takes no address, DNS or upstream: the
// container serves its end of the cable
fun tetherArgs(plan: TetherPlan, function: String): List<String> {
    val args = mutableListOf("tether", "start", if (function == "rndis") "--rndis" else "--ncm")
    fun opt(name: String, value: String) {
        if (value.isNotBlank()) args += listOf(name, value.trim())
    }
    when {
        plan.gateway -> {
            args += listOf("--gateway", plan.container.trim())
            opt("--gateway-iface", plan.iface)
        }
        !plan.net -> args += "--no-net"
        else -> {
            opt("--address", plan.address)
            opt("--dns", plan.dns)
            opt("--upstream", plan.upstream)
        }
    }
    return args
}

private val OCTET = Regex("0|[1-9][0-9]?|1[0-9]{2}|2[0-4][0-9]|25[0-5]")

// An IPv4 address as inet_pton reads it: four decimal octets, no leading zeros
fun validIp(s: String) = s.split('.').let { p -> p.size == 4 && p.all { OCTET.matches(it) } }

// As tether.c's parse_address: a host address with a /8 to /30 prefix (24 when left out)
fun validAddress(cidr: String) = hostOf(cidr.trim()) != null

// As config.c's ua_valid_dns: IPv4 addresses, comma-separated
fun validDns(s: String) = s.split(',').filter { it.isNotEmpty() }.let { p -> p.isNotEmpty() && p.all { validIp(it.trim()) } }

// An interface name the kernel takes: 1 to 15 characters, no slash or space
fun validIface(s: String) = s.trim().length in 1..15 && s.trim().none { it == '/' || it.isWhitespace() }

/*
 * Identity's pick: the profile the screen has chosen, a canonical path, or null for the built-in
 * defaults; and whether the running profile's file was edited since it was set. Not stored: the
 * backend's link is what persists, and [chosen] starts at it.
 */
object IdentitySetup {
    var chosen by mutableStateOf<String?>(null)
    var edited by mutableStateOf(false)

    // The running profile's file as it was before an edit made while bound, for Discard
    var undo: Pair<String, String>? = null
}

// The profile `identity show` says is in force; null for the built-in defaults
fun activeProfile(profile: Map<String, String>): String? = profile["profile"]?.takeIf { it.isNotEmpty() && it != "built-in defaults" }

// The name Settings shows for it: the file's, without .conf
fun profileName(profile: Map<String, String>) = activeProfile(profile)?.substringAfterLast('/')?.removeSuffix(".conf") ?: "Built-in"

// A pick waits only while the host sees the gadget: otherwise it is set at once, with no replug
fun identityPending(bound: Boolean, chosen: String?, active: String?, edited: Boolean) = bound && (chosen != active || edited)

const val DEFAULT_INTERVAL = 4

// HID's setup: the keyboard's poll interval exponent, 1 to 16 (4 is the kernel's 1 ms)
object HidSetup {
    var interval by mutableIntStateOf(DEFAULT_INTERVAL)
        private set

    // This kernel keeps the stock 1 ms whatever is asked (no f_hid interval control), as the
    // running keyboard showed; the interval is then not a setting at all
    var fixed by mutableStateOf(false)

    // The trackpad's speed in quarters: 4 is 1.0x, half a host pixel per pixel of drag
    var speed by mutableIntStateOf(4)
        private set

    // The wheel strip moves content with the finger, as a touchscreen does
    var natural by mutableStateOf(true)
        private set
    private var loaded = false

    fun load(context: Context) {
        if (loaded) return
        loaded = true
        val p = prefs(context)
        interval = p.getInt("interval", DEFAULT_INTERVAL).coerceIn(1, 16)
        speed = p.getInt("speed", 4).coerceIn(1, 16)
        natural = p.getBoolean("natural", true)
    }

    fun set(context: Context, next: Int) {
        interval = next.coerceIn(1, 16)
        prefs(context).edit { putInt("interval", interval) }
    }

    fun setSpeed(context: Context, next: Int) {
        speed = next.coerceIn(1, 16)
        prefs(context).edit { putInt("speed", speed) }
    }

    fun setNatural(context: Context, next: Boolean) {
        natural = next
        prefs(context).edit { putBoolean("natural", next) }
    }

    private fun prefs(context: Context) = context.getSharedPreferences("hid", Context.MODE_PRIVATE)
}

// `storage start` for every pick in one command: one replug however many LUNs
fun startArgs(picks: List<Pick>): List<String> =
    listOf("storage", "start") + picks.sortedBy { it.lun }.flatMap { listOf("--lun", "${it.lun}", "--mode", it.mode, it.path) }

// One pick a line: lun, mode, path, tab-separated (a path cannot hold a newline here)
fun encodePicks(picks: List<Pick>) = picks.joinToString("\n") { "${it.lun}\t${it.mode}\t${it.path}" }

fun decodePicks(text: String): List<Pick> = text.lines().mapNotNull { line ->
    val (lun, mode, path) = line.split('\t', limit = 3).takeIf { it.size == 3 } ?: return@mapNotNull null
    lun.toIntOrNull()?.let { Pick(it, mode, path) }
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
