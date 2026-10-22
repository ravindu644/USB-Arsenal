package com.usbarsenal.app.ui

import androidx.compose.foundation.background
import com.usbarsenal.app.lcd.stepSpec
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.animation.core.rememberTransition
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.MutableTransitionState
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.usbarsenal.app.lcd.Dither
import com.usbarsenal.app.lcd.FRAME_MS
import com.usbarsenal.app.lcd.Haptics
import com.usbarsenal.app.lcd.Inverted
import com.usbarsenal.app.lcd.Lcd
import com.usbarsenal.app.lcd.LcdType
import com.usbarsenal.app.lcd.LocalHaptics
import com.usbarsenal.app.lcd.LocalLcd
import com.usbarsenal.app.lcd.LocalReducedMotion
import com.usbarsenal.app.lcd.Pin
import com.usbarsenal.app.lcd.Sprite
import com.usbarsenal.app.lcd.SpriteIcon
import com.usbarsenal.app.lcd.Sprites
import com.usbarsenal.app.lcd.dither
import com.usbarsenal.app.lcd.ghost
import com.usbarsenal.app.lcd.lcdClickable
import com.usbarsenal.app.lcd.loop
import com.usbarsenal.app.lcd.shoulderStep
import kotlin.math.floor
import kotlin.math.roundToInt
import kotlinx.coroutines.delay

// The controls of the function screens, each named after its class in lcd.css

/*
 * .st: how far a line that does not fit has slid, 0 to 1, at [ms] into its 16s loop. One pass
 * is 8s, then it comes back: it holds for the first and last 20% of a pass and crosses the
 * middle 60% in 32 steps.
 */
fun marqueeAt(ms: Int): Float {
    val pass = if (ms < 8_000) ms else 16_000 - ms
    return floor(((pass / 8_000f - 0.2f) / 0.6f).coerceIn(0f, 1f) * 32) / 32
}

// Text that does not fit slides to show its end and back; text that fits stays still, at [end] or the start
@Composable
fun Marquee(text: String, style: TextStyle, modifier: Modifier = Modifier, end: Boolean = false) {
    var box by remember { mutableIntStateOf(0) }
    var width by remember { mutableIntStateOf(0) }
    val overflow = (width - box).coerceAtLeast(0)
    val at = if (overflow == 0) 0f else loop(16_000, ::marqueeAt)
    Box(
        modifier.clipToBounds().onSizeChanged { box = it.width },
        contentAlignment = if (overflow == 0 && end) Alignment.CenterEnd else Alignment.CenterStart,
    ) {
        Text(
            text, style,
            Modifier
                .wrapContentWidth(Alignment.Start, unbounded = true)
                .onSizeChanged { width = it.width }
                .offset { IntOffset(-(overflow * at).roundToInt(), 0) },
            maxLines = 1,
        )
    }
}

// margin: 0 -10px. A list reaches past the gutter while its text stays on the gutter
fun Modifier.bleed(by: Dp) = layout { measurable, constraints ->
    // An unbounded width has no edge to reach past
    val extra = if (constraints.hasBoundedWidth) 2 * by.roundToPx() else 0
    val placeable = measurable.measure(
        constraints.copy(minWidth = constraints.minWidth + extra, maxWidth = constraints.maxWidth + extra),
    )
    layout(placeable.width - extra, placeable.height) { placeable.place(-extra / 2, 0) }
}

/*
 * .segs: one choice of several, 2dp ink between the options, the chosen one inverted. A focused
 * option inverts too, and the chosen one shows the dashed cursor instead. L1 and R1 step
 * through the first [steps] options while focus is inside.
 */
@Composable
fun Segs(
    options: List<String>,
    selected: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    cell: Dp? = null,
    steps: Int = options.size,
) {
    val ink = LocalLcd.current.ink
    val haptics = LocalHaptics.current
    fun pick(i: Int) {
        if (i == selected || i !in options.indices) return
        haptics.tick()
        onSelect(i)
    }
    Row(
        modifier
            .height(IntrinsicSize.Min)
            .border(Lcd.pixel, ink)
            .padding(Lcd.pixel)
            .selectableGroup()
            .shoulderStep { if (selected + it in 0 until steps) pick(selected + it) },
    ) {
        options.forEachIndexed { i, label ->
            if (i > 0) Box(Modifier.width(Lcd.pixel).fillMaxHeight().background(ink))
            Seg(label, i == selected, if (cell != null) Modifier.width(cell) else Modifier.weight(1f)) { pick(i) }
        }
    }
}

