package com.usbarsenal.app.backend

import org.json.JSONArray
import org.json.JSONObject

// The one root runner every command goes through. The global mount namespace, as at boot: a daemon
// started from here must see the mounts init sees, not the app's own view
val Root = RootExec { ProcessBuilder("su", "--mount-master", "-c", it).start() }

// ua_ws_path()'s workspace on Android; the binary lives in its bin/, the boot module only starts it
const val WORKSPACE = "/data/local/USB-Arsenal"
const val UA = "$WORKSPACE/bin/usb-arsenal"

/*
 * Setup checks with the binary it is about to install; UA_NO_PROXY keeps an older daemon from
 * answering for it. check probes by creating functions in configfs, so once the gadget may be
 * live it goes through the change lane ([onLane]) and never races a command.
 */
suspend fun readCheck(binary: String = UA, onLane: Boolean = false): Result<Capabilities> {
    val cmd = "UA_NO_PROXY=1 ${shellQuote(binary)} check --json"
    return (if (onLane) Root.change(cmd) else Root.read(cmd)).json(::parseCheck)
}

// identity show's key column: "  tether_function  ncm"; a derived value reads "(derived)"
suspend fun readProfile(): Map<String, String> = Root.read("$UA identity show").out
    .filter { it.startsWith("  ") }
    .mapNotNull { line -> line.trim().split(Regex("\\s+"), limit = 2).takeIf { it.size == 2 }?.let { it[0] to it[1] } }
    .filter { it.second != "(derived)" }
    .toMap()

suspend fun readStatus(): Result<GadgetStatus> = Root.read("$UA status --json").json(::parseStatus)

// What su -v prints, as it prints it: "3.3.0:KernelSU" on the a166p
data class RootInfo(val su: String)

/*
 * A failure carries what a shell would have said. KernelSU hides su from an app it has not
 * granted, so a missing su reads as sh reports it; a su that ran and refused says so on stderr.
 */
suspend fun rootInfo(): Result<RootInfo> {
    val timeoutMs = 15_000L
    val r = Root.read("id -u; su -v", timeoutMs)
    // The uid decides: the exit code is su -v's, which a root shell without su on its PATH fails
    return when {
        r.out.firstOrNull() == "0" -> Result.success(RootInfo(r.out.getOrNull(1).orEmpty()))
        r.code == NO_ROOT && r.out.isEmpty() -> Result.failure(SecurityException("su: inaccessible or not found"))
        r.timedOut -> Result.failure(SecurityException("su: no answer in ${timeoutMs / 1000}s"))
        else -> Result.failure(SecurityException(r.err.lastOrNull() ?: "su: permission denied"))
    }
}

// check exits 1 when a requirement is missing and still prints the report, so stdout decides
private inline fun <T> RootResult.json(parse: (String) -> T): Result<T> = runCatching {
    if (out.isEmpty()) error(err.joinToString("\n").ifEmpty { if (timedOut) "timed out" else "exit $code" })
    parse(out.joinToString("\n"))
}

// A check --json probe: 1 ok, 0 missing, -1 works with caveats
enum class Probe { OK, MISSING, CAVEAT }

data class Capabilities(
    val root: Probe,
    val configfs: Probe,
    val udc: Probe,
    val gadget: Probe,
    val massStorage: Probe,
    val cdrom: Probe,
    val hid: Probe,
    val ncm: Probe,
    val rndis: Probe,
    val nat: Probe,
    val nat6: Probe,
    val version: String,
    val udcName: String,
    val requirementsMet: Boolean,
)

// An ejected LUN has an empty file, mode and access
data class Lun(val lun: Int, val file: String, val mode: String, val access: String)

data class HidNode(val function: String, val node: String)

data class Tether(
    val function: String,
    val iface: String,
    val running: Boolean,
    val address: String,
    val ipv6: String,
    val upstream: String,
    val gateway: Boolean,
    val gatewayContainer: String,
    val gatewayIface: String,
    val gatewayWaiting: Boolean,
)

data class GadgetStatus(
    val version: String,
    val udc: String,
    val udcState: String,
    val bound: Boolean,
    val storage: List<Lun>,
    val hid: List<HidNode>,
    val tether: Tether?,
)

// The C side prints every key, so a missing one is a mismatched backend and throws
fun parseCheck(json: String): Capabilities = with(JSONObject(json)) {
    fun p(key: String) = when (getInt(key)) {
        1 -> Probe.OK
        -1 -> Probe.CAVEAT
        else -> Probe.MISSING
    }
    Capabilities(
        p("root"), p("configfs"), p("udc"), p("gadget"), p("mass_storage"), p("cdrom"), p("hid"),
        p("ncm"), p("rndis"), p("nat"), p("nat6"),
        getString("version"), getString("udc_name"), getInt("requirements_met") == 1,
    )
}

fun parseStatus(json: String): GadgetStatus = with(JSONObject(json)) {
    GadgetStatus(
        version = getString("version"),
        udc = getString("udc"),
        udcState = getString("udc_state"),
        bound = getInt("bound") == 1,
        storage = getJSONArray("storage").objects().map {
            Lun(it.getInt("lun"), it.getString("file"), it.getString("mode"), it.getString("access"))
        },
        hid = getJSONArray("hid").objects().map { HidNode(it.getString("function"), it.getString("node")) },
        tether = if (isNull("tether")) null else getJSONObject("tether").run {
            Tether(
                function = getString("function"),
                iface = getString("iface"),
                running = getInt("running") == 1,
                address = getString("address"),
                ipv6 = getString("ipv6"),
                upstream = getString("upstream"),
                gateway = getInt("gateway") == 1,
                gatewayContainer = getString("gateway_container"),
                gatewayIface = getString("gateway_iface"),
                gatewayWaiting = getInt("gateway_waiting") == 1,
            )
        },
    )
}

private fun JSONArray.objects() = (0 until length()).map(::getJSONObject)
