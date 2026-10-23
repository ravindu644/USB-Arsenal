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
import com.usbarsenal.app.backend.Controller
import com.usbarsenal.app.backend.Udc
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.os.SystemClock
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
import com.usbarsenal.app.backend.Probe
import com.usbarsenal.app.backend.RootInfo
import com.usbarsenal.app.backend.deviceArch
import com.usbarsenal.app.backend.installBackend
import com.usbarsenal.app.backend.readCheck
import com.usbarsenal.app.backend.rootInfo
import com.usbarsenal.app.backend.stageBinary
import com.usbarsenal.app.lcd.FRAME_MS
import com.usbarsenal.app.lcd.Lcd
import com.usbarsenal.app.lcd.LcdType
import com.usbarsenal.app.lcd.LocalHaptics
import com.usbarsenal.app.lcd.LocalReducedMotion
import com.usbarsenal.app.lcd.Pin
import com.usbarsenal.app.lcd.Sprite
import com.usbarsenal.app.lcd.SpriteIcon
import com.usbarsenal.app.lcd.Sprites
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

// Every screen; App keeps them as a back stack
private enum class Step { WELCOME, ROOT, CONTROLLER, CHECK, INSTALL, HOME, LOG, SETTINGS, FONTS, STORAGE, TETHER, HID, KEYS, QUACKY, IDENTITY }

// TYPING: Install was pressed and the command is typing; the installer starts when it has
private enum class Phase { BEFORE, TYPING, RUNNING, DONE, FAILED }

/*
 * What setup has found, kept for as long as the process lives (until the app is cleared from
 * recents), so Back never asks root or the kernel again. Only Retry and Recheck do.
 */