@Composable
private fun Seg(label: String, on: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val body = @Composable {
        Box(
            Modifier.fillMaxWidth().heightIn(min = Lcd.touch).background(LocalLcd.current.ground).padding(horizontal = 4.dp),
            contentAlignment = Alignment.Center,
        ) { Text(label, LcdType.labelStrong, maxLines = 1) }
    }
    // Drawn above its neighbours while focused, so the next cell's ground does not cover the dashed cursor
    Box(
        modifier
            .zIndex(if (focused) 1f else 0f)
            .lcdClickable(interaction, outline = on, role = Role.RadioButton, onClick = onClick)
            .semantics { selected = on },
    ) {
        Inverted(on || focused, body)
    }
}

// .luns: 0 to 7, then + to add one while there are fewer than eight. L1 and R1 never land on +
@Composable
fun LunStrip(count: Int, selected: Int, onSelect: (Int) -> Unit, onAdd: () -> Unit, modifier: Modifier = Modifier) {
    val options = List(count) { "$it" } + if (count < 8) listOf("+") else emptyList()
    Segs(options, selected, { if (it == count) onAdd() else onSelect(it) }, modifier, cell = Lcd.lunCell, steps = count)
}

/*
 * .dip: one function on or off. On prints ON with the knob right; off is the knob left. The knob
 * snaps across in one frame. It draws in the ink around it, so it inverts with its row. A
 * function this kernel lacks gets a dithered track with no knob, which focus skips.
 */
@Composable
fun Dip(on: Boolean, onToggle: (Boolean) -> Unit, label: String, modifier: Modifier = Modifier, enabled: Boolean = true) {
    val interaction = remember { MutableInteractionSource() }
    val haptics = LocalHaptics.current
    val ink = LocalLcd.current.ink
    Box(
        modifier
            .heightIn(min = Lcd.touch)
            .lcdClickable(interaction, outline = true, role = Role.Switch, enabled = enabled) {
                haptics.toggle(!on)
                onToggle(!on)
            }
            .semantics {
                contentDescription = label
                toggleableState = ToggleableState(on && enabled)
            }
            .padding(horizontal = 2.dp),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            Modifier
                .size(68.dp, 32.dp)
                .then(if (enabled) Modifier else Modifier.dither(Dither.D50, ink))
                .border(Lcd.pixel, ink)
                .padding(Lcd.pixel),
        ) {
            if (!enabled) return@Row
            if (on) Box(Modifier.weight(1f).fillMaxHeight(), contentAlignment = Alignment.Center) { Text("on", LcdType.labelStrong) }
            Box(Modifier.width(26.dp).fillMaxHeight().background(ink))
        }
    }
}

/*
 * .row: a function on Home. Its name and two status lines open the function; the DIP switch
 * beside them flips it. The row inverts while the opening part has focus or is held. A
 * function this kernel lacks keeps its row, ghosted, its switch dead, its lines saying why.
 */
@Composable
fun FunctionRow(
    icon: Sprite,
    label: String,
    line1: String,
    line2: String,
    on: Boolean,
    onToggle: (Boolean) -> Unit,
    // null while the function's screen does not exist yet: the row cannot be opened
    onOpen: (() -> Unit)?,
    modifier: Modifier = Modifier,
    available: Boolean = true,
    // false holds the switch where it is (a command running, the backend needing attention)
    enabled: Boolean = available,
) {
    val open = remember { MutableInteractionSource() }
    val focused by open.collectIsFocusedAsState()
    val pressed by open.collectIsPressedAsState()
    val haptics = LocalHaptics.current
    val body = @Composable {
        val ground = LocalLcd.current.ground
        Row(
            Modifier.fillMaxWidth().heightIn(min = Lcd.row).background(ground).padding(10.dp),
            horizontalArrangement = Arrangement.spacedBy(Lcd.tight),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(32.dp), contentAlignment = Alignment.Center) {
                SpriteIcon(icon, 2.dp, if (available) Modifier else Modifier.ghost(ground))
            }
            Column(
                Modifier.weight(1f).lcdClickable(open, outline = false, role = Role.Button, enabled = onOpen != null) {
                    haptics.keyPress()
                    onOpen?.invoke()
                },
                verticalArrangement = Arrangement.spacedBy(Lcd.lines),
            ) {
                Text(label, LcdType.labelStrong)
                Marquee(line1, LcdType.data, Modifier.fillMaxWidth())
                Marquee(line2, LcdType.data, Modifier.fillMaxWidth())
            }
            Dip(on, onToggle, label, enabled = available && enabled)
        }
    }
    Box(modifier) { Inverted(focused || pressed, body) }
}

