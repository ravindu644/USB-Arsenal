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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.runtime.Composable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.focusable
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
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
import com.usbarsenal.app.lcd.dashes
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
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import com.usbarsenal.app.lcd.focusOutline
import com.usbarsenal.app.lcd.focusTick

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
    val at = if (overflow == 0) 0f else loop(16_000, frame = ::marqueeAt)
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
    // An option the kernel cannot honour: ghosted, never picked or focused, skipped by L1 and R1
    enabled: (Int) -> Boolean = { true },
    // What TalkBack says for an option whose printed label is only a mark
    spoken: (Int) -> String? = { null },
) {
    val ink = LocalLcd.current.ink
    val haptics = LocalHaptics.current
    fun pick(i: Int) {
        if (i == selected || i !in options.indices || !enabled(i)) return
        haptics.tick()
        onSelect(i)
    }
    Row(
        modifier
            .height(IntrinsicSize.Min)
            .border(Lcd.pixel, ink)
            .padding(Lcd.pixel)
            .selectableGroup()
            .shoulderStep { d -> nextEnabled(selected, d, steps, enabled)?.let(::pick) },
    ) {
        options.forEachIndexed { i, label ->
            if (i > 0) Box(Modifier.width(Lcd.pixel).fillMaxHeight().background(ink))
            Seg(label, spoken(i), i == selected, enabled(i), if (cell != null) Modifier.width(cell) else Modifier.weight(1f)) { pick(i) }
        }
    }
}

@Composable
private fun Seg(label: String, spoken: String?, on: Boolean, enabled: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val body = @Composable {
        val ground = LocalLcd.current.ground
        Box(
            Modifier.fillMaxWidth().heightIn(min = Lcd.touch).background(ground).padding(horizontal = 4.dp),
            contentAlignment = Alignment.Center,
        ) { Text(label, LcdType.labelStrong, if (enabled) Modifier else Modifier.ghost(ground), maxLines = 1) }
    }
    // Drawn above its neighbours while focused, so the next cell's ground does not cover the dashed cursor
    Box(
        modifier
            .zIndex(if (focused) 1f else 0f)
            .lcdClickable(interaction, outline = on, role = Role.RadioButton, enabled = enabled, onClick = onClick)
            .semantics {
                selected = on
                if (spoken != null) contentDescription = spoken
            },
    ) {
        Inverted(on || focused, body)
    }
}

// The next option a shoulder key lands on from [from], stepping by [d] past the ghosted ones
fun nextEnabled(from: Int, d: Int, steps: Int, enabled: (Int) -> Boolean): Int? =
    generateSequence(from + d) { it + d }.takeWhile { it in 0 until steps }.firstOrNull(enabled)

// .luns: 0 to 7, then + to add one while there are fewer than eight. L1 and R1 never land on +
@Composable
fun LunStrip(count: Int, selected: Int, onSelect: (Int) -> Unit, onAdd: () -> Unit, modifier: Modifier = Modifier) {
    val options = List(count) { "$it" } + if (count < 8) listOf("+") else emptyList()
    Segs(options, selected, { if (it == count) onAdd() else onSelect(it) }, modifier, cell = Lcd.lunCell, steps = count, spoken = { if (it == count) "Add a LUN" else "LUN $it" })
}

/*
 * .dip: one function on or off. On prints 1 with the knob right; off prints O with the knob
 * left. The knob snaps across in one frame. It draws in the ink around it, so it inverts with
 * its row. A function this kernel lacks gets a dithered track with no knob, which focus skips.
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
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (!enabled) return@Row
            val mark = @Composable { Box(Modifier.weight(1f).clearAndSetSemantics {}, contentAlignment = Alignment.Center) { Text(if (on) "1" else "O", LcdType.data) } }
            if (on) mark()
            // It runs over the track's edge on its side and a dot past it above and below, so
            // where it sits reads at a glance
            Box(
                Modifier.width(26.dp).fillMaxHeight().drawBehind {
                    val edge = (Lcd.pixel * 2).toPx()
                    val rise = (Lcd.pixel * 3).toPx()
                    drawRect(ink, Offset(if (on) 0f else -edge, -rise), Size(size.width + edge, size.height + rise * 2))
                },
            )
            if (!on) mark()
        }
    }
}

/*
 * .slider: one value from a few dozen steps. The label, a 2dp track with a pixel dot on it, then
 * the value. A tap or a drag puts the dot on the step under the finger, and it jumps a step at a
 * time, never slides. D-pad Left and Right step it; at either end they move focus on instead.
 */
