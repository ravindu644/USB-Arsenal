package com.usbarsenal.app.ui

import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import com.usbarsenal.app.lcd.lcdClickable
import com.usbarsenal.app.lcd.Inverted
import com.usbarsenal.app.lcd.LocalLcd
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.usbarsenal.app.backend.Controller
import com.usbarsenal.app.backend.UA
import kotlinx.coroutines.delay
import com.usbarsenal.app.backend.Udc
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import kotlinx.coroutines.Job
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.unit.dp
import androidx.core.content.edit
import com.usbarsenal.app.BuildConfig
import com.usbarsenal.app.backend.Backend
import com.usbarsenal.app.backend.BackendState
import com.usbarsenal.app.backend.Capabilities
import com.usbarsenal.app.backend.deviceArch
import com.usbarsenal.app.backend.installBackend
import com.usbarsenal.app.backend.readCheckReport
import com.usbarsenal.app.backend.rootInfo
import com.usbarsenal.app.backend.stageBinary
import com.usbarsenal.app.lcd.Lcd
import com.usbarsenal.app.lcd.LcdType
import com.usbarsenal.app.lcd.LocalHaptics
import com.usbarsenal.app.lcd.Pin
import com.usbarsenal.app.lcd.Sprite
import com.usbarsenal.app.lcd.SpriteIcon
import com.usbarsenal.app.lcd.Sprites
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

// Status is re-read this often while the app is started (and after every command)
const val POLL_MS = 2_000L

// Every screen; App keeps them as a back stack
private enum class Step { WELCOME, ROOT, CONTROLLER, CHECK, INSTALL, HOME, LOG, SETTINGS, FONTS, STORAGE, TETHER, HID, KEYS, QUACKY, IDENTITY }

// TYPING: Install was pressed and the command is typing; the installer starts when it has
private enum class Phase { BEFORE, TYPING, RUNNING, DONE, FAILED }

/*
 * What setup has found, kept for as long as the process lives (until the app is cleared from
 * recents), so Back never asks root or the kernel again. Only Retry and Recheck do.
 */
private object Setup {
    var root by mutableStateOf<Result<String>?>(null)
    var check by mutableStateOf<Result<Capabilities>?>(null)
    // check's own report, as its shell shows it
    var report by mutableStateOf<List<String>>(emptyList())
    var staged: File? = null


    val log = mutableStateListOf<String>()
    var phase by mutableStateOf(Phase.BEFORE)
    // A Retry starts the shell session over; the first Install types on in the same one
    var retries by mutableIntStateOf(0)

    // The install belongs to no screen: leaving one, or the activity going, never stops it
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    // A new visit to Install after one that finished starts from the prompt again
    fun fresh() {
        if (phase == Phase.DONE || phase == Phase.FAILED) {
            log.clear()
            phase = Phase.BEFORE
        }
    }

    // Install pressed: the command starts typing
    fun enter() {
        log.clear()
        phase = Phase.TYPING
    }

    fun install(context: Context) {
        phase = Phase.RUNNING
        scope.launch {
            val ok = installBackend(context.applicationContext) { log += it }
            // Home draws the installed state at once, not the one from before
            if (ok) Gadget.launchReads(context.applicationContext)
            phase = if (ok) Phase.DONE else Phase.FAILED
        }
    }
}

/*
 * First launch walks Welcome, Root, Check and Install, then Home; once an install has
 * finished, launches open Home. Screens stack: Back pops one, painting bottom up, and Back on
 * the last one leaves the app. Every screen change is the LCD redraw.
 */
