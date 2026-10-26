package com.usbarsenal.app.ui

import androidx.compose.ui.platform.LocalWindowInfo
import android.content.res.Configuration
import androidx.compose.foundation.systemGestureExclusion
import android.provider.Settings
import android.view.Surface
import android.content.pm.ActivityInfo
import androidx.activity.compose.LocalActivity
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.size
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import android.os.SystemClock
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.ui.input.pointer.changedToDownIgnoreConsumed
import androidx.compose.ui.input.pointer.changedToUpIgnoreConsumed
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import java.io.IOException
import kotlinx.coroutines.channels.BufferOverflow
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.input.key.Key as KeyCode
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.focus.focusRequester
import androidx.compose.foundation.focusable
import com.usbarsenal.app.lcd.focusOutline
import com.usbarsenal.app.lcd.focusTick
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.usbarsenal.app.backend.hidWrite
import com.usbarsenal.app.lcd.dashes
import com.usbarsenal.app.lcd.Dither
import com.usbarsenal.app.lcd.FRAME_MS
import com.usbarsenal.app.lcd.Inverted
import com.usbarsenal.app.lcd.Lcd
import com.usbarsenal.app.lcd.LcdType
import com.usbarsenal.app.lcd.LocalHaptics
import com.usbarsenal.app.lcd.LocalLcd
import com.usbarsenal.app.lcd.Pin
import com.usbarsenal.app.lcd.SpriteIcon
import com.usbarsenal.app.lcd.Sprites
import com.usbarsenal.app.lcd.dither
import com.usbarsenal.app.lcd.lcdClickable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.roundToInt

/*
 * One root `hid write` per node, fed one report per pipe write (atomic below PIPE_BUF). It never
 * blocks on the node: while the host reads nothing it keeps only the newest report, so the host
 * ends in the latest state and no backlog lands when it wakes. f_hid holds one report at a time,
 * so a report written before the host took the last one replaces it: keys, where every press
 * and release counts, are [paced] (ms) to two poll periods apart, and none is ever dropped here.
 * Pointer moves may go: the newest carries the buttons as they are.
 */
private class HidWriter(private val node: String, private val size: Int, private val paced: () -> Long = { 0L }) {
    private val reports = if (paced() > 0) Channel<ByteArray>(Channel.UNLIMITED) else Channel(64, BufferOverflow.DROP_OLDEST)

    fun send(report: ByteArray) {
        reports.trySend(report)
    }

    // Runs until cancelled, which kills the writer with its shell. One that dies (HID stopped, the
    // host gone) breaks the pipe under the next report; another is started, and every start
    // first lets go of everything, so a release lost with the last one holds no key down
    suspend fun run() {
        while (true) {
            val w = hidWrite(node, size)
            if (w != null) {
                try {
                    withContext(Dispatchers.IO) {
                        w.input.write(ByteArray(size))
                        w.input.flush()
                        for (r in reports) {
                            w.input.write(r)
                            w.input.flush()
                            paced().takeIf { it > 0 }?.let { delay(it) }
                        }
                    }
                } catch (_: IOException) {
                } finally {
                    // Gone with a button or key down (the screen left mid drag): the host is let go
                    // of first, as every start lets go too. A pipe already broken just fails
                    withContext(NonCancellable + Dispatchers.IO) {
                        runCatching {
                            w.input.write(ByteArray(size))
                            w.input.flush()
                        }
                        w.close()
                    }
                }
            }
            delay(1000)
        }
    }
}

// Two poll periods of the running keyboard, in whole ms: its interval (0 is the kernel's 1 ms) at
// the bus speed the host gave it
private fun keyPace(): Long {
    val s = Gadget.status
    val n = s?.hid?.firstOrNull { it.function == "keyboard" }?.interval?.takeIf { it > 0 } ?: DEFAULT_INTERVAL
    val us = if (s?.speed == "full-speed") n * 1000L else (1L shl (n - 1)) * 125
    return maxOf(1L, (2 * us + 999) / 1000)
}