@Composable
fun Slider(label: String, value: Int, range: IntRange, text: String, onChange: (Int) -> Unit, modifier: Modifier = Modifier) {
    val ink = LocalLcd.current.ink
    val haptics = LocalHaptics.current
    // The gestures outlive a recomposition; they must step from the value as it is now
    val current by rememberUpdatedState(value)
    val change by rememberUpdatedState(onChange)
    var focused by remember { mutableStateOf(false) }
    val thumb = Lcd.pixel * Sprites.Dot.width
    fun set(next: Int) {
        val v = next.coerceIn(range)
        if (v == current) return
        haptics.tick()
        change(v)
    }
    Row(
        modifier.fillMaxWidth().heightIn(min = Lcd.touch),
        horizontalArrangement = Arrangement.spacedBy(Lcd.tight),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, LcdType.label)
        BoxWithConstraints(
            Modifier
                .weight(1f)
                .height(Lcd.touch)
                .then(if (focused) Modifier.focusOutline(ink) else Modifier)
                .onFocusChanged { focused = it.isFocused }
                .onPreviewKeyEvent { e ->
                    val d = when (e.key) {
                        Key.DirectionLeft -> -1
                        Key.DirectionRight -> 1
                        else -> return@onPreviewKeyEvent false
                    }
                    if (current + d !in range) return@onPreviewKeyEvent false
                    if (e.type == KeyEventType.KeyDown) set(current + d)
                    true
                }
                .shoulderStep { set(current + it) }
                .focusTick()
                .focusable()
                .semantics {
                    contentDescription = label
                    stateDescription = text
                    progressBarRangeInfo = ProgressBarRangeInfo(value.toFloat(), range.first.toFloat()..range.last.toFloat(), range.last - range.first - 1)
                    setProgress { set(it.roundToInt()); true }
                }
                // A tap lands on release and a drag only once it is sideways, so a scroll that
                // starts here still scrolls. Two blocks, not one with a launched detector: a
                // block starts on its first touch, and a launched one would miss that touch
                .pointerInput(range) { detectTapGestures { set(at(it.x, size.width, thumb.toPx(), range)) } }
                .pointerInput(range) {
                    detectHorizontalDragGestures(onDragStart = { set(at(it.x, size.width, thumb.toPx(), range)) }) { c, _ ->
                        c.consume()
                        set(at(c.position.x, size.width, thumb.toPx(), range))
                    }
                },
            contentAlignment = Alignment.CenterStart,
        ) {
            Box(Modifier.fillMaxWidth().height(Lcd.pixel).background(ink))
            val share = (value - range.first).toFloat() / (range.last - range.first)
            SpriteIcon(Sprites.Dot, Lcd.pixel, Modifier.offset(x = (maxWidth - thumb).coerceAtLeast(0.dp) * share))
        }
        Box(Modifier.width(52.dp), contentAlignment = Alignment.CenterEnd) { Text(text, LcdType.data) }
    }
}

// The step under [x] on a track [width] wide, whose ends sit half a [thumb] in from its edges
private fun at(x: Float, width: Int, thumb: Float, range: IntRange) =
    range.first + ((x - thumb / 2) / (width - thumb).coerceAtLeast(1f) * (range.last - range.first)).roundToInt()

/*
 * A page that cannot be used yet: it stays drawn under a D50 dither that takes every touch, and
 * an inverted box with [pin] and [text] steps in over it, 2 frames on. Nothing behind takes focus
 * while it shows (SheetsOpen), and Back still leaves. No key: what it asks for is done elsewhere.
 * [light] is for a box over an inverted panel (Root's terminal), where an inverted one vanishes.
 */