@Composable
fun App() {
    val context = LocalContext.current
    val prefs = context.getSharedPreferences("setup", Context.MODE_PRIVATE)
    var stack by rememberSaveable { mutableStateOf(listOf(if (prefs.getBoolean("done", false)) Step.HOME else Step.WELCOME)) }
    var back by remember { mutableStateOf(false) }
    // The installed version when Install was opened to update it
    var update by rememberSaveable { mutableStateOf<String?>(null) }
    val focusManager = LocalFocusManager.current
    val scope = rememberCoroutineScope()
    var showing by remember { mutableStateOf<Job?>(null) }
    // openers[i] held focus on stack[i] when stack[i + 1] was pushed (ReturnFocus)
    var openers by rememberSaveable { mutableStateOf(listOf<String?>()) }
    fun show(next: List<Step>, goingBack: Boolean) {
        // One at a time: a second tap in the gap below would stack a screen twice
        if (showing?.isActive == true) return
        val from = ReturnFocus.focused
        ReturnFocus.returning = if (next.size == stack.size - 1) openers.getOrNull(next.size - 1) else null
        // Padded to the stack, which a recreation can bring back longer than this
        val held = (openers + List(stack.size) { null }).take(stack.size - 1)
        openers = if (next.size > stack.size) held + from else held.take(next.size - 1)
        // Focus leaves with the old screen, so a key press during the redraw cannot reach it
        focusManager.clearFocus()
        // The key that asked has just lifted: draw that frame first. Composing the next screen
        // takes a frame of 50 to 100ms, and in the same frame it held the key down that long
        showing = scope.launch {
            withFrameNanos {}
            withFrameNanos {}
            back = goingBack
            stack = next
        }
    }
    fun push(s: Step) = show(stack + s, false)
    fun pop() = show(stack.dropLast(1), true)
    val home = {
        prefs.edit { putBoolean("done", true) }
        show(listOf(Step.HOME), false)
    }
    val setup = stack.first() != Step.HOME
    // What a backend that needs something leads to: Install, as an update over an installed one.
    // No root is no key, but the shade over every screen
    val fix = { b: Backend ->
        update = b.installed
        Setup.fresh()
        push(Step.INSTALL)
    }
    // Once a launch: the backend, the kernel, the profile, the controllers. A set-up app on a
    // phone with several and no pick (set up before the step existed) is asked for one first
    LaunchedEffect(Unit) {
        if (stack.first() == Step.HOME) {
            Gadget.launchReads(context)
            if (Controller.refresh() && stack.last() != Step.CONTROLLER) push(Step.CONTROLLER)
        } else if (stack.size > 2) {
            // Setup brought back past Root (the process was killed) counts its steps again
            Controller.refresh()
        }
    }
    // The one status loop: every screen reads Gadget.status; the read lane never waits on a command
    val owner = LocalLifecycleOwner.current
    LaunchedEffect(owner) {
        owner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                if (!setup && Gadget.backend?.state != BackendState.NO_ROOT) Gadget.refresh()
                delay(POLL_MS)
            }
        }
    }
    // A real bind (the port left Android) thuds wherever it was started; Home's Pin plugs in on it
    val haptics = LocalHaptics.current
    var thudded by remember { mutableIntStateOf(Gadget.binds) }
    LaunchedEffect(Gadget.binds) {
        if (Gadget.binds > thudded) haptics.bindPlug()
        thudded = Gadget.binds
    }
    Redraw(stack.last(), back, Modifier.fillMaxSize()) {
        when (it) {
            Step.WELCOME -> Welcome { push(Step.ROOT) }
            Step.ROOT -> RootScreen(back = ::pop) {
                // More than one controller (Root read them): the user picks theirs before Check runs
                // with it, a kept pick already picked. With one, no pick and no step
                push(if (several()) Step.CONTROLLER else Step.CHECK)
            }
            // In setup it leads on to Check; asked for at a launch, it goes back to Home
            Step.CONTROLLER -> ControllerScreen(setup, back = ::pop) { if (setup) push(Step.CHECK) else pop() }
            Step.CHECK -> CheckScreen(back = ::pop) { push(Step.INSTALL) }
            Step.INSTALL -> InstallScreen(setup, update = update, back = ::pop, home = home)
            Step.HOME -> HomeScreen(
                open = { fn ->
                    push(
                        when (fn) {
                            Fn.STORAGE -> Step.STORAGE
                            Fn.TETHER -> Step.TETHER
                            Fn.HID -> Step.HID
                        },
                    )
                },
                openLog = { push(Step.LOG) },
                openSettings = { push(Step.SETTINGS) },
                fix = fix,
            )
            Step.LOG -> LogScreen(back = ::pop)
            Step.SETTINGS -> SettingsScreen(
                back = ::pop,
                openIdentity = { push(Step.IDENTITY) },
                openFonts = { push(Step.FONTS) },
                fix = fix,
            )
            Step.FONTS -> FontsScreen(back = ::pop)
            Step.IDENTITY -> IdentityScreen(back = ::pop, openLog = { push(Step.LOG) })
            Step.STORAGE -> StorageScreen(back = ::pop, openLog = { push(Step.LOG) })
            Step.TETHER -> TetherScreen(back = ::pop, openLog = { push(Step.LOG) })
            Step.HID -> HidScreen(back = ::pop, openKeys = { push(Step.KEYS) }, openQuacky = { push(Step.QUACKY) })
            Step.KEYS -> KbdMouseScreen(back = ::pop)
            Step.QUACKY -> QuackyScreen(back = ::pop)
        }
    }
    // A set-up app that finds no root at launch: nothing works, on any screen, until it is granted
    if (!setup && Gadget.backend?.state == BackendState.NO_ROOT) NoRoot()
}

