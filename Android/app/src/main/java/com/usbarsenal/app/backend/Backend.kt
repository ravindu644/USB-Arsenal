package com.usbarsenal.app.backend

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.json.JSONArray
import org.json.JSONObject

// The one root runner every command goes through. The global mount namespace, as at boot: a daemon
// started from here must see the mounts init sees, not the app's own view
val Root = RootExec { ProcessBuilder("su", "--mount-master", "-c", it).start() }

// ua_ws_path()'s workspace on Android; the binary lives in its bin/, the boot module only starts it
const val WORKSPACE = "/data/local/USB-Arsenal"
const val UA = "$WORKSPACE/bin/usb-arsenal"

/*
 * Reads run here, not through the daemon (UA_NO_PROXY): as root they read the same configfs and
 * workspace, and Home re-reads status every 2s, which would fill the daemon's log with
 * `Running: status --json` and bury the commands that changed something.
 */
private const val NO_PROXY = "UA_NO_PROXY=1"

private fun read() = "$NO_PROXY $UA"

// A USB device controller as /sys/class/udc lists it, and its state ("configured": a host is
// talking to it, "not attached": nothing is)
data class Udc(val name: String, val state: String)

// "<name> <state>" a line; a state can be several words
fun parseUdcs(lines: List<String>): List<Udc> = lines.mapNotNull { line ->
    val l = line.trim().ifEmpty { return@mapNotNull null }
    val gap = l.indexOf(' ')
    if (gap < 0) Udc(l, "") else Udc(l.substring(0, gap), l.substring(gap + 1).trim())
}.filterNot { it.name.startsWith("dummy_udc.") } // GKI's built-in dummy_hcd reaches no host; the backend skips it too

/*
 * The controller every command uses. With more than one, the backend reads the setup's `udc`
 * key, so the pick is set once (`setup set udc=NAME`) and no command carries a flag; with one
 * there is no pick. The app only mirrors the key, from the last status read.
 */
object Controller {
    var picked by mutableStateOf<String?>(Cache.status?.setup?.get("udc")?.ifEmpty { null })
        private set

    // What /sys/class/udc listed last, from the cache first
    var udcs by mutableStateOf<List<Udc>?>(Cache.udcs)

    // The setup says, after a status read
    fun mirror(setup: Map<String, String>) {
        picked = setup["udc"]?.ifEmpty { null }
    }

    /*
     * Writes the pick into the setup through [binary] (the staged one before the backend is
     * installed, since Check runs it), and shows it at once. A pick is never cleared by the app:
     * a controller gone to host mode comes back, and the user picks another in Settings.
     */
    suspend fun set(name: String, binary: String = UA): RootResult {
        val r = Root.change("$NO_PROXY ${shellQuote(binary)} setup set ${shellQuote("udc=$name")}")
        if (r.ok) picked = name
        return r
    }

    // Reads the controllers; whether the user has to pick one now comes back. A failed read
    // keeps the last list and asks nothing
    suspend fun refresh(): Boolean {
        val found = Root.read("""for u in /sys/class/udc/*; do [ -e "${'$'}u" ] && echo "${'$'}{u##*/} ${'$'}(cat "${'$'}u/state" 2>/dev/null)"; done; true""")
            .takeIf { it.ok }?.let { parseUdcs(it.out) } ?: return false
        udcs = found
        Cache.udcs = found
        return found.size > 1 && picked == null
    }
}

// check probes by creating functions in configfs, so once the gadget may be live it goes through
// the change lane and never races a command
suspend fun readCheck(): Result<Capabilities> = Root.change("${read()} check --json").json(::parseCheck)

// The C side colours its [+] lines even through a pipe
private val ANSI = Regex("\u001B\\[[0-9;]*[A-Za-z]")

fun plain(line: String) = line.replace(ANSI, "")

// What a shell shows of check's report: no colour (it always colours), no indent, and its marks
// as the installer's [+] and [-], since VT323 has no check mark or cross. The blank lines that
// part its sections stay
private const val REPORT_END = "@@check-json"

// The report and the --json lines of one readCheckReport; a report cut short (a timeout before the
// marker) keeps what it printed, which is where it stalled
fun splitCheck(out: List<String>): Pair<List<String>, List<String>> {
    val end = out.indexOf(REPORT_END)
    val report = (if (end < 0) out else out.take(end))
        .map { plain(it).trim().replace("[✓]", "[+]").replace("[✗]", "[-]") }
        .dropWhile { it.isEmpty() }
        .dropLastWhile { it.isEmpty() }
    return report to if (end < 0) emptyList() else out.drop(end + 1)
}

/*
 * Check's shell: the report check prints, then its --json from the same root shell, for the
 * app's own use. Both run the binary Check stages, with the controller picked. The report
 * comes back even when the JSON does not.
 * ponytail: check probes twice, once per format; one run that prints both if a kernel ever minds
 */
suspend fun readCheckReport(binary: String): Pair<List<String>, Result<Capabilities>> {
    val check = "$NO_PROXY ${shellQuote(binary)} check"
    val r = Root.read("$check 2>&1; echo $REPORT_END; $check --json", timeoutMs = 30_000)
    val (report, json) = splitCheck(r.out)
    return report to r.copy(out = json).json(::parseCheck)
}

/*
 * identity show: "Profile: <path or built-in defaults>[ (copy)]", then a key column,
 * "  tether_function  ncm", where a derived value reads "(derived)". The profile comes back
 * as the "profile" key.
 */