@Composable
fun Shade(text: String, light: Boolean = false, pin: @Composable () -> Unit) {
    val ink = LocalLcd.current.ink
    val reduced = LocalReducedMotion.current
    var shown by remember { mutableStateOf(reduced) }
    LaunchedEffect(Unit) {
        delay(2L * FRAME_MS)
        shown = true
    }
    DisposableEffect(Unit) {
        SheetsOpen.count++
        onDispose { SheetsOpen.count-- }
    }
    Box(
        Modifier
            .fillMaxSize()
            .dither(Dither.D50, ink)
            .pointerInput(Unit) { awaitPointerEventScope { while (true) awaitPointerEvent().changes.forEach { it.consume() } } },
        contentAlignment = Alignment.Center,
    ) {
        if (shown) {
            Inverted(!light) {
                Column(
                    Modifier.padding(horizontal = 36.dp).background(LocalLcd.current.ground).padding(horizontal = 16.dp, vertical = 18.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    pin()
                    Text(text, LcdType.labelStrong)
                }
            }
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
    onOpen: () -> Unit,
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
                Modifier.weight(1f).lcdClickable(open, outline = false, role = Role.Button) {
                    haptics.keyPress()
                    onOpen()
                },
                verticalArrangement = Arrangement.spacedBy(Lcd.lines),
            ) {
                // The > says the row opens a screen; the switch is only its quick way on and off
                Text("$label >", LcdType.labelStrong, Modifier.clearAndSetSemantics { contentDescription = label })
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
 * .card: a big way into a screen, in the drive panel's look: an inverted 40dp face with the
 * name and `>`, over a body with the sprite and one line. The whole card is one key. It
 * inverts while focused or pressed; the screen change waits for its release to be drawn.
 */
@Composable
fun Card(label: String, line: String, icon: Sprite, onOpen: () -> Unit, modifier: Modifier = Modifier) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val pressed by interaction.collectIsPressedAsState()
    val haptics = LocalHaptics.current
    val lit = focused || pressed
    val edge = LocalLcd.current.ink
    Box(
        modifier
            .fillMaxWidth()
            .lcdClickable(interaction, outline = false, role = Role.Button) {
                haptics.keyPress()
                onOpen()
            },
    ) {
        Inverted(lit) {
            // The face is the inverse of the card, whichever way the card is lit
            Panel({
                Text(label, LcdType.labelStrong, Modifier.weight(1f))
                Text(">", LcdType.labelStrong, Modifier.clearAndSetSemantics {})
            }, edge) {
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 84.dp).padding(horizontal = 14.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    SpriteIcon(icon, 4.dp)
                    Text(line, LcdType.data)
                }
            }
        }
    }
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
                // The > says the row opens something, as Home's rows do; outside the marquee, so it never slides away
                if (onClick != null) Text(" >", LcdType.data, Modifier.clearAndSetSemantics {})
            }
        }
    }
}

/*
 * A choice from a list: a LinkRow names the choice, and opens this sheet of rows, the chosen
 * one inverted. With nothing to choose, one ghosted line says why.
 */