// The left stick while Keys + mouse holds it: MainActivity hands joystick moves here instead of
// letting them move focus
object Stick {
    var sink: ((Float, Float) -> Unit)? = null
}

private val KEY_H = Lcd.touch
private val KEY_GAP = 10.dp
private val FKEYS = (1..12).map { "F$it" }
private val TABLE = listOf(
    listOf("ESC", "TAB", "BKSP", "DEL"),
    listOf("SHIFT", "CTRL", "ALT", "SUPER"),
    listOf("INS", "HOME", "END", "PRTSC"),
    listOf("ENTER", "ENTER", "PGUP", "PGDN"),
)
private const val TYPE_LABEL = 20

// What the table names, for the test that every key shown has a code
internal val TABLE_NAMES = TABLE.flatten().toSet()

// Rows of keys, each [cols] wide; a name given twice in a row is one key two cells wide
private fun gridHeight(rows: Int) = KEY_H * rows + KEY_GAP * (rows - 1)

// The Keys tab above its TYPE box: F-keys, the table, the arrows, with the gaps between and the
// TYPE label. The Mouse tab's pad is this tall, so its bottom edge sits where the box begins
private val KEYS_ABOVE_BOX = gridHeight(2) + gridHeight(4) + gridHeight(2) + Lcd.section * 3 + TYPE_LABEL.dp + 6.dp

/*
 * Keys + mouse: the host's keyboard and mouse, live. Mouse is a trackpad with a wheel strip and
 * L M R; Keys is F1 to F12, the editing and modifier keys, the arrows and a box whose text goes
 * to the host as typed. Modifiers latch for the next key. The gamepad's left stick drives the
 * pointer, A and X click. With HID off, the page is shaded and asks for HID first.
 */
