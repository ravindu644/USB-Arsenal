package com.usbarsenal.app.backend

import org.json.JSONArray
import org.json.JSONObject

// The one root runner every command goes through. The global mount namespace, as at boot: a daemon
// started from here must see the mounts init sees, not the app's own view
val Root = RootExec { ProcessBuilder("su", "--mount-master", "-c", it).start() }

// ua_ws_path()'s workspace on Android; bin/usb-arsenal links to the module's binary
const val WORKSPACE = "/data/local/USB-Arsenal"
const val UA = "$WORKSPACE/bin/usb-arsenal"

suspend fun readCheck(): Result<Capabilities> = Root.read("$UA check --json").json(::parseCheck)

suspend fun readStatus(): Result<GadgetStatus> = Root.read("$UA status --json").json(::parseStatus)

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
