package com.usbarsenal.app.ui

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
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
 * A row's two status lines: live facts while on, what a start would give while off, why
 * while the kernel lacks the function, and the command while it runs. [pick] is Storage's
 * first remembered image, if any.
 */
fun rowLines(
    fn: Fn,
    status: GadgetStatus?,
    caps: Capabilities?,
    busy: Busy?,
    pick: String? = null,
    profile: Map<String, String> = emptyMap(),
): Pair<String, String> {
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
            "off" to (pick?.substringAfterLast('/') ?: "pick an image to start")
        }
        Fn.TETHER -> {
            val t = status?.tether
            if (t == null) {
                // What a start would bring up: the profile's function and address, NCM falling
                // back to RNDIS where the kernel lacks it or it is unsafe (tether.c)
                val wanted = profile["tether_function"] ?: "ncm"
                val function = if (wanted == "ncm" && caps != null && caps.ncm != Probe.OK) "rndis" else wanted
                "off" to "$function · ${profile["tether_address"] ?: "192.168.42.129/24"}"
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
            "off" to "keyboard and 5-button mouse"
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
    val ip = cidr.substringBefore('/').split('.').mapNotNull { it.toIntOrNull() }.takeIf { it.size == 4 } ?: return null
    val prefix = cidr.substringAfter('/', "").toIntOrNull()?.takeIf { it in 8..30 } ?: return null
    val n = ip.fold(0L) { a, b -> a * 256 + b }
    val mask = (0xffffffffL shl (32 - prefix)) and 0xffffffffL
    val bcast = (n and mask) or (mask.inv() and 0xffffffffL)
    val offer = if (n + 1 < bcast) n + 1 else n - 1
    return (3 downTo 0).joinToString(".") { "${(offer shr (8 * it)) and 0xff}" }
}