@Composable
fun KbdMouseScreen(back: () -> Unit) {
    BackHandler(onBack = back)
    val context = LocalContext.current
    // Kept through a status read that fails: one bad poll must not kill the writers
    val status = Gadget.status
    var hid by remember { mutableStateOf(status?.hid.orEmpty()) }
    LaunchedEffect(status) { if (status != null) hid = status.hid }
    val kbdNode = hid.firstOrNull { it.function == "keyboard" }?.node?.ifEmpty { null }
    val mouseNode = hid.firstOrNull { it.function == "mouse" }?.node?.ifEmpty { null }
    val on = kbdNode != null && mouseNode != null
    val kbd = remember(kbdNode) { kbdNode?.let { HidWriter(it, 8, ::keyPace) } }
    val mouse = remember(mouseNode) { mouseNode?.let { HidWriter(it, 5) } }
    LaunchedEffect(kbd) { kbd?.run() }
    LaunchedEffect(mouse) { mouse?.run() }

    var tab by rememberSaveable { mutableIntStateOf(0) }
    var buttons by remember { mutableIntStateOf(0) }
    var mods by remember { mutableIntStateOf(0) }
    fun pointer(dx: Int = 0, dy: Int = 0, wheel: Int = 0, pan: Int = 0) = mouse?.send(mouseReport(buttons, dx, dy, wheel, pan))
    fun button(bit: Int, down: Boolean) {
        buttons = if (down) buttons or bit else buttons and bit.inv()
        pointer()
    }
    // A key goes with the latched modifiers, then they let go: Ctrl, then c, is Ctrl+C
    fun press(code: Int, extra: Int = 0) {
        kbd?.send(keyReport(mods or extra, code))
        kbd?.send(keyReport())
        mods = 0
    }
    // The pad's gestures, kept across the full-screen flip, their outputs the writers as they are now
    val haptics = LocalHaptics.current
    val slop = LocalViewConfiguration.current.touchSlop
    val density = LocalDensity.current
    // The button a pad drag holds: L M R show it, and a press of that key ends the drag. A tap's
    // key flashes for 2 frames instead, as a tapped card does: the host's 300 ms hold is not shown
    var dragBit by remember { mutableIntStateOf(0) }
    var flashBit by remember { mutableIntStateOf(0) }
    val scope = rememberCoroutineScope()
    val padOut = remember(mouse, kbd, haptics) {
        object : Pad.Out {
            override fun move(dx: Int, dy: Int) {
                pointer(dx, dy)
            }
            override fun press(bit: Int, down: Boolean) = button(bit, down)
            override fun scroll(wheel: Int, pan: Int) {
                pointer(wheel = wheel, pan = pan)
            }
            override fun zoom(notches: Int) {
                pointer(wheel = notches)
            }
            // A pinch is Ctrl on the keyboard around wheel notches on the mouse; the latched
            // modifiers are left alone, and a writer restart lets go of Ctrl with everything else
            override fun zoomHold(on: Boolean) {
                kbd?.send(keyReport(if (on) MOD_CTRL else 0))
            }
            override fun drag(bit: Int) {
                dragBit = bit
                if (bit != 0) haptics.tick() else haptics.gestureEnd()
            }
            override fun click(bit: Int) {
                haptics.keyPress()
                scope.launch {
                    flashBit = bit
                    delay(FRAME_MS * 2L)
                    if (flashBit == bit) flashBit = 0
                }
            }
            override fun tick() = haptics.frequentTick()
        }
    }
    val pad = remember(padOut) { with(density) { Pad(Pad.Config(slop, 16.dp.toPx(), 24.dp.toPx(), 8.dp.toPx(), density.density), padOut) } }
    // L M R and the gamepad: a press of the button the pad drags with ends that drag
    fun click(bit: Int, down: Boolean) {
        if (down && bit == dragBit) pad.cancel()
        button(bit, down)
    }

    // The left stick moves the pointer a frame at a time while held past its dead zone
    var stick by remember { mutableStateOf(Offset.Zero) }
    DisposableEffect(on) {
        Stick.sink = if (on) { x, y -> stick = Offset(x, y) } else null
        onDispose { Stick.sink = null }
    }
    LaunchedEffect(on) {
        while (on) {
            delay(FRAME_MS.toLong())
            val (x, y) = stick
            if (abs(x) > 0.15f || abs(y) > 0.15f) pointer((x * 14).roundToInt(), (y * 14).roundToInt())
        }
    }

    // A phone held sideways: the Mouse tab cannot fit, so the whole screen scrolls instead, by the
    // title, the tabs or a label (the pad and L M R keep their own gestures)
    val windowHeight = with(LocalDensity.current) { LocalWindowInfo.current.containerSize.height.toDp() }
    val short = windowHeight < 600.dp
    // Full screen: the pad and L M R alone, landscape, the system bars hidden. Only while HID runs:
    // a restore asks for it again, and it shows once HID is known to run, never before
    var wantFull by rememberSaveable { mutableStateOf(false) }
    val full = wantFull && on
    var wasOn by remember { mutableStateOf(on) }
    LaunchedEffect(on) {
        if (wasOn && !on) wantFull = false
        wasOn = on
    }
    // Turning the phone sideways on the Mouse tab is asking for the wide pad. Only on the turn, so
    // leaving full screen while still sideways stays left (the page then scrolls)
    LaunchedEffect(short) { if (short && on && tab == 0) wantFull = true }
    // The pad and L M R are drawn anew on the way in or out, and a button held then would never let go
    LaunchedEffect(full) {
        pad.reset()
        if (buttons != 0) {
            buttons = 0
            pointer()
        }
    }
    BackHandler(enabled = full) { wantFull = false }
    val activity = LocalActivity.current
    val view = LocalView.current
    // The screen gives back the orientation it found when it leaves
    DisposableEffect(activity) {
        val was = activity?.requestedOrientation
        onDispose { if (activity != null && was != null) activity.requestedOrientation = was }
    }
    DisposableEffect(full) {
        val window = activity?.window
        if (!full || activity == null || window == null) return@DisposableEffect onDispose {}
        val bars = WindowCompat.getInsetsController(window, view)
        val was = activity.requestedOrientation
        // How it was held: portrait or landscape, and which way up
        val portrait = activity.resources.configuration.orientation != Configuration.ORIENTATION_LANDSCAPE
        val flipped = view.display?.rotation.let { it == Surface.ROTATION_180 || it == Surface.ROTATION_270 }
        // An edge swipe shows the bars for a moment over the pad, then they hide again
        bars.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        bars.hide(WindowInsetsCompat.Type.systemBars())
        activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        onDispose {
            bars.show(WindowInsetsCompat.Type.systemBars())
            // With auto-rotate off, the landscape request moved the locked rotation, and giving
            // the request back would leave the phone sideways: ask for the way it was held instead
            val autoRotate = Settings.System.getInt(activity.contentResolver, Settings.System.ACCELEROMETER_ROTATION, 0) == 1
            activity.requestedOrientation = when {
                autoRotate -> was
                portrait && flipped -> ActivityInfo.SCREEN_ORIENTATION_REVERSE_PORTRAIT
                portrait -> ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                flipped -> ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE
                else -> ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            }
        }
    }
    val trackpad = @Composable { modifier: Modifier, onFull: (() -> Unit)? ->
        Trackpad(modifier, pad, HidSetup.speed, HidSetup.natural, onButton = { bit, down -> click(bit, down) }, onScroll = { wheel, pan -> pointer(wheel = wheel, pan = pan) }, onFull = onFull)
    }
    // Keys typed into the box, kept here so a trip to the Mouse tab never empties it
    var text by rememberSaveable { mutableStateOf("") }
    Box(Modifier.fillMaxSize()) {
        if (full) {
            Column(
                Modifier
                    .fillMaxSize()
                    .background(LocalLcd.current.ground)
                    .windowInsetsPadding(WindowInsets.safeDrawing)
                    .padding(Lcd.gutter),
                verticalArrangement = Arrangement.spacedBy(Lcd.tight),
            ) {
                // A drag from the side edges is the pad's, not the system's Back gesture
                trackpad(Modifier.weight(1f).systemGestureExclusion(), null)
                Row(horizontalArrangement = Arrangement.spacedBy(Lcd.tight), verticalAlignment = Alignment.CenterVertically) {
                    Key(
                        "Leave full screen",
                        Modifier.width(56.dp),
                        icon = Sprites.Collapse,
                        padding = 0.dp,
                    ) { wantFull = false }
                    Box(Modifier.weight(1f)) { MouseButtons(dragBit or flashBit) { bit, down -> click(bit, down) } }
                }
            }
        } else Screen(scroll = tab == 0 && short) {
            Top { BackKey() }
            Title("Keys + mouse")
            Segs(listOf("Mouse", "Keys"), tab, { tab = it })
            if (tab == 0) {
                BoxWithConstraints(Modifier.fillMaxWidth()) {
                    // The pad takes what L M R, the rule, Speed and Natural scroll leave, up to the
                    // Keys tab's height above its box; a short screen (landscape) gets the floor
                    // L M R, CONFIGURE (a 20dp line and its 4dp top), Speed and Natural scroll, 4 gaps
                    val below = Lcd.touch + Lcd.pixel * 4 + 24.dp + Lcd.touch * 2 + Lcd.section * 4
                    val padHeight = if (maxHeight == Dp.Infinity) {
                        (windowHeight / 2).coerceIn(120.dp, KEYS_ABOVE_BOX)
                    } else {
                        KEYS_ABOVE_BOX.coerceAtMost(maxHeight - below).coerceAtLeast(120.dp)
                    }
                    Column(verticalArrangement = Arrangement.spacedBy(Lcd.section)) {
                        trackpad(Modifier.height(padHeight), if (on) ({ wantFull = true }) else null)
                        MouseButtons(dragBit or flashBit) { bit, down -> click(bit, down) }
                        Column(verticalArrangement = Arrangement.spacedBy(Lcd.section)) {
                            Group("Configure")
                            val speed = HidSetup.speed
                            Slider("Speed", speed, 1..16, if (speed % 4 == 0) "${speed / 4}.0x" else "${speed / 4f}x", { HidSetup.speed(it) })
                            Kv("Natural scroll") { Dip(HidSetup.natural, { HidSetup.natural(it) }, "Natural scroll") }
                        }
                    }
                }
            } else {
                KeysTab(text, { text = it }, mods, onMod = { mods = mods xor it }, onKey = { press(it) }, onType = { m, c -> press(c, m) })
            }
        }
        if (!on) HidOff(Gadget.caps.has(Fn.HID))
    }
}

