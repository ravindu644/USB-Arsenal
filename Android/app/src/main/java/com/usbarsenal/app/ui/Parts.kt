package com.usbarsenal.app.ui

import android.os.Build
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.EnterExitState
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.usbarsenal.app.lcd.FRAME_MS
import com.usbarsenal.app.lcd.Haptics
import com.usbarsenal.app.lcd.Inverted
import com.usbarsenal.app.lcd.Lcd
import com.usbarsenal.app.lcd.LcdType
import com.usbarsenal.app.lcd.LocalHaptics
import com.usbarsenal.app.lcd.LocalLcd
import com.usbarsenal.app.lcd.LocalReducedMotion
import com.usbarsenal.app.lcd.cursorOn
import com.usbarsenal.app.lcd.dashedRect
import com.usbarsenal.app.lcd.lcdClickable
import com.usbarsenal.app.lcd.loop
import com.usbarsenal.app.lcd.stepSpec
import kotlinx.coroutines.delay

// The parts of lcd.css the screens share, each named after its class there

/*
 * .lcd.phone: the gutters, 22dp from the top, and the system bars kept clear. A [bottomBar]
 * sits outside the gutters, flush to the screen edges, and keeps clear of the navigation bar
 * itself so its ground reaches under it.
 */
@Composable
fun Screen(bottomBar: (@Composable () -> Unit)? = null, content: @Composable ColumnScope.() -> Unit) = Column(
    // The keyboard pushes the bar and the content up; the bar's own navigation-bar padding then gives way
    Modifier
        .fillMaxSize()
        .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))
        .imePadding(),
) {
    Column(
        Modifier
            .weight(1f)
            .fillMaxWidth()
            .then(if (bottomBar == null) Modifier.windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom)) else Modifier)
            .padding(start = Lcd.gutter, end = Lcd.gutter, top = Lcd.topInset, bottom = if (bottomBar == null) Lcd.gutter else Lcd.section),
        verticalArrangement = Arrangement.spacedBy(Lcd.section),
        content = content,
    )
    bottomBar?.invoke()
}

/*
 * Silkscreen is a menu face and always uppercase; VT323 keeps its case. Only the first [typed]
 * characters show; the rest is laid out but clear, so text that types on never re-wraps.
 * [cursor] puts the working cursor inline after the typed part; [cursorSpace] keeps its place
 * without drawing it, so text that loses its cursor when it finishes typing keeps its wrap.
 */
@Composable
fun Text(
    text: String,
    style: TextStyle,
    modifier: Modifier = Modifier,
    cursor: Boolean = false,
    typed: Int = Int.MAX_VALUE,
    cursorSpace: Boolean = false,
    maxLines: Int = Int.MAX_VALUE,
) {
    val shown = if (style.fontFamily == LcdType.data.fontFamily) text else text.uppercase()
    val ink = LocalLcd.current.ink
    if (!cursor && !cursorSpace && typed >= shown.length) {
        BasicText(shown, modifier, style.copy(color = ink), maxLines = maxLines, overflow = TextOverflow.Clip)
        return
    }
    // 4dp gap plus the 10x16 block, as .blk's margin and size
    val box = with(LocalDensity.current) { Placeholder(14.dp.toSp(), 16.dp.toSp(), PlaceholderVerticalAlign.TextCenter) }
    BasicText(
        buildAnnotatedString {
            append(shown.take(typed))
            if (cursor || cursorSpace) appendInlineContent("cursor")
            withStyle(SpanStyle(color = Color.Transparent)) { append(shown.drop(typed)) }
        },
        modifier,
        style.copy(color = ink),
        maxLines = maxLines,
        inlineContent = mapOf("cursor" to InlineTextContent(box) { if (cursor) Cursor(Modifier.padding(start = 4.dp)) }),
    )
}

// Lists type on: element [i] is drawn once [shown] has passed it (DESIGN.md's rows typing on)
fun Modifier.drawnFrom(i: Int, shown: Int) = drawWithContent { if (i < shown) drawContent() }