// Setup is Root, Check and Backend, and Controller after Root on a phone with several
private fun several() = (Controller.udcs?.size ?: 0) > 1

private fun setupSteps() = if (several()) 4 else 3

// Initial focus goes to the screen's primary key once it is there, from an effect, never the body
@Composable
private fun focusOn(key: Any?): FocusRequester {
    val focus = remember { FocusRequester() }
    LaunchedEffect(key) { runCatching { focus.requestFocus() } }
    return focus
}

// Welcome's parts come on one per frame, top to bottom, as DESIGN.md's rows typing on
@Composable
private fun Welcome(next: () -> Unit) {
    val shown = rememberTyped(5, 1)
    fun Modifier.from(i: Int) = drawnFrom(i, shown)
    Screen(footer = { Key("Get started", Modifier.fillMaxWidth().from(4).focusRequester(focusOn(Unit)), primary = true, onClick = next) }) {
        Top {
            Spacer(Modifier.weight(1f))
            Text("v${BuildConfig.VERSION_NAME}", LcdType.data, Modifier.from(0))
        }
        Pin(8.dp, Modifier.from(1))
        Text("USB\nArsenal", LcdType.hero, Modifier.from(2))
        Column(Modifier.from(3)) {
            Feature(Sprites.Storage, "boot an iso or share a disk")
            Feature(Sprites.Tether, "network over the usb cable")
            Feature(Sprites.Hid, "keyboard and mouse on the host")
        }
    }
}

@Composable
private fun Feature(sprite: Sprite, text: String) = Row(
    Modifier.heightIn(min = Lcd.touch),
    horizontalArrangement = Arrangement.spacedBy(Lcd.tight),
    verticalAlignment = Alignment.CenterVertically,
) {
    Box(Modifier.size(32.dp), contentAlignment = Alignment.Center) { SpriteIcon(sprite, 2.dp) }
    Text(text, LcdType.data)
}

private const val ASKING = "# checking root access"

/*
 * `id -u; su -v` in a root shell, played as that shell: a comment saying what is happening,
 * `su`, and once it answers, the root prompt's commands and what they printed. su is asked the
 * moment `su` has typed; Back waits for its answer, which the 15s timeout bounds.
 */
@Composable
private fun RootScreen(back: () -> Unit, next: () -> Unit) {
    val context = LocalContext.current
    val haptics = LocalHaptics.current
    // A result that was already there shows at once; one that arrives now types
    val had = remember { Setup.root != null }
    val result = Setup.root
    val session = buildList {
        add(ShellLine("$ ", ASKING))
        add(ShellLine("$ ", "su", result?.exceptionOrNull()?.let { listOf(it.message.orEmpty()) }.orEmpty()))
        result?.onSuccess {
            add(ShellLine("# ", "# root access granted !"))
            add(ShellLine("# ", "id -u", listOf("0")))
            add(ShellLine("# ", "su -v", listOf(it)))
            add(ShellLine("# "))
        }
    }
    val total = session.steps()
    val step = rememberTyped(total, 1, instant = had)
    val stepNow by rememberUpdatedState(step)
    val enter = session.enterAt(1)
    LaunchedEffect(Unit) {
        if (Setup.root != null) return@LaunchedEffect
        snapshotFlow { stepNow }.first { it >= enter }
        val r = rootInfo()
        // Controller's step joins the dots here, so it is there before Next lights
        if (r.isSuccess) Controller.refresh()
        Setup.root = r
    }
    val done = result != null && step >= total
    // Reject once the denial is on screen, not when su answers ahead of the typing
    LaunchedEffect(done) { if (done && result.isFailure && !had) haptics.reject() }
    BackHandler { if (done) back() }
    val focus = focusOn(done)
    Box(Modifier.fillMaxSize()) {
        Screen(
            footer = {
                // Next is there from the start, dashed until su has said yes, so nothing pops in
                if (done && result.isSuccess) {
                    Key("Next", Modifier.fillMaxWidth().focusRequester(focus), primary = true, onClick = next)
                } else {
                    Key("Next", Modifier.fillMaxWidth(), primary = true, enabled = false) {}
                }
            },
        ) {
            Top { Steps(1, setupSteps(), joins = true) }
            Title("Root")
            Shell("su", if (!done) Led.BUSY else if (result.isSuccess) Led.ON else Led.OFF, session.shownAt(step), Modifier.fill(min = 200.dp))
        }
        // Root managers today only take a grant at the app's next start, so there is no retry
        if (done && result.isFailure) NoRoot(light = true)
    }
}