private object Setup {
    var root by mutableStateOf<Result<RootInfo>?>(null)
    var check by mutableStateOf<Result<Capabilities>?>(null)
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
    // The controllers once a launch. A set-up app on a phone with several and no pick (set up
    // before the step existed, or its pick gone) is asked for one before anything else
    LaunchedEffect(Unit) {
        if (stack.first() == Step.HOME && Controller.refresh(context)) push(Step.CONTROLLER)
        // Setup brought back past Root (the process was killed) counts its steps from them again
        else if (stack.first() != Step.HOME && stack.size > 2) Controller.refresh(context)
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
                // A function opens once its screen exists
                open = { fn ->
                    when (fn) {
                        Fn.STORAGE -> ({ push(Step.STORAGE) })
                        Fn.TETHER -> ({ push(Step.TETHER) })
                        Fn.HID -> ({ push(Step.HID) })
                    }
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

// Runs [block], then waits out the rest of [ms], so a working state is on screen long enough to read
private suspend fun <T> atLeast(ms: Long, block: suspend () -> T): T {
    val start = SystemClock.uptimeMillis()
    return block().also { delay(ms - (SystemClock.uptimeMillis() - start)) }
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
            add(ShellLine("# ", "su -v", listOf(it.su)))
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
        if (r.isSuccess) Controller.refresh(context)
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

/*
 * check --json's probes as Check.dc.html words them. A note says what a missing probe costs;
 * where check.c has more than one reason for a 0 or a -1, the note follows the same reason.
 */
private class ProbeRow(
    val label: String,
    val of: (Capabilities) -> Probe,
    val value: (Capabilities) -> String = { "ok" },
    val note: (Capabilities, Probe) -> String,
)

private fun row(label: String, note: String, of: (Capabilities) -> Probe) = ProbeRow(label, of) { _, _ -> note }

private val mustHave = listOf(
    row("Root", "nothing works") { it.root },
    row("configfs", "this kernel has no configfs usb gadget; it needs a custom kernel") { it.configfs },
    // check also says 0 when there are several controllers and no --udc, which the app cannot pass yet
    ProbeRow("Controller", { it.udc }, { it.udcName }) { _, _ -> "no usb device controller, or more than one" },
    ProbeRow("Gadget", { it.gadget }) { c, _ ->
        if (c.root == Probe.OK && c.configfs == Probe.OK && c.udc == Probe.OK) "could not create a gadget"
        else "needs the lines above first"
    },
)

private val functions = listOf(
    row("Storage", "no storage") { it.massStorage },
    row("CD-ROM", "plain isos such as windows cannot boot as a cd-rom") { it.cdrom },
    row("HID", "no keyboard and mouse") { it.hid },
    // -1 is the Samsung kernel NCM would crash; 0 is a kernel without f_ncm. Tether falls back either way
    ProbeRow("NCM", { it.ncm }) { _, p ->
        if (p == Probe.CAVEAT) "unsafe on this samsung kernel; tether uses rndis" else "no ncm in this kernel; tether uses rndis"
    },
    row("RNDIS", "no tether on old windows hosts") { it.rndis },
    row("NAT", "the host gets no internet through the phone") { it.nat },
    row("NAT6", "the host gets ipv4 only") { it.nat6 },
)

// The bundled binary checks the kernel before anything is installed
@Composable
private fun CheckScreen(back: () -> Unit, next: () -> Unit) {
    val context = LocalContext.current
    val haptics = LocalHaptics.current
    val reduced = LocalReducedMotion.current
    var run by remember { mutableIntStateOf(0) }
    // How many probe rows show their answer. A fresh result holds its cursors for a beat, then
    // resolves top to bottom, one row every 4 frames, as if each were probed in turn
    var shown by remember { mutableIntStateOf(if (Setup.check != null) Int.MAX_VALUE else 0) }
    LaunchedEffect(run) {
        if (run == 0 && Setup.check != null) return@LaunchedEffect
        shown = 0
        Setup.check = null
        val r = atLeast(LINE_PAUSE * FRAME_MS.toLong()) {
            // The install deletes the staged copy, so a Recheck after it stages again
            val bin = Setup.staged?.takeIf { it.exists() } ?: stageBinary(context)?.also { Setup.staged = it }
            bin?.let { readCheck(it.absolutePath) }
                ?: Result.failure(IllegalStateException("no usb-arsenal binary for ${Build.SUPPORTED_ABIS.firstOrNull()}"))
        }
        val rows = r.getOrNull()?.let { if (it.requirementsMet) mustHave.size + functions.size else mustHave.size } ?: 0
        Setup.check = r
        r.getOrNull()?.let { Gadget.caps = it }
        // With reduced motion they all answer at once, as the shells type at once
        if (!reduced) for (i in 1..rows) {
            delay(4L * FRAME_MS)
            shown = i
            haptics.tick()
        }
        shown = Int.MAX_VALUE
        if (r.getOrNull()?.requirementsMet != true) haptics.reject()
    }
    val result = Setup.check
    // Until the last row resolves, the screen still reads as checking
    val settled = result != null && shown == Int.MAX_VALUE
    val caps = result?.getOrNull()
    val blocked = settled && caps?.requirementsMet == false
    // A probe that is still running finishes first; it is quick and its root shell is ours
    BackHandler { if (settled) back() }
    val focus = focusOn(settled)
    Screen(
        footer = {
            KeyRow {
                val canNext = settled && caps?.requirementsMet == true
                Key(
                    "Recheck",
                    Modifier.weight(1f).then(if (settled && !canNext) Modifier.focusRequester(focus) else Modifier),
                    enabled = settled,
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
        if (settled) result.exceptionOrNull()?.let { Warn(it.message ?: "check failed") }
        if (blocked) Warn("usb arsenal cannot run on this kernel")
        // ponytail: nothing in the list takes focus, so keys cannot scroll a short window to its last rows; F3 settles it
        Column {
            if (result == null || caps != null) {
                Group("Must have")
                mustHave.forEachIndexed { i, row -> ProbeLine(row, caps.takeIf { i < shown }) }
                Group("Functions")
                if (blocked) {
                    Note("skipped: needs every must have line first")
                } else {
                    functions.forEachIndexed { i, row -> ProbeLine(row, caps.takeIf { mustHave.size + i < shown }) }
                }
            }
        }
    }
}

@Composable
private fun ProbeLine(row: ProbeRow, caps: Capabilities?) {
    val probe = caps?.let(row.of)
    Kv(row.label, fact = true) {
        when (probe) {
            null -> Cursor()
            Probe.OK -> Text(row.value(caps), LcdType.data)
            Probe.CAVEAT -> Tag("caveat")
            Probe.MISSING -> Tag("missing")
        }
    }
    if (probe != null && probe != Probe.OK) Note(row.note(caps, probe))
}

// What the install looks like from the shell; the app runs the installer itself (backend/Installer.kt)
private const val INSTALL = "./install-backend.sh"

/*
 * The ported installer, played as a root shell like Root's. A comment says what will be
 * installed, the prompt waits for Install, which types the command; the installer's lines are
 * its output, and the prompt comes back. The layout never changes. [update] is the installed
 * version when this replaces one; the app's own version needs no root.
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
            add(ShellLine("# ", "# press update to update the backend"))
        } else {
            add(ShellLine("# ", "# usb-arsenal $version for $arch"))
            add(ShellLine("# ", "# press install to install the backend"))
        }
    }
    val session = opening + when {
        phase == Phase.BEFORE -> listOf(ShellLine("# "))
        ended -> listOf(ShellLine("# ", INSTALL, log.toList()), ShellLine("# "))
        else -> listOf(ShellLine("# ", INSTALL, log.toList()))
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
                    else -> Key(if (update != null) "Update" else "Install", key, primary = true, enabled = phase == Phase.BEFORE) {
                        watching = true
                        Setup.enter()
                    }
                }
            }
        },
    ) {
        Top {
            if (setup) Steps(setupSteps(), setupSteps())
            else if (phase == Phase.BEFORE || (phase == Phase.FAILED && finished)) BackKey()
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
    LaunchedEffect(Unit) { if (Controller.udcs == null) Controller.refresh(context) }
    val udcs = Controller.udcs.orEmpty()
    var picked by rememberSaveable { mutableStateOf(Controller.picked) }
    Screen(
        footer = {
            KeyRow {
                Key("Next", Modifier.weight(1f), primary = true, enabled = udcs.any { it.name == picked }) {
                    Controller.set(context, picked)
                    // Check's answer is for the controller it ran with
                    Setup.check = null
                    next()
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
        "configured" -> "a computer is talking to it"
        "not attached" -> "nothing on the other end"
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