/*
 * How many of [total] characters have typed: [perFrame] more every 40ms frame, and it carries
 * on when [total] grows. [instant] starts it fully typed (a result that was already there); a
 * new [key] starts it over. With reduced motion everything is typed.
 */
@Composable
fun rememberTyped(total: Int, perFrame: Int, instant: Boolean = false, key: Any? = Unit): Int {
    val reduced = LocalReducedMotion.current
    var n by remember(key) { mutableIntStateOf(if (instant) total else 0) }
    LaunchedEffect(key, total, reduced) {
        while (!reduced && n < total) {
            delay(FRAME_MS.toLong())
            n = minOf(n + perFrame, total)
        }
    }
    return if (reduced) total else n
}

/*
 * The beat between two lines. Text types quickly; it is the hold between lines that makes it
 * read like a machine working.
 */
const val LINE_PAUSE = 7

// What a person types (a command, a title) goes 3 characters every 2 frames, as steps of one frame
fun typeSteps(chars: Int) = (2 * chars + 2) / 3

fun typedIn(steps: Int) = steps * 3 / 2

private suspend fun PointerInputScope.swallowTouches() {
    awaitPointerEventScope {
        while (true) awaitPointerEvent(PointerEventPass.Initial).changes.forEach { it.consume() }
    }
}

/*
 * The LCD redraw: a new screen paints over the old one in [frames] bands, top to bottom, one
 * per 40ms frame; going [back] paints from the bottom up. The old screen stays alive underneath
 * until the last band, so nothing in it restarts. Reduced motion cuts. Screens use 4 bands, the
 * bottom bar 2.
 */
@Composable
fun <T> Redraw(
    target: T,
    back: Boolean,
    modifier: Modifier = Modifier,
    frames: Int = 4,
    contentAlignment: Alignment = Alignment.TopStart,
    content: @Composable (T) -> Unit,
) {
    val spec = stepSpec<Float>(frames)
    val ground = LocalLcd.current.ground
    AnimatedContent(
        target,
        // No size animation: a shorter state simply cuts in
        transitionSpec = { (EnterTransition.None togetherWith ExitTransition.KeepUntilTransitionsFinished) using null },
        contentAlignment = contentAlignment,
        label = "redraw",
    ) { screen ->
        val painted by transition.animateFloat({ spec }, label = "bands") { if (it == EnterExitState.PreEnter) 0f else 1f }
        val fromBottom = remember { back }
        // The screen going away stays drawn under the bands, but no touch reaches it
        val leaving = transition.targetState == EnterExitState.PostExit
        Box(
            modifier.then(if (leaving) Modifier.pointerInput(Unit) { swallowTouches() } else Modifier).drawWithContent {
                val h = size.height * painted
                clipRect(top = if (fromBottom) size.height - h else 0f, bottom = if (fromBottom) size.height else h) {
                    drawRect(ground)
                    this@drawWithContent.drawContent()
                }
            },
        ) { content(screen) }
    }
}

// .blk: on for half of every second; off keeps its place
@Composable
fun Cursor(modifier: Modifier = Modifier) {
    val ink = LocalLcd.current.ink
    Box(modifier.size(10.dp, 16.dp).then(if (cursorOn()) Modifier.background(ink) else Modifier))
}

/*
 * .key: the only object with depth. Pressing pushes it into its shadow for as long as it is
 * held (one frame, no easing). A disabled key loses its shadow and its border turns dashed, and
 * cannot take focus.
 */
@Composable
fun Key(
    label: String,
    modifier: Modifier = Modifier,
    primary: Boolean = false,
    enabled: Boolean = true,
    // VirtualKey, unless DESIGN.md's haptics table names another effect for what the key does
    haptic: Haptics.() -> Unit = { keyPress() },
    onClick: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val haptics = LocalHaptics.current
    val c = LocalLcd.current
    val filled = primary && enabled
    val depth = Lcd.keyDepth
    Box(
        modifier
            .offset { if (pressed && enabled) IntOffset(depth.roundToPx(), depth.roundToPx()) else IntOffset.Zero }
            .drawBehind {
                if (enabled && !pressed) drawRect(c.ink, topLeft = Offset(depth.toPx(), depth.toPx()), size = size)
            }
            .lcdClickable(interaction, outline = true, role = Role.Button, enabled = enabled) {
                haptics.haptic()
                onClick()
            }
            .background(if (filled) c.ink else c.ground)
            .then(if (enabled) Modifier.border(Lcd.pixel, c.ink) else Modifier.dashedRect(c.ink, -Lcd.pixel))
            .heightIn(min = Lcd.touch)
            .padding(horizontal = 14.dp),
        contentAlignment = Alignment.Center,
    ) {
        Inverted(filled) { Text(label, LcdType.labelStrong) }
    }
}