// No root: the page shaded, Pin in tears, and what to do, which happens outside the app. On
// Root it sits over the terminal, so the box is [light]
@Composable
private fun NoRoot(light: Boolean = false) = Shade("Grant root, then\nreopen the app", light) { SpriteIcon(Sprites.PinSad, 3.dp) }

// What Check types; the app runs the binary it staged (backend/readCheckReport)
private const val CHECK = "usb-arsenal check"

/*
 * The bundled binary checks the kernel before anything is installed, played as a root shell
 * like Backend's: a comment says what is happening, the command types, check runs the moment
 * it has typed, and its own report is the output. A result that was already there (Back from
 * Backend) shows at once; Recheck starts the session over.
 */
@Composable
private fun CheckScreen(back: () -> Unit, next: () -> Unit) {
    val context = LocalContext.current
    val haptics = LocalHaptics.current
    var run by remember { mutableIntStateOf(0) }
    val had = remember { Setup.check != null }
    val result = Setup.check
    val session = buildList {
        add(ShellLine("# ", "# checking this kernel for usb arsenal"))
        if (result == null) {
            add(ShellLine("# ", CHECK))
        } else {
            add(ShellLine("# ", CHECK, Setup.report, block = true))
            add(ShellLine("# "))
        }
    }
    val total = session.steps()
    val step = rememberTyped(total, 1, instant = had && run == 0, key = run)
    val stepNow by rememberUpdatedState(step)
    val enter = session.enterAt(1)
    LaunchedEffect(run) {
        if (run == 0 && Setup.check != null) return@LaunchedEffect
        Setup.check = null
        snapshotFlow { stepNow }.first { it >= enter }
        // The install deletes the staged copy, so a Recheck after it stages again
        val bin = Setup.staged?.takeIf { it.exists() } ?: stageBinary(context)?.also { Setup.staged = it }
        val (report, r) = bin?.let { readCheckReport(it.absolutePath) }
            ?: (emptyList<String>() to Result.failure(IllegalStateException("no usb-arsenal binary for ${Build.SUPPORTED_ABIS.firstOrNull()}")))
        // A check that could not finish says why in the shell, under what it printed
        Setup.report = report + listOfNotNull(r.exceptionOrNull()?.let { it.message ?: "check failed" })
        Setup.check = r
        r.getOrNull()?.let { Gadget.caps = it }
    }
    val caps = result?.getOrNull()
    // Done only once the report has typed, so the keys never run ahead of what the shell says
    val done = result != null && step >= total
    val canNext = done && caps?.requirementsMet == true
    LaunchedEffect(done) { if (done && !canNext && (run > 0 || !had)) haptics.reject() }
    // Back leaves until check starts; one that is running finishes first, quick and in our own root shell
    BackHandler { if (done || stepNow < enter) back() }
    val focus = focusOn(done)
    Screen(
        footer = {
            KeyRow {
                Key(
                    "Recheck",
                    Modifier.weight(1f).then(if (done && !canNext) Modifier.focusRequester(focus) else Modifier),
                    enabled = done,
                ) { run++ }
                Key(
                    "Next",
                    Modifier.weight(1f).then(if (canNext) Modifier.focusRequester(focus) else Modifier),
                    primary = true,
                    enabled = canNext,
                    onClick = next,
                )
            }
        },
    ) {
        Top { Steps(setupSteps() - 1, setupSteps()) }
        Title("Check")
        Shell("sh", if (!done) Led.BUSY else if (canNext) Led.ON else Led.OFF, session.shownAt(step), Modifier.fill(min = 200.dp))
    }
}

