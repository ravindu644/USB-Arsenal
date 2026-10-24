package com.usbarsenal.app.lcd

import android.app.Activity
import android.content.Context
import androidx.core.content.edit
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalFocusManager
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
import androidx.core.graphics.drawable.toDrawable
import androidx.core.view.WindowCompat
import com.usbarsenal.app.R
import kotlin.properties.ReadWriteProperty
import kotlin.reflect.KProperty

// Every value in this file comes from DESIGN.md. A value that is not there does not exist.
object Lcd {
    val pixel = 2.dp
    val keyDepth = 4.dp
    val lines = 5.dp
    val tight = 12.dp
    val section = 16.dp
    val gutter = 20.dp
    val topInset = 22.dp
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

/*
 * The themes, DESIGN.md's Colors table: one pair each, the ground and the ink, and nothing
 * between. Everything else (inversion, dither, the scanline grid, keys) derives from the pair,
 * so a theme is only these two values. [lit] is a backlight with dark pixels; a negative theme
 * is dark glass with lit pixels, and the system bars' icons follow it.
 */
enum class Theme(val label: String, val ground: Color, val ink: Color) {
    Amber("Amber", Color(0xFFFFA62E), Color(0xFF1C1005)),
    Orange("Orange", Color(0xFFFF8200), Color(0xFF000000)),
    Pea("Pea", Color(0xFF9BBC0F), Color(0xFF0F380F)),
    Pocket("Pocket", Color(0xFFC4CFA1), Color(0xFF2A2B26)),
    Nokia("Nokia", Color(0xFFC7F0D8), Color(0xFF43523D)),
    Paper("Paper", Color(0xFFE9E6DC), Color(0xFF1A1A1A)),
    Night("Night", Color(0xFF1C1005), Color(0xFFFFA62E)),
    Phosphor("Phosphor", Color(0xFF0B1A0E), Color(0xFF3DF07A)),
    Matrix("Matrix", Color(0xFF000000), Color(0xFF00FF41)),
    Vfd("Vfd", Color(0xFF0A1412), Color(0xFF5EFFD2)),
    Plasma("Plasma", Color(0xFF1A0300), Color(0xFFFF6A2A)),
    ;

    val colors get() = LcdColors(ground, ink)
    val lit get() = colors.lit
}

// The two colours as the content below sees them. Inside an inverted element they swap
@Immutable
data class LcdColors(val ground: Color, val ink: Color) {
    fun inverted() = LcdColors(ground = ink, ink = ground)

    // A lit theme has a light glass and dark pixels; a negative one the reverse, so its ink glows
    val lit get() = ground.luminance() > ink.luminance()
}

val LocalLcd = staticCompositionLocalOf { Theme.Amber.colors }

/*
 * [on] swaps the two colours for [content]; off leaves them. The composition is the same either
 * way, so an element that inverts when focused keeps its focus while it does.
 */
@Composable
fun Inverted(on: Boolean = true, content: @Composable () -> Unit) {
    val colors = LocalLcd.current
    CompositionLocalProvider(LocalLcd provides if (on) colors.inverted() else colors, content = content)
}

// The in-app motion and haptics switches and the theme, kept across launches
class Switches(context: Context) {
    private val prefs = context.getSharedPreferences("switches", Context.MODE_PRIVATE)
    var motion by pref("motion")
    var haptics by pref("haptics")

    private val themeState = mutableStateOf(Theme.entries.firstOrNull { it.name == prefs.getString("theme", null) } ?: Theme.Amber)
    var theme: Theme
        get() = themeState.value
        set(value) {
            themeState.value = value
            prefs.edit { putString("theme", value.name) }
        }

    // Compose state, so a screen redraws when it changes, written through to the prefs
    private fun pref(key: String) = object : ReadWriteProperty<Any?, Boolean> {
        private val state = mutableStateOf(prefs.getBoolean(key, true))
        override fun getValue(thisRef: Any?, property: KProperty<*>) = state.value
        override fun setValue(thisRef: Any?, property: KProperty<*>, value: Boolean) {
            state.value = value
            prefs.edit { putBoolean(key, value) }
        }
    }
}

val LocalSwitches = staticCompositionLocalOf<Switches> { error("LcdTheme not set") }

@Composable
fun LcdTheme(switches: Switches, content: @Composable () -> Unit) {
    val view = LocalView.current
    val feedback = LocalHapticFeedback.current
    val haptics = remember(view, feedback, switches) { Haptics(view, feedback, switches) }
    val theme = switches.theme
    val focusManager = LocalFocusManager.current
    // The window behind Compose shows wherever Compose does not paint (a resize, the keyboard),
    // so it is the ground too, and the bars' icons are dark on a lit theme, light on a negative one
    if (!view.isInEditMode) SideEffect {
        val window = (view.context as Activity).window
        window.setBackgroundDrawable(theme.ground.toArgb().toDrawable())
        WindowCompat.getInsetsController(window, view).run {
            isAppearanceLightStatusBars = theme.lit
            isAppearanceLightNavigationBars = theme.lit
        }
    }
    CompositionLocalProvider(
        LocalSwitches provides switches,
        LocalLcd provides theme.colors,
        LocalReducedMotion provides rememberReducedMotion(switches),
        LocalHaptics provides haptics,
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .background(theme.ground)
                .scanlines(theme.ink)
                // A tap nothing else took, on the bare ground, lets go of a field: its outline and
                // the keyboard go. A scroll consumes its drag, so it never counts as one
                .pointerInput(Unit) { detectTapGestures { focusManager.clearFocus() } },
        ) { content() }
    }
}