// "< Back" in the top bar: the same as the system Back, so a screen's BackHandler decides
@Composable
fun BackKey(modifier: Modifier = Modifier) {
    val dispatcher = LocalOnBackPressedDispatcherOwner.current?.onBackPressedDispatcher
    Key("< Back", modifier) { dispatcher?.onBackPressed() }
}

// .top: 44dp, even when empty, so titles line up from screen to screen
@Composable
fun Top(content: @Composable RowScope.() -> Unit = {}) = Row(
    Modifier.fillMaxWidth().heightIn(min = Lcd.touch),
    horizontalArrangement = Arrangement.SpaceBetween,
    verticalAlignment = Alignment.CenterVertically,
    content = content,
)

// A title types on, at typing speed, each time its screen is drawn
@Composable
fun Title(text: String) {
    val n = typedIn(rememberTyped(typeSteps(text.length), 1))
    Text(text, LcdType.title, cursor = n < text.length, typed = n, cursorSpace = true)
}

// .tag: a short inverted word beside data
@Composable
fun Tag(text: String) = Inverted {
    Text(text, LcdType.data, Modifier.background(LocalLcd.current.ground).padding(horizontal = 6.dp))
}

// .warn: a consequence, in the inverted voice
@Composable
fun Warn(text: String) = Inverted {
    Text(text, LcdType.data, Modifier.fillMaxWidth().background(LocalLcd.current.ground).padding(horizontal = 10.dp, vertical = 8.dp))
}

enum class Led { OFF, ON, BUSY }

/*
 * .led: lit when the thing works, hollow when there is nothing, and while it works it blinks
 * twice in 1.2s, as lcd.css's led keyframes: off at 30-40% and 55-60%.
 */
@Composable
fun LedDot(led: Led) {
    val hidden = led == Led.BUSY && loop(1200) { it in 360 until 480 || it in 660 until 720 }
    val ink = LocalLcd.current.ink
    val dot = Modifier.size(8.dp)
    when {
        hidden -> Box(dot)
        led == Led.OFF -> Box(dot.border(Lcd.pixel, ink))
        else -> Box(dot.background(ink))
    }
}

// The phone's codename, as adb shell's prompt shows it; no root needed to read it
val host = listOf(Build.DEVICE, Build.PRODUCT).firstOrNull { !it.isNullOrBlank() && it != "unknown" } ?: "localhost"

// One Enter's worth of a shell: the prompt, what is typed after it, and what that printed
class ShellLine(val prompt: String, val typed: String = "", val out: List<String> = emptyList())

/*
 * A shell session plays on the 40ms frame clock, one step a frame:
 * - a prompt appears at once, the cursor holds on it for a beat, then the command types at
 *   typing speed;
 * - Enter: the output types, 4 characters a frame with a beat between its lines, and the next
 *   prompt comes the moment the output ends;
 * - a session ends on a prompt with nothing typed, whose beat is the last step.
 */
private fun outSteps(line: String) = (line.length + 3) / 4

private fun ShellLine.steps() = LINE_PAUSE + typeSteps(typed.length) + out.sumOf(::outSteps) + LINE_PAUSE * (out.size - 1).coerceAtLeast(0)

fun List<ShellLine>.steps() = sumOf { it.steps() }

// The step at which line [i]'s command has typed: Enter, when its real work starts
fun List<ShellLine>.enterAt(i: Int) = take(i).steps() + LINE_PAUSE + typeSteps(this[i].typed.length)