// What the install looks like from the shell; the app runs the installer itself (backend/Installer.kt)
private const val INSTALL = "./install-backend.sh"

/*
 * The ported installer, played as a root shell like Root's. A comment says what will be
 * installed and the command types at once: nothing is asked, as opening the screen (setup's
 * Next, Home's update, Settings' Reinstall) already was. The installer's lines are its output,
 * typed as they come, and the prompt comes back. The layout never changes. [update] is the
 * installed version when this replaces one; the app's own version needs no root.
 */
@Composable
private fun InstallScreen(setup: Boolean, update: String?, back: () -> Unit, home: () -> Unit) {
    val context = LocalContext.current
    val haptics = LocalHaptics.current
    val phase = Setup.phase
    val log = Setup.log
    val ended = phase == Phase.DONE || phase == Phase.FAILED
    val version = BuildConfig.VERSION_NAME
    val arch = deviceArch() ?: Build.SUPPORTED_ABIS.firstOrNull().orEmpty()
    val opening = buildList {
        if (update != null) {
            // The same version rebuilt is still an update, but "1.2.0 → 1.2.0" would say nothing
            add(ShellLine("# ", if (update != version) "# update $update → $version" else "# usb-arsenal $version for $arch"))
            // The install never touches the gadget, so this holds
            add(ShellLine("# ", "# the gadget stays up while it updates"))
        } else {
            add(ShellLine("# ", "# usb-arsenal $version for $arch"))
        }
    }
    val session = opening + when {
        phase == Phase.BEFORE -> listOf(ShellLine("# "))
        // One stream of output: it types straight through, with no beat between its lines
        ended -> listOf(ShellLine("# ", INSTALL, log.toList(), block = true), ShellLine("# "))
        else -> listOf(ShellLine("# ", INSTALL, log.toList(), block = true))
    }
    val total = session.steps()
    val step = rememberTyped(total, 1, instant = ended, key = Setup.retries)
    val stepNow by rememberUpdatedState(step)
    val enter = session.enterAt(opening.size)
    // Done only once everything has typed, so the keys never run ahead of what the shell says
    val finished = ended && step >= total

    LaunchedEffect(phase, Setup.retries) {
        if (phase != Phase.TYPING) return@LaunchedEffect
        // The installer starts the moment the command has typed, as Enter would
        snapshotFlow { stepNow }.first { it >= enter }
        Setup.install(context)
    }

    // Halfway through, Back would leave a module that is half copied
    BackHandler {
        when {
            !finished && phase != Phase.BEFORE -> {}
            phase == Phase.DONE -> home()
            else -> back()
        }
    }
    // The haptic belongs to an install seen ending here, not to a screen that opens on an old one
    var watching by remember { mutableStateOf(phase == Phase.TYPING || phase == Phase.RUNNING) }
    // Opening the screen was the ask: the command types at once
    LaunchedEffect(Unit) {
        if (Setup.phase != Phase.BEFORE) return@LaunchedEffect
        watching = true
        Setup.enter()
    }
    LaunchedEffect(finished) {
        if (!finished || !watching) return@LaunchedEffect
        watching = false
        if (phase == Phase.DONE) haptics.confirm() else haptics.reject()
    }
    val focus = focusOn(phase to finished)
    val led = when {
        phase == Phase.BEFORE -> Led.OFF
        !finished -> Led.BUSY
        phase == Phase.DONE -> Led.ON
        else -> Led.OFF
    }

    Screen(
        footer = {
            KeyRow {
                val key = Modifier.weight(1f).focusRequester(focus)
                when {
                    phase == Phase.DONE && finished -> Key("Home", key, primary = true, onClick = home)
                    phase == Phase.FAILED && finished -> {
                        Key("Copy log", Modifier.weight(1f)) {
                            context.getSystemService(ClipboardManager::class.java)
                                .setPrimaryClip(ClipData.newPlainText("install log", log.joinToString("\n")))
                        }
                        Key("Retry", key, primary = true) {
                            watching = true
                            Setup.retries++
                            Setup.enter()
                        }
                    }
                    // Where Home will be, dashed until the install has ended
                    else -> Key("Home", Modifier.weight(1f), primary = true, enabled = false) {}
                }
            }
        },
    ) {
        Top {
            if (setup) Steps(setupSteps(), setupSteps())
            else if (phase == Phase.FAILED && finished) BackKey()
        }
        Title("Backend")
        Shell("sh", led, session.shownAt(step), Modifier.fill(min = 200.dp))
    }
}

