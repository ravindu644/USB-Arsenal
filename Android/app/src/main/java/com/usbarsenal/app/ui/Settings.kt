package com.usbarsenal.app.ui

import android.content.Context
import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import com.usbarsenal.app.BuildConfig
import com.usbarsenal.app.backend.Backend
import com.usbarsenal.app.backend.BackendState
import com.usbarsenal.app.backend.MODULE
import com.usbarsenal.app.backend.RootResult
import com.usbarsenal.app.backend.backendState
import com.usbarsenal.app.backend.daemonMode
import com.usbarsenal.app.backend.pathLinked
import com.usbarsenal.app.backend.readProfile
import com.usbarsenal.app.backend.setDaemonMode
import com.usbarsenal.app.backend.setPathLinked
import com.usbarsenal.app.lcd.LcdType
import com.usbarsenal.app.lcd.LocalSwitches
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val SOURCE = "github.com/ravindu644/USB-Arsenal"

private fun stateWords(b: BackendState) = when (b) {
    BackendState.READY -> "ready"
    BackendState.UPDATE_AVAILABLE -> "update available"
    BackendState.NOT_INSTALLED -> "not installed"
    BackendState.MODULE_MISSING -> "module missing"
    BackendState.CORRUPTED -> "broken"
    BackendState.NO_ROOT -> "no root"
}

// "1d6b:0104 · pixel": the IDs the host sees, and the profile they come from
// Nothing when identity show could not be read: the row shows only what the backend said
private fun identityWords(p: Map<String, String>): String {
    val vid = p["id_vendor"] ?: return ""
    val pid = p["id_product"] ?: return ""
    val name = p["profile"]?.takeIf { it != "built-in defaults" }?.substringAfterLast('/')?.removeSuffix(".conf") ?: "built-in"
    return "${vid.removePrefix("0x")}:${pid.removePrefix("0x")} · $name"
}

/*
 * Settings: the app's own switches (motion, haptics), the identity the host sees, the backend
 * (its state, daemon mode and the PATH link, both read at the next boot, and Reinstall), and
 * what the app is. Daemon mode and the PATH link are Droidspaces' switches, done the same way.
 */
@Composable
fun SettingsScreen(back: () -> Unit, openIdentity: (() -> Unit)?, openFonts: () -> Unit, fix: (Backend) -> Unit) {
    BackHandler(onBack = back)
    val context = LocalContext.current
    val switches = LocalSwitches.current
    val scope = rememberCoroutineScope()
    var backend by remember { mutableStateOf<Backend?>(null) }
    var daemon by remember { mutableStateOf<Boolean?>(null) }
    var linked by remember { mutableStateOf<Boolean?>(null) }
    // A switch whose write failed shakes, and stderr's last line sits under it
    var fault by remember { mutableStateOf<String?>(null) }
    val shake = rememberShake()
    LaunchedEffect(Unit) {
        // Reads only, none behind the change lane; the profile is re-read, an identity may have changed
        backend = backendState(context)
        daemon = daemonMode()
        linked = pathLinked()
        Gadget.profile = readProfile()
    }
    fun write(r: RootResult, done: () -> Unit) {
        fault = null
        if (r.ok) {
            done()
        } else {
            fault = r.err.lastOrNull() ?: "[-] Failed (exit ${r.code})"
            shake.fire()
        }
    }
    val installed = backend?.state == BackendState.READY || backend?.state == BackendState.UPDATE_AVAILABLE
    Screen {
        Top { BackKey() }
        Title("Settings")
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            Kv("Motion") { Dip(switches.motion, { switches.motion = it }, "Motion") }
            Kv("Haptics") { Dip(switches.haptics, { switches.haptics = it }, "Haptics") }
            LinkRow("Identity", identityWords(Gadget.profile), openIdentity)

            Group("Backend")
            Kv("State", fact = true) { Text(backend?.let { stateWords(it.state) } ?: "", LcdType.data) }
            Kv("Where", fact = true) { Marquee(MODULE, LcdType.data, Modifier.weight(1f), end = true) }
            // Both write a file the boot module reads; a switch that cannot be read is dead
            Column(Modifier.shake(shake)) {
                Kv("Daemon mode") {
                    Dip(daemon == true, { on -> scope.launch { write(setDaemonMode(on)) { daemon = on } } }, "Daemon mode", enabled = daemon != null)
                }
                // The link lives in the module, so it needs one installed
                Kv("System path") {
                    Dip(linked == true, { on -> scope.launch { write(setPathLinked(on)) { linked = on } } }, "System path", enabled = linked != null && installed)
                }
            }
            fault?.let { Fault(it, onLog = null) }
            Note("both apply from the next boot")
            Key("Reinstall", Modifier.fillMaxWidth().padding(top = 6.dp, bottom = 10.dp), enabled = backend != null) {
                backend?.let(fix)
            }

            Group("About")
            Kv("App", fact = true) { Text(BuildConfig.VERSION_NAME, LcdType.data) }
            LinkRow("Source", SOURCE, onClick = {
                context.startActivity(Intent(Intent.ACTION_VIEW, "https://$SOURCE".toUri()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            })
            Kv("Licence", fact = true) { Text("GPL-3.0-or-later", LcdType.data) }
            LinkRow("Fonts", "SIL OFL 1.1", openFonts)
        }
    }
}

// The three faces' copyright lines, then the licence they share
private fun licence(context: Context): List<String> {
    val files = context.assets.list("licenses").orEmpty().sorted()
    val texts = files.map { f -> context.assets.open("licenses/$f").bufferedReader().readLines() }
    val body = texts.firstOrNull().orEmpty().let { lines -> lines.drop(lines.indexOfFirst { it.contains("SIL OPEN FONT LICENSE") }.coerceAtLeast(0)) }
    // The files wrap at 75 columns; the panel wraps to its own width, so each paragraph is one line
    val paragraphs = body.joinToString("\n").split(Regex("\n\\s*\n")).map { it.lines().joinToString(" ") { l -> l.trim() } }
    return texts.mapNotNull { it.firstOrNull() } + paragraphs
}

// Fonts: each face and its licence, then the licence itself on the panel, read as a document
@Composable
fun FontsScreen(back: () -> Unit) {
    BackHandler(onBack = back)
    val context = LocalContext.current
    // Off the main thread: three asset files are a read, not a frame's work
    val text by produceState(emptyList<String>()) { value = withContext(Dispatchers.IO) { licence(context) } }
    Screen {
        Top { BackKey() }
        Title("Fonts")
        Column {
            for (face in listOf("Jersey 10", "Silkscreen", "VT323")) Kv(face, fact = true) { Text("OFL 1.1", LcdType.data) }
        }
        Terminal(text, Modifier.weight(1f), live = false)
    }
}