// The terminal lines a session shows after [step] steps
fun List<ShellLine>.shownAt(step: Int): List<String> {
    val shown = mutableListOf<String>()
    var left = step
    for (line in this) {
        shown += line.prompt + line.typed.take(typedIn(left - LINE_PAUSE).coerceIn(0, line.typed.length))
        left -= LINE_PAUSE + typeSteps(line.typed.length)
        if (left < 0) return shown
        line.out.forEachIndexed { k, out ->
            if (k > 0) {
                left -= LINE_PAUSE
                if (left < 0) return shown
            }
            // A line shows once its first characters have typed, never as an empty bar
            if (left <= 0) return shown
            shown += out.take(4 * left)
            left -= outSteps(out)
            if (left < 0) return shown
        }
    }
    return shown
}

/*
 * A shell window: a light strip (the LED, what runs, and the phone's codename) over the
 * inverted terminal, inside one 2dp box. Root and Backend both use it.
 */
@Composable
fun Shell(label: String, led: Led, lines: List<String>, modifier: Modifier = Modifier) {
    val ink = LocalLcd.current.ink
    Column(modifier.fillMaxWidth().border(Lcd.pixel, ink).padding(Lcd.pixel)) {
        Row(
            Modifier.fillMaxWidth().height(32.dp).padding(horizontal = 10.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                LedDot(led)
                Text(label, LcdType.labelStrong)
            }
            Text(host, LcdType.data)
        }
        Box(Modifier.fillMaxWidth().height(Lcd.pixel).background(ink))
        Terminal(lines, Modifier.weight(1f))
    }
}

// .kv: a label and its value. Facts nobody taps (.kv.fact) are 32dp, so long lists like Check fit
@Composable
fun Kv(label: String, modifier: Modifier = Modifier, fact: Boolean = false, value: @Composable RowScope.() -> Unit) = Row(
    modifier.fillMaxWidth().heightIn(min = if (fact) 32.dp else Lcd.touch),
    horizontalArrangement = Arrangement.spacedBy(Lcd.tight),
    verticalAlignment = Alignment.CenterVertically,
) {
    Text(label, LcdType.label)
    Row(Modifier.weight(1f), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically, content = value)
}

// .grp: a bold heading over a group of facts
@Composable
fun Group(label: String) = Text(label, LcdType.labelStrong, Modifier.padding(top = 4.dp))

// .note: what a missing fact means, under its row
@Composable
fun Note(text: String) = Text(text, LcdType.data, Modifier.padding(bottom = 8.dp))

// The installer's "[+] ", "[-] " and "[!] "
private val GLYPH = Regex("^\\[.] ")

/*
 * .term: live output on a whole inverted panel, its newest line backlit with the cursor. It
 * fills from the top down like a terminal and, once full, keeps the newest line in view. A line
 * that starts with a glyph such as "[+]" puts it in the 28dp column, so wrapped text lines up
 * after it; any other line (a prompt, stderr) uses the whole width. Only the lines on screen
 * are composed, so a long log costs what a short one does.
 */
// [live] false is a document on the panel (a licence): no backlit line, no cursor
@Composable
fun Terminal(lines: List<String>, modifier: Modifier = Modifier, live: Boolean = true) = Inverted {
    val list = rememberLazyListState()
    LaunchedEffect(lines.size, lines.lastOrNull()) { if (live && lines.isNotEmpty()) list.scrollToItem(lines.lastIndex) }
    LazyColumn(
        modifier.fillMaxWidth().background(LocalLcd.current.ground).padding(10.dp),
        state = list,
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        itemsIndexed(lines) { i, line ->
            val glyph = GLYPH.find(line)?.value
            val newest = live && i == lines.lastIndex
            Inverted(newest) {
                Row(
                    Modifier.fillMaxWidth().background(LocalLcd.current.ground).padding(horizontal = 4.dp, vertical = 3.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    if (glyph != null) Text(glyph.trim(), LcdType.data, Modifier.width(28.dp))
                    Text(line.removePrefix(glyph.orEmpty()), LcdType.data, cursor = newest)
                }
            }
        }
    }
}