@Composable
// [detail] is a fact shown at each option's right (a controller's state)
fun ChoiceSheet(
    open: Boolean,
    title: String,
    options: List<String>,
    chosen: String,
    empty: String,
    onPick: (String) -> Unit,
    onDismiss: () -> Unit,
    detail: (String) -> String = { "" },
) =
    Sheet(open, onDismiss) {
        Text(title, LcdType.labelStrong)
        Column(Modifier.bleed(10.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            // Plain: a ghost's dither knocks the thin VT323 strokes out until it cannot be read
            if (options.isEmpty()) Text(empty, LcdType.data, Modifier.padding(horizontal = 10.dp, vertical = 12.dp))
            options.forEach { FileRow(it, detail(it), null, selected = it == chosen, onPick = { onPick(it) }) }
        }
    }

/*
 * How many sheets are open. Screen keeps focus out of its content while any is: after a touch
 * nothing holds focus, and the first key would otherwise land on a control behind the scrim.
 */
object SheetsOpen {
    var count by mutableIntStateOf(0)
}

/*
 * .sheet and .scrim: a short task over the screen. The screen behind is dithered, so it reads
 * as unavailable, and takes no touch; the sheet rises from the bottom in 4 frames (the scrim
 * on the first) and Back or [onDismiss] plays it in reverse. Its ground reaches under the
 * navigation bar.
 */
@Composable
fun Sheet(
    open: Boolean,
    onDismiss: () -> Unit,
    entry: FocusRequester? = null,
    // The keys: pinned under the body, so they stay above the keyboard while the body scrolls
    footer: (@Composable RowScope.() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    // While it is open, Screen lets no focus into the screen behind (see SheetsOpen)
    DisposableEffect(open) {
        if (open) SheetsOpen.count++
        onDispose { if (open) SheetsOpen.count-- }
    }
    val state = remember { MutableTransitionState(false) }
    state.targetState = open
    if (!state.currentState && !state.targetState) return
    val risen by rememberTransition(state, label = "sheet").animateFloat({ stepSpec(4) }, label = "rise") { if (it) 1f else 0f }
    BackHandler(enabled = open, onBack = onDismiss)
    // The scrim's gesture outlives a recomposition; it has to call the newest onDismiss
    val dismiss by rememberUpdatedState(onDismiss)
    val ink = LocalLcd.current.ink
    // Keys and pads work the sheet alone: focus moves into it as it opens and cannot leave for
    // the screen behind the scrim, where Enter would press something unseen. It lands on
    // [entry], a key, so no field raises the keyboard unasked. After a touch no key can take
    // focus, so the sheet itself holds it, and the next key steps to its first control
    val group = remember { FocusRequester() }
    // Moved after the focus change that asked for it has been cancelled, never inside it
    var wraps by remember { mutableIntStateOf(0) }
    val focusManager = LocalFocusManager.current
    LaunchedEffect(wraps) {
        if (wraps == 0) return@LaunchedEffect
        runCatching { group.requestFocus() }
        focusManager.moveFocus(FocusDirection.Enter)
    }
    // Closed, focus goes back to the key that opened it (ReturnFocus)
    var from by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(open) {
        if (open) {
            from = ReturnFocus.focused
            withFrameMillis {}
            runCatching { if (entry?.requestFocus(FocusDirection.Enter) != true) group.requestFocus() }
        } else if (from != null) {
            // A Back that left with it (Discard, Apply) is already taking focus somewhere
            if (ReturnFocus.returning == null) ReturnFocus.returning = from
            from = null
        }
    }
    Box(Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize().dither(Dither.D50, ink).pointerInput(Unit) { detectTapGestures { dismiss() } })
        /*
         * The sheet is drawn beside Screen, not in it, so Screen's keyboard padding never reaches
         * it: it pads for the keyboard itself (safeDrawing, the bars, cutout and keyboard as one,
         * so nothing counts twice), and a tall sheet stops under the status bar. Its body shrinks and
         * scrolls in what is left, which brings the focused field into view above the keys
         */
        Column(
            Modifier
                .align(Alignment.BottomStart)
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top))
                .fillMaxWidth()
                .layout { measurable, constraints ->
                    val p = measurable.measure(constraints)
                    layout(p.width, p.height) { p.place(0, ((1 - risen) * p.height).roundToInt()) }
                }
                .background(LocalLcd.current.ground)
                .drawBehind { drawRect(ink, size = Size(size.width, Lcd.pixel.toPx())) }
                .pointerInput(Unit) { detectTapGestures {} }
                // safeDrawing holds the keyboard too; the sides keep clear of a landscape cutout and bar
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom))
                .padding(Lcd.gutter)
                .focusRequester(group)
                .focusProperties {
                    onExit = {
                        if (open) {
                            cancelFocusChange()
                            // Tab past the last control comes round to the first, as it does on a screen.
                            // ponytail: Shift+Tab stops at the first; wrapping to the last needs every sheet's last control
                            if (requestedFocusDirection == FocusDirection.Next) wraps++
                        }
                    }
                }
                .focusable(),
            verticalArrangement = Arrangement.spacedBy(Lcd.section),
        ) {
            Column(
                Modifier.weight(1f, fill = false).lcdScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(Lcd.section),
                content = content,
            )
            footer?.let { KeyRow(it) }
        }
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

    // [command] is what is running, in a few words
    data class Replugging(val command: String) : Port

    data class Stopping(val command: String) : Port

    data object Android : Port
}

// What the bar's row says for a while after a command fails; the fault itself is the fault line
private data object Failed

