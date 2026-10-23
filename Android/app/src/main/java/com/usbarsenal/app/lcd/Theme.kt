package com.usbarsenal.app.lcd

import android.content.Context
import androidx.core.content.edit
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.usbarsenal.app.R

// Every value in this file comes from DESIGN.md's front matter. A value that is not there does not exist.
object Lcd {
    val Backlight = Color(0xFFFFA62E)
    val Ink = Color(0xFF1C1005)
    val Scanline = Color(0x0F1C1005)

    val Shape = RectangleShape

    val pixel = 2.dp
    val keyDepth = 4.dp
    val lines = 5.dp
    val tight = 12.dp
    val section = 16.dp
    val gutter = 20.dp
    val topInset = 22.dp
    val navInset = 48.dp
    val bottomInset = 68.dp
    val touch = 44.dp
    val row = 80.dp
    val lunCell = 40.dp
}

val Jersey10 = FontFamily(Font(R.font.jersey10_regular))
val Silkscreen = FontFamily(
    Font(R.font.silkscreen_regular, FontWeight.Normal),
    Font(R.font.silkscreen_bold, FontWeight.Bold),
)
val Vt323 = FontFamily(Font(R.font.vt323_regular))

// Compose has no text-transform: Silkscreen text is uppercased by the component that draws it, VT323 never is
object LcdType {
    // CSS centres the glyphs in a line box smaller than the font; this is the same, with no trimming
    private val cssLine = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.None)

    val hero = TextStyle(fontFamily = Jersey10, fontSize = 104.sp, lineHeight = 0.8.em, lineHeightStyle = cssLine)
    val title = hero.copy(fontSize = 72.sp)
    val label = TextStyle(fontFamily = Silkscreen, fontSize = 16.sp, lineHeight = 20.sp, lineHeightStyle = cssLine)
    val labelStrong = label.copy(fontWeight = FontWeight.Bold)
    val data = TextStyle(fontFamily = Vt323, fontSize = 22.sp, lineHeight = 20.sp, lineHeightStyle = cssLine)
}

// The two colours as the content below sees them. Inside an inverted element they swap
@Immutable
data class LcdColors(val ground: Color, val ink: Color) {
    fun inverted() = LcdColors(ground = ink, ink = ground)
}

val LocalLcd = staticCompositionLocalOf { LcdColors(Lcd.Backlight, Lcd.Ink) }

/*
 * [on] swaps the two colours for [content]; off leaves them. The composition is the same either
 * way, so an element that inverts when focused keeps its focus while it does.
 */
@Composable
fun Inverted(on: Boolean = true, content: @Composable () -> Unit) {
    val colors = LocalLcd.current
    CompositionLocalProvider(LocalLcd provides if (on) colors.inverted() else colors, content = content)
}

// The in-app motion and haptics switches, kept across launches
class Switches(context: Context) {
    private val prefs = context.getSharedPreferences("switches", Context.MODE_PRIVATE)
    private val motionState = mutableStateOf(prefs.getBoolean("motion", true))
    private val hapticsState = mutableStateOf(prefs.getBoolean("haptics", true))

    var motion: Boolean
        get() = motionState.value
        set(on) {
            motionState.value = on
            prefs.edit { putBoolean("motion", on) }
        }

    var haptics: Boolean
        get() = hapticsState.value
        set(on) {
            hapticsState.value = on
            prefs.edit { putBoolean("haptics", on) }
        }
}

val LocalSwitches = staticCompositionLocalOf<Switches> { error("LcdTheme not set") }

@Composable
fun LcdTheme(switches: Switches, content: @Composable () -> Unit) {
    val view = LocalView.current
    val feedback = LocalHapticFeedback.current
    val haptics = remember(view, feedback, switches) { Haptics(view, feedback, switches) }
    CompositionLocalProvider(
        LocalSwitches provides switches,
        LocalReducedMotion provides rememberReducedMotion(switches),
        LocalHaptics provides haptics,
    ) {
        Box(Modifier.fillMaxSize().background(Lcd.Backlight).scanlines()) { content() }
    }
}
