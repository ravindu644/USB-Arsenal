package com.usbarsenal.app.ui

import androidx.compose.ui.platform.LocalConfiguration
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
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
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
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.usbarsenal.app.backend.Root
import com.usbarsenal.app.backend.shellQuote
import com.usbarsenal.app.lcd.Dither
import com.usbarsenal.app.lcd.FRAME_MS
import com.usbarsenal.app.lcd.Inverted
import com.usbarsenal.app.lcd.Lcd
import com.usbarsenal.app.lcd.LcdType
import com.usbarsenal.app.lcd.LocalHaptics
import com.usbarsenal.app.lcd.LocalLcd
import com.usbarsenal.app.lcd.LocalReducedMotion
import com.usbarsenal.app.lcd.Pin
import com.usbarsenal.app.lcd.SpriteIcon
import com.usbarsenal.app.lcd.Sprites
import com.usbarsenal.app.lcd.dither
import com.usbarsenal.app.lcd.lcdClickable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.roundToInt

/*
 * One root `dd` per node, fed one report per write. f_hid sends at most one report per write,
 * so reports must never merge: each is a single pipe write (atomic below PIPE_BUF), and dd with
 * bs equal to the report size writes each read on as it came.
 */
private class HidWriter(private val node: String, private val size: Int) {
    // Bounded: while the host reads nothing (asleep, unplugged) the oldest go, so a backlog of
    // pointer moves never lands all at once when it wakes
    private val reports = Channel<ByteArray>(64, BufferOverflow.DROP_OLDEST)

    fun send(report: ByteArray) {
        reports.trySend(report)
    }

    // Runs until cancelled, which kills the dd with its shell. A dd that dies (the host gone, so
    // f_hid fails its write) breaks the pipe under the next report; another is started
    suspend fun run() {
        while (true) {
            val dd = Root.stream("dd of=${shellQuote(node)} bs=$size")
            if (dd != null) {
                try {
                    withContext(Dispatchers.IO) {
                        for (r in reports) {
                            dd.input.write(r)
                            dd.input.flush()
                        }
                    }
                } catch (_: IOException) {
                } finally {
                    withContext(NonCancellable) { dd.close() }
                }
            }
            delay(1000)
        }
    }
}

// The left stick while Keys + mouse holds it: MainActivity hands joystick moves here instead of
// letting them move focus
object Stick {
    var sink: ((Float, Float) -> Unit)? = null
}