/*
 * .strip: Home's bottom bar, flush to the screen edges under a 2dp rule, its ground reaching
 * under the navigation bar. One height in every state, so nothing above it moves: Pin's stage,
 * then a row with what holds the port and Stop all, dashed while there is nothing to stop. A
 * new state cuts the row in over two bands; the stage never cuts, so Pin's moves run on.
 * [binds] counts the times the port left Android (the app thuds for those, so a replug ending
 * here only ticks); [failed] counts failed commands, each one running Pin into the port's edge
 * while the row says Failed, for 2.4s or until the next command starts.
 */
@Composable
fun BottomBar(port: Port, onStopAll: () -> Unit, modifier: Modifier, binds: Int, failed: Int) {
    // A failure from before the bar was drawn (Home opened again) is not played
    var seenFails by remember { mutableIntStateOf(failed) }
    var bonking by remember { mutableStateOf(false) }
    LaunchedEffect(failed) {
        if (failed <= seenFails) return@LaunchedEffect
        seenFails = failed
        bonking = true
        delay(BONK_MS)
        bonking = false
    }
    val busy = port is Port.Replugging || port is Port.Stopping
    val failing = bonking && !busy
    val row: Any = if (failing) Failed else port
    val rule = LocalLcd.current.ink
    Inverted(busy) {
        Column(
            modifier
                .fillMaxWidth()
                .background(LocalLcd.current.ground)
                .drawBehind { drawRect(rule, size = Size(size.width, Lcd.pixel.toPx())) }
                // Its content keeps clear of the navigation bar and a landscape cutout; its rule does not
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom))
                .padding(start = Lcd.gutter, end = Lcd.gutter, top = 14.dp, bottom = Lcd.gutter),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            PinStage(port, failing, binds)
            Redraw(row, back = false, modifier = Modifier.fillMaxWidth(), frames = 2) { shown ->
                val (word, detail) = when (shown) {
                    is Port.Bound -> "Bound" to shown.udc
                    is Port.Replugging -> "Replugging" to shown.command
                    is Port.Stopping -> "Stopping" to shown.command
                    Failed -> "Failed" to ""
                    else -> "Port with Android" to ""
                }
                Row(
                    Modifier.fillMaxWidth().heightIn(min = Lcd.touch + 4.dp).background(LocalLcd.current.ground),
                    horizontalArrangement = Arrangement.spacedBy(Lcd.tight),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Lcd.lines)) {
                        Text(word, LcdType.labelStrong, cursor = shown is Port.Replugging || shown is Port.Stopping)
                        // Always there, empty with Android, so the row never changes height
                        Marquee(detail, LcdType.data, Modifier.fillMaxWidth())
                    }
                    // Live whenever the gadget really is bound, Failed showing or not
                    Key("Stop all", Modifier.widthIn(min = 140.dp), enabled = port is Port.Bound, haptic = { gestureEnd() }, onClick = onStopAll)
                }
            }
        }
    }
}

// One failure's run at the port, as the bar's row says Failed
private const val BONK_MS = 2400L

/*
 * Pin acts out the port on a 72dp stage under the host's port: he dozes at home while Android
 * holds it, walks over and hops under it while a command runs, plugs in the moment the gadget
 * is bound (a thud when the port left Android, a tick when a replug ends), tugs while a stop
 * runs and pops out and walks home after it, and on a failure runs into the port's edge and
 * sees stars. Every move is a cut on the frame clock; with reduced motion he is put straight
 * where each state leaves him.
 */
