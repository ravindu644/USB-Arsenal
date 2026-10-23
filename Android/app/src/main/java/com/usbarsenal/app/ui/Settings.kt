package com.usbarsenal.app.ui

import com.usbarsenal.app.backend.Controller
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import android.content.Context
import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import com.usbarsenal.app.BuildConfig
import com.usbarsenal.app.backend.Backend
import com.usbarsenal.app.backend.BackendState
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

// A section after the first: a rule, then its heading
@Composable
private fun Section(label: String) {
    Rule(Modifier.padding(vertical = 10.dp))
    Group(label)
}

/*
 * Settings, in sections parted by rules: App (motion, haptics), Identity (the profile in force,
 * opening Identity), Backend (daemon mode and the PATH link, both read at the next boot, and
 * Reinstall), Credits (the fonts) and About. Daemon mode and the PATH link are Droidspaces'
 * switches, done the same way.
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
    // Either switch changed on this visit: it takes effect at the next boot, which is worth saying
    var changed by remember { mutableStateOf(false) }
    // A switch whose write failed shakes, and stderr's last line sits under it
    var fault by remember { mutableStateOf<String?>(null) }
    val shake = rememberShake()
    // The controllers as read at launch, so the USB section is there from the first frame
    val udcs = Controller.udcs.orEmpty()
    var choosingUdc by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        // Reads only, none behind the change lane; the profile is re-read, an identity may have changed
        backend = backendState(context)
        daemon = daemonMode()
        linked = pathLinked()
        readProfile().takeIf { it.isNotEmpty() }?.let { Gadget.profile = it }
    }
    fun write(r: RootResult, done: () -> Unit) {
        fault = null
        if (r.ok) {
            done()
            changed = true
        } else {
            fault = r.err.lastOrNull() ?: "[-] Failed (exit ${r.code})"
            shake.fire()
        }
    }
    val installed = backend?.state == BackendState.READY || backend?.state == BackendState.UPDATE_AVAILABLE
    Box(Modifier.fillMaxSize()) {
        Screen {
            Top { BackKey() }
            Title("Settings")
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Group("App")
                Kv("Motion") { Dip(switches.motion, { switches.motion = it }, "Motion") }
                Kv("Haptics") { Dip(switches.haptics, { switches.haptics = it }, "Haptics") }

                Section("Identity")
                LinkRow(profileName(Gadget.profile), "", openIdentity, Modifier.opener("settings/identity"))

                // Every command names it. The backend keeps the controller it holds while bound,
                // so a change waits until nothing is
                if (udcs.size > 1) {
                    Section("USB")
                    val bound = Gadget.status?.bound == true
                    LinkRow("Controller", Controller.picked ?: "none", if (bound) null else ({ choosingUdc = true }), Modifier.opener("settings/controller"))
                    if (bound) Note("stop all to change it")
                }

                Section("Backend")
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
                if (changed) Warn("both apply from the next boot")
                Key("Reinstall", Modifier.fillMaxWidth().padding(top = 6.dp, bottom = 10.dp).opener("settings/reinstall"), enabled = backend != null) {
                    backend?.let(fix)
                }

                Section("Credits")
                LinkRow("Fonts", "SIL OFL 1.1", openFonts, Modifier.opener("settings/fonts"))

                Section("About")
                Kv("App", fact = true) { Text(BuildConfig.VERSION_NAME, LcdType.data) }
                LinkRow("Source", SOURCE, onClick = {
                    context.startActivity(Intent(Intent.ACTION_VIEW, "https://$SOURCE".toUri()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                })
            }
        }
        ChoiceSheet(
            choosingUdc, "Controller", udcs.map { it.name }, Controller.picked.orEmpty(), "No controllers",
            onPick = {
                Controller.set(context, it)
                // What the kernel can do is asked again, of this controller, when Home next loads
                Gadget.caps = null
                choosingUdc = false
            },
            onDismiss = { choosingUdc = false },
            detail = { name -> udcs.firstOrNull { it.name == name }?.state.orEmpty() },
        )
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
        Terminal(text, Modifier.fill(min = 240.dp), live = false)
    }
}