/*
 * An inverted line that is a key: the attention key and the fault line. Null [onClick] leaves it
 * a line nobody can press.
 */
@Composable
private fun InvertedKey(
    onClick: (() -> Unit)?,
    modifier: Modifier,
    alignment: Alignment.Vertical,
    content: @Composable RowScope.() -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val haptics = LocalHaptics.current
    Box(
        modifier.fillMaxWidth().lcdClickable(interaction, outline = true, role = Role.Button, enabled = onClick != null) {
            haptics.keyPress()
            onClick?.invoke()
        },
    ) {
        Inverted {
            Row(
                Modifier.fillMaxWidth().heightIn(min = Lcd.touch).background(LocalLcd.current.ground).padding(horizontal = 10.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = alignment,
                content = content,
            )
        }
    }
}

// .alert: Home's one line about the backend: the state on the left, the fix on the right; it opens the fix
@Composable
fun Alert(label: String, fix: String, onClick: () -> Unit, modifier: Modifier = Modifier) =
    InvertedKey(onClick, modifier, Alignment.CenterVertically) {
        Text(label, LcdType.labelStrong, Modifier.weight(1f))
        Text(fix, LcdType.data)
    }

/*
 * a.kv: a key-value row that opens something (Identity, Source, Fonts). It reaches 10dp past
 * the gutter and inverts while focused or held, as rows do. Its value slides when it does not fit.
 */
@Composable
fun LinkRow(label: String, value: String, onClick: (() -> Unit)?, modifier: Modifier = Modifier) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val pressed by interaction.collectIsPressedAsState()
    val haptics = LocalHaptics.current
    Box(
        modifier.bleed(10.dp).lcdClickable(interaction, outline = false, role = Role.Button, enabled = onClick != null) {
            haptics.keyPress()
            onClick?.invoke()
        },
    ) {
        Inverted(focused || pressed) {
            Kv(label, Modifier.background(LocalLcd.current.ground).padding(horizontal = 10.dp)) {
                Marquee(value, LcdType.data, Modifier.weight(1f), end = true)
            }
        }
    }
}

/*
 * .sheet and .scrim: a short task over the screen. The screen behind is dithered, so it reads
 * as unavailable, and takes no touch; the sheet rises from the bottom in 4 frames (the scrim
 * on the first) and Back or [onDismiss] plays it in reverse. Its ground reaches under the
 * navigation bar.
 */
@Composable
fun Sheet(open: Boolean, onDismiss: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    val state = remember { MutableTransitionState(false) }
    state.targetState = open
    if (!state.currentState && !state.targetState) return
    val risen by rememberTransition(state, label = "sheet").animateFloat({ stepSpec(4) }, label = "rise") { if (it) 1f else 0f }
    BackHandler(enabled = open, onBack = onDismiss)
    // The scrim's gesture outlives a recomposition; it has to call the newest onDismiss
    val dismiss by rememberUpdatedState(onDismiss)
    val ink = LocalLcd.current.ink
    Box(Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize().dither(Dither.D50, ink).pointerInput(Unit) { detectTapGestures { dismiss() } })
        Column(
            Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth()
                .layout { measurable, constraints ->
                    val p = measurable.measure(constraints)
                    layout(p.width, p.height) { p.place(0, ((1 - risen) * p.height).roundToInt()) }
                }
                .background(LocalLcd.current.ground)
                .drawBehind { drawRect(ink, size = Size(size.width, Lcd.pixel.toPx())) }
                .pointerInput(Unit) { detectTapGestures {} }
                .windowInsetsPadding(WindowInsets.navigationBars)
                .padding(Lcd.gutter),
            verticalArrangement = Arrangement.spacedBy(Lcd.section),
            content = content,
        )
    }
}

// .cells: work long enough to wait on, in whole tenths, so it steps like everything else
@Composable
fun Cells(tenths: Int, modifier: Modifier = Modifier) {
    val ink = LocalLcd.current.ink
    Row(
        modifier.fillMaxWidth().height(28.dp).border(Lcd.pixel, ink).padding(Lcd.pixel),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        repeat(10) { Box(Modifier.weight(1f).fillMaxHeight().then(if (it < tenths) Modifier.background(ink) else Modifier)) }
    }
}