/*
 * Controller: this phone has more than one USB device controller, and the backend needs to be
 * told which on every command. Each is a row with Pin: happy where a host is talking to it,
 * dozing where nothing is, which is the clue to the one the cable is in. Next keeps the pick
 * and runs Check with it.
 */
@Composable
private fun ControllerScreen(setup: Boolean, back: () -> Unit, next: () -> Unit) {
    BackHandler(onBack = back)
    val context = LocalContext.current
    // Read again when the process was killed on this step, so its rows come back
    LaunchedEffect(Unit) { if (Controller.udcs == null) Controller.refresh() }
    val udcs = Controller.udcs.orEmpty()
    var picked by rememberSaveable { mutableStateOf(Controller.picked) }
    var writing by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    Screen(
        footer = {
            KeyRow {
                Key("Next", Modifier.weight(1f), primary = true, enabled = !writing && udcs.any { it.name == picked }) {
                    // Into the setup, through the binary at hand: the staged one before the
                    // backend is installed, since Check runs that
                    writing = true
                    scope.launch {
                        val bin = if (setup) Setup.staged?.takeIf { it.exists() } ?: stageBinary(context)?.also { Setup.staged = it } else null
                        val r = Controller.set(picked!!, bin?.absolutePath ?: UA)
                        writing = false
                        if (!r.ok) return@launch
                        // Check's answer is for the controller it ran with
                        Setup.check = null
                        if (!setup) Gadget.readCheckOnce()
                        next()
                    }
                }
            }
        },
    ) {
        Top { if (setup) Steps(2, setupSteps()) else BackKey() }
        Title("Controller")
        Text("This phone has more than one USB controller. Pick the one your cable is in.", LcdType.data)
        Column(Modifier.bleed(10.dp).selectableGroup()) {
            udcs.forEachIndexed { i, udc -> ControllerRow(udc, udc.name == picked, rule = i > 0) { picked = udc.name } }
        }
    }
}

// A controller as a Home-style row: Pin, its name, its state and what that means. Inverted while picked
@Composable
private fun ControllerRow(udc: Udc, picked: Boolean, rule: Boolean, onPick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val pressed by interaction.collectIsPressedAsState()
    val haptics = LocalHaptics.current
    val ink = LocalLcd.current.ink
    val hint = when (udc.state) {
        // Only a computer configures a controller; a charger stops at powered
        "configured" -> "a computer is using it"
        "suspended" -> "plugged in, the computer is asleep"
        "attached", "powered", "default", "addressed" -> "plugged in, not set up by a computer"
        "not attached" -> "nothing plugged in"
        else -> null
    }
    Box(
        Modifier
            // The 2dp rule between rows, as Home's
            .then(if (rule) Modifier.drawWithContent { drawContent(); drawRect(ink, size = Size(size.width, Lcd.pixel.toPx())) } else Modifier)
            .lcdClickable(interaction, outline = false, role = Role.RadioButton) {
                haptics.tick()
                onPick()
            }
            .semantics { selected = picked },
    ) {
        Inverted(picked || focused || pressed) {
            Row(
                Modifier.fillMaxWidth().heightIn(min = Lcd.row).background(LocalLcd.current.ground).padding(10.dp),
                horizontalArrangement = Arrangement.spacedBy(Lcd.tight),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                SpriteIcon(if (udc.state == "configured") Sprites.PinHappy else Sprites.PinBlink, 2.dp)
                Column(verticalArrangement = Arrangement.spacedBy(Lcd.lines)) {
                    Text(udc.name, LcdType.labelStrong)
                    Text(udc.state.ifEmpty { "-" }, LcdType.data)
                    if (hint != null) Text(hint, LcdType.data)
                }
            }
        }
    }
}