suspend fun readProfile(): Map<String, String> {
    val out = Root.read("${read()} identity show").out.ifEmpty { return emptyMap() }
    val keys = out.filter { it.startsWith("  ") }
        .mapNotNull { line -> line.trim().split(Regex("\\s+"), limit = 2).takeIf { it.size == 2 }?.let { it[0] to it[1] } }
        .filter { it.second != "(derived)" }
    val profile = out.firstOrNull { it.startsWith("Profile: ") }?.removePrefix("Profile: ")?.removeSuffix(" (copy)")
    return (keys + listOfNotNull(profile?.let { "profile" to it })).toMap()
}

// Whether Droidspaces is installed, and its running containers by name, which tether --gateway
// takes; [running] is null when they could not be read, which is not the same as none
data class Droidspaces(val installed: Boolean, val running: List<String>?)

suspend fun readDroidspaces(): Droidspaces {
    // A Droidspaces from before --json ignores the flag and prints its table; its JSON was --format
    val r = Root.read("d=$DROIDSPACES; [ -x \$d ] || exit 3; o=\$(\$d show --json); case \"\$o\" in \"{\"*) echo \"\$o\" ;; *) \$d show --format ;; esac")
    if (r.code == 3) return Droidspaces(false, null)
    return Droidspaces(true, if (r.ok) runCatching { parseContainers(r.out.joinToString("\n")) }.getOrNull() else null)
}

// The folder's files as `stat -c '%s %n'` prints them
fun parseImages(lines: List<String>): List<ImageFile> = lines.mapNotNull { line ->
    val bytes = line.substringBefore(' ').toLongOrNull() ?: return@mapNotNull null
    ImageFile(line.substringAfter(' '), bytes)
}.sortedBy { it.name.lowercase() }

const val DROIDSPACES = "/data/local/Droidspaces/bin/droidspaces"

fun parseContainers(json: String): List<String> =
    JSONObject(json).optJSONArray("running")?.let { a -> (0 until a.length()).map { a.getJSONObject(it).getString("name") } }.orEmpty()

suspend fun readStatus(): Result<GadgetStatus> = Root.read("${read()} status --json").json(::parseStatus)

/*
 * What su -v prints, as it prints it: "3.3.0:KernelSU" on the a166p. A failure carries what a shell would have said. KernelSU hides su from an app it has not
 * granted, so a missing su reads as sh reports it; a su that ran and refused says so on stderr.
 */
suspend fun rootInfo(): Result<String> {
    val timeoutMs = 15_000L
    val r = Root.read("id -u; su -v", timeoutMs)
    // The uid decides: the exit code is su -v's, which a root shell without su on its PATH fails
    return when {
        r.out.firstOrNull() == "0" -> Result.success(r.out.getOrNull(1).orEmpty())
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
    // Whether f_hid takes an interval; null from a backend older than the probe
    val hidInterval: Probe? = null,
    // The JSON it was parsed from, for the cache
    val raw: String = "",
)

// An ejected LUN has an empty file, mode and access
data class Lun(val lun: Int, val file: String, val mode: String, val access: String)

// [interval] is the poll interval exponent the function runs with: 0 for the kernel's default
// (1 ms), -1 where the kernel has no control for it (stock f_hid keeps 1 ms) or the backend is
// older
data class HidNode(val function: String, val node: String, val interval: Int = -1)

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
    // What the phone's end of the link has sent and received; -1 unread, or an older backend
    val txBytes: Long = -1,
    val rxBytes: Long = -1,
)

data class GadgetStatus(
    val version: String,
    val udc: String,
    val udcState: String,
    val bound: Boolean,
    val storage: List<Lun>,
    val hid: List<HidNode>,
    val tether: Tether?,
    // The boot this was read in, the setup (what each function starts with), what the last
    // successful start used, and whether they differ per function; empty from an older backend
    val bootId: String = "",
    val setup: Map<String, String> = emptyMap(),
    val running: Map<String, String> = emptyMap(),
    val pending: Map<String, Boolean> = emptyMap(),
    val raw: String = "",
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
        if (has("hid_interval")) p("hid_interval") else null,
        raw = json,
    )
}

private fun JSONObject.strings() = keys().asSequence().associateWith { getString(it) }

fun parseStatus(json: String): GadgetStatus = with(JSONObject(json)) {
    GadgetStatus(
        version = getString("version"),
        udc = getString("udc"),
        udcState = getString("udc_state"),
        bound = getInt("bound") == 1,
        storage = getJSONArray("storage").objects().map {
            Lun(it.getInt("lun"), it.getString("file"), it.getString("mode"), it.getString("access"))
        },
        hid = getJSONArray("hid").objects().map { HidNode(it.getString("function"), it.getString("node"), it.optInt("interval", -1)) },
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
                txBytes = optLong("tx_bytes", -1),
                rxBytes = optLong("rx_bytes", -1),
            )
        },
        bootId = optString("boot_id"),
        setup = optJSONObject("setup")?.strings().orEmpty(),
        running = optJSONObject("running")?.strings().orEmpty(),
        pending = optJSONObject("pending")?.let { o -> o.keys().asSequence().associateWith { o.getInt(it) == 1 } }.orEmpty(),
        raw = json,
    )
}

private fun JSONArray.objects() = (0 until length()).map(::getJSONObject)
