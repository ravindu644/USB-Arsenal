package com.usbarsenal.app.lcd

import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.delay
import kotlin.math.floor

// Interaction motion counts in 40ms frames, like an LCD redrawing
const val FRAME_MS = 40

// Values jump between whole positions instead of easing through them
fun StepEasing(frames: Int) = Easing { t -> floor(t * frames) / frames }

// Compose scales finite animations by the system setting only, so the in-app switch is applied here: a cut
@Composable
fun <T> stepSpec(frames: Int): FiniteAnimationSpec<T> =
    if (LocalReducedMotion.current) snap() else tween(frames * FRAME_MS, easing = StepEasing(frames))

val LocalReducedMotion = compositionLocalOf { false }

// "Remove animations" sets the animator duration scale to 0; the in-app switch overrides it the other way only
@Composable
fun rememberReducedMotion(switches: Switches): Boolean {
    val resolver = LocalContext.current.contentResolver
    fun systemOff() = Settings.Global.getFloat(resolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
    var systemReduced by remember { mutableStateOf(systemOff()) }
    DisposableEffect(resolver) {
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                systemReduced = systemOff()
            }
        }
        resolver.registerContentObserver(
            Settings.Global.getUriFor(Settings.Global.ANIMATOR_DURATION_SCALE), false, observer,
        )
        onDispose { resolver.unregisterContentObserver(observer) }
    }
    return systemReduced || !switches.motion
}

/*
 * An ambient loop of [periodMs]: [frame] maps the time into the loop to what is drawn, and the
 * caller recomposes only when that changes. It steps once per 40ms frame on the frame clock,
 * which stops while the app is not visible. A zero duration scale only ends finite animations,
 * so every infinite loop goes through here and holds frame(0), its resting frame, while motion
 * is reduced.
 */
@Composable
fun <T> loop(periodMs: Int, frame: (Int) -> T): T {
    val reduced = LocalReducedMotion.current
    val rest = frame(0)
    return produceState(rest, reduced, periodMs) {
        value = rest
        if (reduced) return@produceState
        val start = withFrameMillis { it }
        while (true) {
            delay(FRAME_MS.toLong())
            value = frame(((withFrameMillis { it } - start) % periodMs).toInt())
        }
    }.value
}

// The working cursor: on for the first half of each second, so its resting frame is visible
@Composable
fun cursorOn(): Boolean = loop(1000) { it < 500 }
