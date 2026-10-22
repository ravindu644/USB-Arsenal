package com.usbarsenal.app.ui

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.usbarsenal.app.backend.Backend
import com.usbarsenal.app.backend.BackendState
import com.usbarsenal.app.backend.CHANGE_TIMEOUT_MS
import androidx.core.content.edit
import com.usbarsenal.app.backend.Capabilities
import com.usbarsenal.app.backend.GadgetStatus
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

    // The installer's verdict on the backend, as Home last read it; null until it has
    var backend by mutableStateOf<Backend?>(null)

    // What each function was last started with from here. Its setup now giving another start
    // is what the rebind bar offers to apply. Kept for the process: a function the app did not
    // start (the CLI, an earlier process) has none, and its changes wait for Home's switch.
    // [rebinding] keeps the bar replugging across its stop and its start
    val started = mutableStateMapOf<Fn, List<String>>()
    var rebinding by mutableStateOf<Fn?>(null)
    var fault by mutableStateOf<Fault?>(null)
    val cli = mutableStateListOf<String>()

    // Commands outlive the screen that started them, as the install does
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    // A status that cannot be read is not shown: claiming a gadget is still bound would be worse
    suspend fun refresh() {
        status = readStatus().getOrNull()
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
    // [onDone] hears whether it worked; a command refused because another runs never starts
    fun run(fn: Fn?, vararg args: String, onDone: (Boolean) -> Unit = {}) {
        if (busy != null) return
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
            }
            refresh()
            busy = null
            onDone(r.ok)
        }
    }
}

// The start a function's setup gives now
fun setupArgs(fn: Fn): List<String> = when (fn) {
    Fn.STORAGE -> startArgs(Picks.all)
    Fn.TETHER -> tetherArgs(TetherSetup.plan, tetherFunction(TetherSetup.plan, Gadget.profile, Gadget.caps))
    Fn.HID -> listOf("hid", "start")
}

// Whether what runs is not what the screen now sets up: the start that ran is compared, so an
// edit made back again, or made while a start ran, is judged by what actually started
fun pending(fn: Fn) = Gadget.status.isOn(fn) && Gadget.started[fn]?.let { it != setupArgs(fn) } == true

// Start a function with what its screen set up, or stop it
fun flip(fn: Fn, on: Boolean, onDone: (Boolean) -> Unit = {}) {
    val args = if (on) setupArgs(fn) else listOf(fn.name.lowercase(), "stop")
    Gadget.run(fn, *args.toTypedArray()) { ok ->
        if (ok && on) Gadget.started[fn] = args
        if (ok && !on) Gadget.started.remove(fn)
        onDone(ok)
    }
}

// Stop [fn] and start it again with its setup: one replug, and what runs is what was set
fun rebind(fn: Fn) {
    Gadget.rebinding = fn
    Gadget.run(fn, fn.name.lowercase(), "stop") { ok ->
        if (ok) flip(fn, true) { Gadget.rebinding = null } else Gadget.rebinding = null
    }
}

// A function screen's bottom bar: the rebind key while its setup waits, Replugging while the
// rebind runs, and nothing otherwise. It is held as Home's switches are
@Composable
fun rebindBar(fn: Fn): (@Composable () -> Unit)? {
    val busy = Gadget.busy
    val port = when {
        Gadget.rebinding == fn -> Port.Replugging("usb-arsenal ${busy?.typed ?: "${fn.name.lowercase()} start"}")
        pending(fn) -> Port.Pending
        else -> return null
    }
    val can = busy == null && !Gadget.backend.holds() && startable(fn)
    return { BottomBar(port, onStopAll = {}, onRebind = if (can) ({ rebind(fn) }) else null) }
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