@Composable
private fun PinStage(port: Port, bonking: Boolean, binds: Int) {
    val reduced = LocalReducedMotion.current
    val haptics = LocalHaptics.current
    val ink = LocalLcd.current.ink
    val ground = LocalLcd.current.ground
    BoxWithConstraints(Modifier.fillMaxWidth().height(72.dp)) {
        val home = 4.dp
        val mid = maxWidth / 2
        // Under the port, so a plug-in is straight up
        val dock = mid - 16.dp
        var x by remember { mutableStateOf(if (port is Port.Android) home else dock) }
        var lift by remember { mutableStateOf(0.dp) }
        var plugged by remember { mutableStateOf(port is Port.Bound) }
        var dozing by remember { mutableStateOf(port is Port.Android) }
        var dizzy by remember { mutableStateOf(false) }
        var seenBinds by remember { mutableIntStateOf(binds) }
        val pace = 4L * FRAME_MS
        // A failure is its own state, so the port settling under it does not restart the run
        LaunchedEffect(if (bonking) Failed else port::class, dock) {
            if (!bonking) dizzy = false
            // 20dp a step, every 4 frames, hopping on every other one
            suspend fun walk(to: Dp) {
                dozing = false
                if (reduced) {
                    x = to
                    return
                }
                var step = 0
                while (x != to) {
                    val d = (to - x).value
                    x = if (kotlin.math.abs(d) <= 20f) to else x + (if (d > 0) 20.dp else (-20).dp)
                    lift = if (step++ % 2 == 0) 6.dp else 0.dp
                    delay(pace)
                }
                lift = 0.dp
            }
            suspend fun popOut() {
                if (!plugged) return
                plugged = false
                lift = 0.dp
                if (!reduced) delay(pace)
            }
            // Up and down on the spot until the state changes: hopping, or tugging at the plug
            suspend fun bob(height: Dp) {
                if (reduced) return
                while (true) {
                    lift = height
                    delay(FRAME_MS * 5L)
                    lift = 0.dp
                    delay(FRAME_MS * 5L)
                }
            }
            when {
                // Dizzy until the bar's timer ends the failure, which restarts this as the real state
                bonking -> {
                    popOut()
                    walk(mid - 52.dp)
                    if (!reduced) {
                        lift = 20.dp; delay(pace)
                        lift = 14.dp; delay(pace)
                        lift = 0.dp
                    }
                    dizzy = true
                }
                port is Port.Bound -> {
                    if (!plugged) {
                        // The moment the gadget is bound, wherever he got to
                        dozing = false
                        x = dock
                        lift = 0.dp
                        plugged = true
                        // A real bind already thudded, from the app; a replug ending ticks
                        if (binds <= seenBinds) haptics.tick()
                    }
                    seenBinds = binds
                }
                port is Port.Replugging -> {
                    popOut()
                    walk(dock)
                    bob(8.dp)
                }
                port is Port.Stopping -> if (plugged) bob((-4).dp) else {
                    walk(dock)
                    bob(8.dp)
                }
                else -> {
                    popOut()
                    walk(home)
                    dozing = true
                }
            }
        }
        val sprite = when {
            dizzy -> Sprites.PinDizzy
            plugged -> Sprites.PinHappy
            dozing -> Sprites.PinBlink
            else -> Sprites.Pin
        }
        SpriteIcon(sprite, Lcd.pixel, Modifier.offset { IntOffset(x.roundToPx(), ((if (plugged) 10.dp else 34.dp) - lift).roundToPx()) })
        // The host's port, its slot filled once he is in it
        Box(Modifier.offset(mid - 20.dp, 0.dp).size(40.dp, 16.dp).background(ink)) {
            Box(Modifier.offset(6.dp, 5.dp).size(28.dp, 6.dp).background(if (plugged) ink else ground))
        }
        // The floor he walks on, dotted
        Box(Modifier.align(Alignment.BottomStart).fillMaxWidth().height(Lcd.pixel).dashes(ink))
        // Off at rest, so reduced motion shows none
        if (plugged && loop(2600) { it in 40 until 360 }) {
            SpriteIcon(Sprites.Spark, Lcd.pixel, Modifier.offset(mid - 43.dp, 6.dp))
            SpriteIcon(Sprites.Spark, Lcd.pixel, Modifier.offset(mid + 29.dp, 6.dp))
        }
        if (dizzy) {
            val sway = loop(600) { if (it < 300) 0.dp else 14.dp }
            SpriteIcon(Sprites.Spark, Lcd.pixel, Modifier.offset { IntOffset((x + sway).roundToPx(), 12.dp.roundToPx()) })
        }
        if (dozing) {
            val rise = loop(1800) { it / 600 }
            Text("z", LcdType.labelStrong, Modifier.offset(home + 34.dp + 4.dp * rise, 22.dp - 8.dp * rise))
        }
    }
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