private val KEY_H = 40.dp
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
    PollStatus()
    val context = LocalContext.current
    // Kept through a status read that fails: one bad poll must not kill the writers
    val status = Gadget.status
    var hid by remember { mutableStateOf(status?.hid.orEmpty()) }
    LaunchedEffect(status) { if (status != null) hid = status.hid }
    val kbdNode = hid.firstOrNull { it.function == "keyboard" }?.node?.ifEmpty { null }
    val mouseNode = hid.firstOrNull { it.function == "mouse" }?.node?.ifEmpty { null }
    val on = kbdNode != null && mouseNode != null
    val kbd = remember(kbdNode) { kbdNode?.let { HidWriter(it, 8) } }
    val mouse = remember(mouseNode) { mouseNode?.let { HidWriter(it, 4) } }
    LaunchedEffect(kbd) { kbd?.run() }
    LaunchedEffect(mouse) { mouse?.run() }

    var tab by rememberSaveable { mutableIntStateOf(0) }
    var buttons by remember { mutableIntStateOf(0) }
    var mods by remember { mutableIntStateOf(0) }
    fun pointer(dx: Int = 0, dy: Int = 0, wheel: Int = 0) = mouse?.send(mouseReport(buttons, dx, dy, wheel))
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

    // Speed or Natural scroll has focus: A works them, as it does a key
    var setupFocused by remember { mutableStateOf(false) }

    // A phone held sideways: the Mouse tab cannot fit, so the whole screen scrolls instead, by the
    // title, the tabs or a label (the pad and L M R keep their own gestures)
    val window = LocalConfiguration.current
    val short = window.screenHeightDp < 600
    // Full screen: the pad and L M R alone, landscape, the system bars hidden. Only while HID runs
    var full by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(on) { if (!on) full = false }
    // Turning the phone sideways on the Mouse tab is asking for the wide pad. Only on the turn, so
    // leaving full screen while still sideways stays left (the page then scrolls)
    LaunchedEffect(short) { if (short && on && tab == 0) full = true }
    // The keys in and out of full screen have focus: A presses them, as it does any key
    var fullKeyFocused by remember { mutableStateOf(false) }
    // L M R are drawn anew on the way in or out, and a button held then would never let go; the
    // key that had focus is gone too
    LaunchedEffect(full) {
        fullKeyFocused = false
        if (buttons != 0) {
            buttons = 0
            pointer()
        }
    }
    BackHandler(enabled = full) { full = false }
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
    val pad = @Composable { modifier: Modifier, onFull: (() -> Unit)? ->
        Trackpad(
            modifier,
            HidSetup.speed,
            HidSetup.natural,
            onMove = { dx, dy -> pointer(dx, dy) },
            onTap = {
                button(MOUSE_LEFT, true)
                button(MOUSE_LEFT, false)
            },
            onWheel = { pointer(wheel = it) },
            onFull = onFull,
            onFullFocus = { fullKeyFocused = it },
        )
    }
    Box(
        Modifier.fillMaxSize().onPreviewKeyEvent {
            // A and X click on the host; B stays Back
            val bit = when (it.key) {
                KeyCode.ButtonA -> MOUSE_LEFT
                KeyCode.ButtonX -> MOUSE_RIGHT
                else -> return@onPreviewKeyEvent false
            }
            // On Keys, A presses the focused key instead
            if (!on || tab != 0 || setupFocused || fullKeyFocused) return@onPreviewKeyEvent false
            button(bit, it.type == KeyEventType.KeyDown)
            true
        },
    ) {
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
                pad(Modifier.weight(1f).systemGestureExclusion(), null)
                Row(horizontalArrangement = Arrangement.spacedBy(Lcd.tight), verticalAlignment = Alignment.CenterVertically) {
                    Key(
                        "Leave full screen",
                        Modifier.width(56.dp).onFocusChanged { fullKeyFocused = it.isFocused },
                        icon = Sprites.Collapse,
                        padding = 0.dp,
                    ) { full = false }
                    Box(Modifier.weight(1f)) { MouseButtons { bit, down -> button(bit, down) } }
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
                    val below = Lcd.touch + Lcd.pixel * 4 + Lcd.pixel + Lcd.touch * 2 + Lcd.section * 4
                    val padHeight = if (maxHeight == Dp.Infinity) {
                        (window.screenHeightDp / 2).dp.coerceIn(120.dp, KEYS_ABOVE_BOX)
                    } else {
                        KEYS_ABOVE_BOX.coerceAtMost(maxHeight - below).coerceAtLeast(120.dp)
                    }
                    Column(verticalArrangement = Arrangement.spacedBy(Lcd.section)) {
                        pad(Modifier.height(padHeight), if (on) ({ full = true }) else null)
                        MouseButtons { bit, down -> button(bit, down) }
                        Rule()
                        Column(
                            Modifier.onFocusChanged { setupFocused = it.hasFocus },
                            verticalArrangement = Arrangement.spacedBy(Lcd.section),
                        ) {
                            val speed = HidSetup.speed
                            Slider("Speed", speed, 1..16, if (speed % 4 == 0) "${speed / 4}.0x" else "${speed / 4f}x", { HidSetup.setSpeed(context, it) })
                            Kv("Natural scroll") { Dip(HidSetup.natural, { HidSetup.setNatural(context, it) }, "Natural scroll") }
                        }
                    }
                }
            } else {
                KeysTab(mods, onMod = { mods = mods xor it }, onKey = { press(it) }, onType = { m, c -> press(c, m) })
            }
        }
        if (!on) HidOff()
    }
}

/*
 * .pad: a .d25 panel where a drag moves the host pointer and a tap clicks, with the pointer
 * drawn where the finger is, and a 44dp wheel strip on its right. A tick every 8dp of travel,
 * as DESIGN.md's haptics table says, so a drag is felt without buzzing.
 */