/*
 * .pad: a .d25 panel that is a laptop touchpad ([pad] has the gestures and their outputs), with
 * the pointer drawn where the first finger is, and a 44dp wheel strip on its right. Every touch
 * goes to the pad, and the pad's timers (a tap's release, a drag lock letting go) fire here when
 * no touch comes first.
 */
@Composable
private fun Trackpad(
    modifier: Modifier,
    pad: Pad,
    speed: Int,
    natural: Boolean,
    // A gamepad's A and X while the pad has focus: a left and a right button, held as long as they are
    onButton: (Int, Boolean) -> Unit,
    // The strip's wheel notches
    onScroll: (Int, Int) -> Unit,
    // Set: the key into full screen, in the pad's top-left corner
    onFull: (() -> Unit)?,
) {
    // The gestures outlive a recomposition; they must reach the writer, and use the setup, as it is now
    val quarters by rememberUpdatedState(speed)
    val follow by rememberUpdatedState(natural)
    val scrollHost by rememberUpdatedState(onScroll)
    val ink = LocalLcd.current.ink
    val haptics = LocalHaptics.current
    val density = LocalDensity.current
    val stepPx = with(density) { 16.dp.toPx() }
    var at by remember { mutableStateOf<Offset?>(null) }
    val buttonHost by rememberUpdatedState(onButton)
    var padFocused by remember { mutableStateOf(false) }
    var wheelFocused by remember { mutableStateOf(false) }
    Row(modifier.fillMaxWidth().border(Lcd.pixel, ink)) {
        Box(
            Modifier
                .weight(1f)
                .fillMaxHeight()
                // The first stop, so a gamepad has the pad from the start: A and X click only over it,
                // and Enter or A on anything else presses that instead
                .focusRequester(focusOn())
                .onFocusChanged { padFocused = it.isFocused }
                .onKeyEvent {
                    val bit = when (it.key) {
                        KeyCode.ButtonA, KeyCode.Enter, KeyCode.DirectionCenter -> MOUSE_LEFT
                        KeyCode.ButtonX -> MOUSE_RIGHT
                        else -> return@onKeyEvent false
                    }
                    if (it.type == KeyEventType.KeyDown && it.nativeKeyEvent.repeatCount == 0) haptics.keyPress()
                    buttonHost(bit, it.type == KeyEventType.KeyDown)
                    true
                }
                .focusTick()
                .focusable()
                .then(if (padFocused) Modifier.focusOutline(ink) else Modifier)
                .dither(Dither.D25, LocalLcd.current)
                .pointerInput(pad) {
                    awaitPointerEventScope {
                        var deadline: Long? = null
                        // A finger that landed on the full-screen key is the key's, not the pad's
                        val taken = mutableSetOf<Long>()
                        while (true) {
                            val e = if (deadline == null) awaitPointerEvent()
                            else withTimeoutOrNull((deadline - SystemClock.uptimeMillis()).coerceAtLeast(1)) { awaitPointerEvent() }
                            pad.speed = quarters
                            pad.natural = follow
                            if (e == null) {
                                deadline = pad.timeout(SystemClock.uptimeMillis())
                                continue
                            }
                            // A touch the system takes back (a window opening, the shade) ends as a
                            // consumed up: nothing it did is a tap, and what the pad held is let go
                            var cancelled = false
                            for (c in e.changes) {
                                if (c.changedToDownIgnoreConsumed() && c.isConsumed) taken += c.id.value
                                if (c.changedToUpIgnoreConsumed() && c.isConsumed && c.id.value !in taken) cancelled = true
                                if (!c.pressed) taken -= c.id.value
                                c.consume()
                            }
                            if (cancelled) pad.cancel()
                            val fingers = e.changes.filter { it.pressed && it.id.value !in taken }.map { Finger(it.id.value, it.position.x, it.position.y) }
                            fingers.minByOrNull { it.id }?.let { at = Offset(it.x, it.y) }
                            deadline = pad.event(e.changes.first().uptimeMillis, fingers)
                        }
                    }
                },
        ) {
            at?.let { p ->
                SpriteIcon(
                    Sprites.Pointer, 2.dp,
                    Modifier.offset { IntOffset(p.x.roundToInt(), p.y.roundToInt()) }.background(LocalLcd.current.ground).padding(2.dp),
                )
            }
            if (onFull != null) {
                Key(
                    "Full screen",
                    Modifier.padding(6.dp).size(Lcd.touch),
                    icon = Sprites.Expand,
                    padding = 0.dp,
                    onClick = onFull,
                )
            }
        }
        Column(
            Modifier
                .width(44.dp)
                .fillMaxHeight()
                // Up and Down turn the wheel a step while it has focus, as a drag of 16dp does
                .onFocusChanged { wheelFocused = it.isFocused }
                .onKeyEvent {
                    val up = it.key == KeyCode.DirectionUp
                    if (it.type != KeyEventType.KeyDown || !(up || it.key == KeyCode.DirectionDown)) return@onKeyEvent false
                    scrollHost(if (up) 1 else -1, 0)
                    haptics.frequentTick()
                    true
                }
                .focusTick()
                .focusable()
                .then(if (wheelFocused) Modifier.focusOutline(ink) else Modifier)
                .background(LocalLcd.current.ground)
                // The 2dp rule between the strip and the pad
                .drawBehind { drawRect(ink, size = Size(Lcd.pixel.toPx(), size.height)) }
                .pointerInput(Unit) {
                    var acc = 0f
                    detectVerticalDragGestures { change, dy ->
                        change.consume()
                        acc += dy
                        while (abs(acc) >= stepPx) {
                            // Dragging up scrolls up, as a wheel turned away does; natural scroll
                            // moves the content with the finger instead, as a touchscreen does
                            val up = acc < 0
                            acc += if (up) stepPx else -stepPx
                            scrollHost(if (up != follow) 1 else -1, 0)
                            haptics.frequentTick()
                        }
                    }
                }
                .padding(vertical = 6.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            Text("^", LcdType.labelStrong)
            // The wheel's track: a dashed 6dp line, as the tether wires are drawn
            Box(Modifier.weight(1f).padding(vertical = 6.dp).width(6.dp).dashes(ink))
            Text("v", LcdType.labelStrong)
        }
    }
}

/*
 * L M R: a button is down for as long as it is held, so a hold drags on the host. Down and up
 * follow the press events themselves, not what is drawn, so a press shorter than a frame still
 * clicks; a click with no press (TalkBack) is a down and an up at once. A key in [held] is
 * inverted too: the pad's tap flashes its key, and a drag keeps it, and its press ends the drag.
 */
@Composable
private fun MouseButtons(held: Int, onButton: (Int, Boolean) -> Unit) {
    val ink = LocalLcd.current.ink
    val haptics = LocalHaptics.current
    val button by rememberUpdatedState(onButton)
    Row(Modifier.fillMaxWidth().border(Lcd.pixel, ink).padding(Lcd.pixel)) {
        listOf("L" to MOUSE_LEFT, "M" to MOUSE_MIDDLE, "R" to MOUSE_RIGHT).forEachIndexed { i, (label, bit) ->
            if (i > 0) Box(Modifier.width(Lcd.pixel).height(Lcd.touch).background(ink))
            val interaction = remember { MutableInteractionSource() }
            val pressed by interaction.collectIsPressedAsState()
            var fromPress by remember { mutableStateOf(false) }
            LaunchedEffect(interaction) {
                interaction.interactions.collect {
                    when (it) {
                        is PressInteraction.Press -> {
                            fromPress = true
                            haptics.keyPress()
                            button(bit, true)
                        }
                        is PressInteraction.Release -> button(bit, false)
                        is PressInteraction.Cancel -> {
                            fromPress = false
                            button(bit, false)
                        }
                    }
                }
            }
            Inverted(pressed || held and bit != 0) {
                Box(
                    Modifier
                        .weight(1f)
                        .heightIn(min = Lcd.touch)
                        .background(LocalLcd.current.ground)
                        .lcdClickable(interaction, outline = true, role = Role.Button) {
                            if (fromPress) {
                                fromPress = false
                            } else {
                                haptics.keyPress()
                                button(bit, true)
                                button(bit, false)
                            }
                        },
                    contentAlignment = Alignment.Center,
                ) { Text(label, LcdType.labelStrong) }
            }
        }
    }
}

@Composable
private fun KeysTab(text: String, onText: (String) -> Unit, mods: Int, onMod: (Int) -> Unit, onKey: (Int) -> Unit, onType: (Int, Int) -> Unit) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        // The box takes what the keys leave, and keeps a few lines when the keyboard is up
        val box = (maxHeight - KEYS_ABOVE_BOX).coerceAtLeast(120.dp)
        Column(Modifier.lcdScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(Lcd.section)) {
            KeyGrid(listOf(FKEYS.take(6), FKEYS.drop(6)), mods, onMod, onKey)
            KeyGrid(TABLE, mods, onMod, onKey)
            KeyGrid(listOf(listOf("", "UP", ""), listOf("LEFT", "DOWN", "RIGHT")), mods, onMod, onKey)
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Type", LcdType.labelStrong, Modifier.height(TYPE_LABEL.dp))
                Input(
                    text,
                    { input ->
                        // The box holds only what the host was sent, so a Backspace always erases
                        // a character the host has: what no key types (é, emoji) never goes in
                        val next = input.filter { keyFor(it) != null }
                        // What changed goes to the host: Backspace for what went, keys for what came
                        val same = text.commonPrefixWith(next).length
                        repeat(text.length - same) { onKey(NAMED_KEYS.getValue("BKSP")) }
                        next.drop(same).forEach { c -> keyFor(c)?.let { (m, code) -> onType(m, code) } }
                        onText(next.takeLast(2000))
                    },
                    Modifier.fillMaxWidth().height(box),
                    keyboard = KeyboardOptions(capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false, keyboardType = KeyboardType.Ascii),
                    singleLine = false,
                )
            }
        }
    }
}