// Who holds the port, as the bottom bar shows it
sealed interface Port {
    data class Bound(val udc: String) : Port

    // [command] is what is running, as typed
    data class Replugging(val command: String) : Port

    data object Android : Port
}

/*
 * .strip: Home's bottom bar, flush to the screen edges under a 2dp rule, its ground reaching
 * under the navigation bar. A new state cuts in over two bands.
 */
@Composable
fun BottomBar(port: Port, onStopAll: () -> Unit, modifier: Modifier = Modifier) = Redraw(
    port,
    back = false,
    modifier = modifier.fillMaxWidth(),
    frames = 2,
    // A shorter state paints over the bottom of the old one, where it will sit, not over its top
    contentAlignment = Alignment.BottomStart,
) { shown ->
    val rule = LocalLcd.current.ink
    val body = @Composable {
        Column(
            Modifier
                .fillMaxWidth()
                .background(LocalLcd.current.ground)
                .drawBehind { drawRect(rule, size = Size(size.width, Lcd.pixel.toPx())) }
                .windowInsetsPadding(WindowInsets.navigationBars)
                .padding(start = Lcd.gutter, end = Lcd.gutter, top = 14.dp, bottom = Lcd.gutter),
            verticalArrangement = Arrangement.spacedBy(Lcd.section),
        ) {
            Row(
                Modifier.heightIn(min = Lcd.touch),
                horizontalArrangement = Arrangement.spacedBy(Lcd.tight),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                when (shown) {
                    is Port.Bound -> {
                        SpriteIcon(Sprites.Pin, 2.dp)
                        Column(verticalArrangement = Arrangement.spacedBy(Lcd.lines)) {
                            Text("Bound", LcdType.labelStrong)
                            Text(shown.udc, LcdType.data)
                        }
                    }
                    is Port.Replugging -> {
                        Pin(2.dp)
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Lcd.lines)) {
                            Text("Replugging", LcdType.labelStrong, cursor = true)
                            Marquee(shown.command, LcdType.data, Modifier.fillMaxWidth())
                        }
                    }
                    Port.Android -> {
                        SpriteIcon(Sprites.PinBlink, 2.dp)
                        Text("Port with Android", LcdType.labelStrong)
                    }
                }
            }
            if (shown is Port.Bound) Key("Stop all", Modifier.fillMaxWidth(), haptic = { gestureEnd() }, onClick = onStopAll)
        }
    }
    Inverted(shown is Port.Replugging, body)
}

/*
 * The error shake: Reject, and the element steps 2dp left, 2dp right and home, a frame each.
 * Reduced motion keeps the haptic and drops the movement.
 */
class Shake internal constructor(private val haptics: Haptics) {
    internal var count by mutableIntStateOf(0)

    fun fire() {
        haptics.reject()
        count++
    }
}

@Composable
fun rememberShake(): Shake {
    val haptics = LocalHaptics.current
    return remember(haptics) { Shake(haptics) }
}

// ponytail: composed{} as lcdClickable is; a Modifier.Node if a list of shaking rows ever shows it in a trace
fun Modifier.shake(shake: Shake): Modifier = composed {
    val reduced = LocalReducedMotion.current
    var dx by remember { mutableIntStateOf(0) }
    LaunchedEffect(shake.count) {
        if (shake.count == 0 || reduced) return@LaunchedEffect
        for (x in intArrayOf(-2, 2, 0)) {
            dx = x
            if (x != 0) delay(FRAME_MS.toLong())
        }
    }
    offset { IntOffset(dx.dp.roundToPx(), 0) }
}

/*
 * .fault: a failed command, under the element acted on: stderr's last line (up to three lines)
 * and a LOG tag. It is a key that opens Log, and it stays until the next action.
 */
@Composable
fun Fault(message: String, onLog: (() -> Unit)?, modifier: Modifier = Modifier) = InvertedKey(onLog, modifier, Alignment.Top) {
    Text(message, LcdType.data, Modifier.weight(1f), maxLines = 3)
    // The tag says where it leads; a fault with nothing to open does not promise it
    if (onLog != null) Inverted { Text("Log", LcdType.labelStrong, Modifier.background(LocalLcd.current.ground).padding(horizontal = 6.dp)) }
}