@Composable
private fun Trackpad(
    modifier: Modifier,
    speed: Int,
    natural: Boolean,
    onMove: (Int, Int) -> Unit,
    onTap: () -> Unit,
    onWheel: (Int) -> Unit,
    // Set: the key into full screen, in the pad's top-left corner
    onFull: (() -> Unit)?,
    onFullFocus: (Boolean) -> Unit = {},
) {
    // The gestures outlive a recomposition; they must reach the writer, and use the setup, as it is now
    val quarters by rememberUpdatedState(speed)
    val follow by rememberUpdatedState(natural)
    val move by rememberUpdatedState(onMove)
    val tap by rememberUpdatedState(onTap)
    val wheel by rememberUpdatedState(onWheel)
    val ink = LocalLcd.current.ink
    val haptics = LocalHaptics.current
    val density = LocalDensity.current
    val tickPx = with(density) { 8.dp.toPx() }
    val stepPx = with(density) { 16.dp.toPx() }
    var at by remember { mutableStateOf<Offset?>(null) }
    Row(modifier.fillMaxWidth().border(Lcd.pixel, ink)) {
        Box(
            Modifier
                .weight(1f)
                .fillMaxHeight()
                .dither(Dither.D25, ink)
                .pointerInput(Unit) { detectTapGestures(onTap = { at = it; tap() }) }
                .pointerInput(Unit) {
                    // Half a pixel of host travel per pixel of drag at 1.0x (4 quarters), the
                    // remainder carried over
                    var rx = 0f
                    var ry = 0f
                    var travel = 0f
                    detectDragGestures(onDragStart = { at = it }) { change, d ->
                        change.consume()
                        at = change.position
                        rx += d.x * quarters / 8
                        ry += d.y * quarters / 8
                        val dx = rx.toInt()
                        val dy = ry.toInt()
                        rx -= dx
                        ry -= dy
                        if (dx != 0 || dy != 0) move(dx, dy)
                        travel += d.getDistance()
                        if (travel >= tickPx) {
                            travel = 0f
                            haptics.dragTick()
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
                    Modifier.padding(6.dp).size(Lcd.touch).onFocusChanged { onFullFocus(it.isFocused) },
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
                            wheel(if (up != follow) 1 else -1)
                            haptics.dragTick()
                        }
                    }
                }
                .padding(vertical = 6.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            Text("^", LcdType.labelStrong)
            // The wheel's track: a dashed 6dp line, as the tether wires are drawn
            Box(
                Modifier.weight(1f).padding(vertical = 6.dp).width(6.dp).drawBehind {
                    val dash = 4.dp.toPx()
                    var y = 0f
                    while (y < size.height) {
                        drawRect(ink, Offset(0f, y), Size(size.width, minOf(dash, size.height - y)))
                        y += 2 * dash
                    }
                },
            )
            Text("v", LcdType.labelStrong)
        }
    }
}

/*
 * L M R: a button is down for as long as it is held, so a hold drags on the host. Down and up
 * follow the press events themselves, not what is drawn, so a press shorter than a frame still
 * clicks; a click with no press (TalkBack) is a down and an up at once.
 */
@Composable
private fun MouseButtons(onButton: (Int, Boolean) -> Unit) {
    val ink = LocalLcd.current.ink
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
            Inverted(pressed) {
                Box(
                    Modifier
                        .weight(1f)
                        .heightIn(min = Lcd.touch)
                        .background(LocalLcd.current.ground)
                        .lcdClickable(interaction, outline = true, role = Role.Button) {
                            if (fromPress) {
                                fromPress = false
                            } else {
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
private fun KeysTab(mods: Int, onMod: (Int) -> Unit, onKey: (Int) -> Unit, onType: (Int, Int) -> Unit) {
    var text by rememberSaveable { mutableStateOf("") }
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
                        text = next.takeLast(2000)
                    },
                    Modifier.fillMaxWidth().height(box),
                    placeholder = "text goes to the host",
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

// HID off: the page shaded, Pin asking for HID first
@Composable
private fun HidOff() = Shade("Enable HID on Home\nto use this page") { Pin(3.dp) }