internal fun runs(row: List<String>): List<Pair<String, Float>> =
    row.fold(mutableListOf<Pair<String, Float>>()) { out, name ->
        if (name.isNotEmpty() && out.lastOrNull()?.first == name) out[out.lastIndex] = name to out.last().second + 1 else out += name to 1f
        out
    }

// A grid of keys: modifiers latch (inverted while latched), the rest press and release
@Composable
private fun KeyGrid(rows: List<List<String>>, mods: Int, onMod: (Int) -> Unit, onKey: (Int) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(KEY_GAP)) {
        rows.forEach { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(KEY_GAP)) {
                // A name repeated side by side is one key that many cells wide; an empty name is a gap
                runs(row).forEach { (name, span) ->
                    if (name.isEmpty()) {
                        Spacer(Modifier.weight(span))
                        return@forEach
                    }
                    val mod = MODIFIERS[name]
                    val arrow = when (name) {
                        "UP" -> Sprites.ArrowUp
                        "DOWN" -> Sprites.ArrowDown
                        "LEFT" -> Sprites.ArrowLeft
                        "RIGHT" -> Sprites.ArrowRight
                        else -> null
                    }
                    Key(
                        name,
                        Modifier.weight(span),
                        primary = mod != null && mods and mod != 0,
                        padding = 2.dp,
                        icon = arrow,
                        minHeight = KEY_H,
                    ) { if (mod != null) onMod(mod) else onKey(NAMED_KEYS.getValue(name)) }
                }
            }
        }
    }
}

// HID off: the page shaded, Pin asking for HID first, or saying this kernel has none, as Home's row does
@Composable
private fun HidOff(kernelHas: Boolean) = Shade(if (kernelHas) "Enable HID on Home\nto use this page" else "No HID in\nthis kernel") { Pin(3.dp) }
