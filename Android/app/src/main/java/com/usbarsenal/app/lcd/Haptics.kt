package com.usbarsenal.app.lcd

import android.annotation.SuppressLint
import android.content.Context
import android.os.Build
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.VibrationEffect.Composition.PRIMITIVE_CLICK
import android.os.VibrationEffect.Composition.PRIMITIVE_THUD
import android.os.VibrationEffect.Composition.PRIMITIVE_TICK
import android.os.Vibrator
import android.os.VibratorManager
import android.provider.Settings
import android.view.HapticFeedbackConstants
import android.view.View
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.findViewTreeLifecycleOwner

// Device tiers for the rich effects, from DESIGN.md's Haptics section
enum class HapticTier { T1, T2, T3, T4 }

/*
 * One function per event in DESIGN.md's event map. Simple events go through Compose, which
 * falls back by itself on older devices; the bind sequence goes through the tier.
 */
class Haptics(private val view: View, private val feedback: HapticFeedback, private val switches: Switches) {
    private val vibrator: Vibrator? = view.context.vibrator()?.takeIf { it.hasVibrator() }

    // THUD is API 31; where it is missing the plug-in lands as a full-scale CLICK. Only read on API 30 and later
    @SuppressLint("InlinedApi")
    private val thud = if (vibrator != null && Build.VERSION.SDK_INT >= 31 &&
        vibrator.areAllPrimitivesSupported(PRIMITIVE_THUD)
    ) PRIMITIVE_THUD else PRIMITIVE_CLICK

    // Chosen once. One unsupported primitive silences a whole composition, so T2 checks every one it plays
    val tier: HapticTier = when {
        vibrator == null -> HapticTier.T4
        Build.VERSION.SDK_INT >= 36 && vibrator.areEnvelopeEffectsSupported() &&
            vibrator.areAllPrimitivesSupported(PRIMITIVE_TICK) -> HapticTier.T1
        Build.VERSION.SDK_INT >= 30 && vibrator.areAllPrimitivesSupported(PRIMITIVE_TICK, thud) -> HapticTier.T2
        Build.VERSION.SDK_INT >= 29 -> HapticTier.T3
        else -> HapticTier.T4
    }

    // The in-app switch, the system touch feedback setting, and never from the background
    private fun allowed(): Boolean =
        switches.haptics && systemTouchFeedback() &&
            view.findViewTreeLifecycleOwner()?.lifecycle?.currentState?.isAtLeast(Lifecycle.State.STARTED) == true

    // From API 33 the system applies the setting itself, to performHapticFeedback and to USAGE_TOUCH vibrations
    @Suppress("DEPRECATION")
    private fun systemTouchFeedback(): Boolean = Build.VERSION.SDK_INT >= 33 ||
        Settings.System.getInt(view.context.contentResolver, Settings.System.HAPTIC_FEEDBACK_ENABLED, 1) != 0

    private fun simple(type: HapticFeedbackType) {
        if (allowed()) feedback.performHapticFeedback(type)
    }

    fun keyPress() = simple(HapticFeedbackType.VirtualKey)
    fun toggle(on: Boolean) = simple(if (on) HapticFeedbackType.ToggleOn else HapticFeedbackType.ToggleOff)

    // Segment change, LUN switch, and every D-pad or keyboard focus move
    fun tick() = simple(HapticFeedbackType.SegmentTick)

    // The caller spaces these at least 8px of trackpad travel apart
    fun dragTick() = simple(HapticFeedbackType.SegmentFrequentTick)

    // Stop all and eject
    fun gestureEnd() = simple(HapticFeedbackType.GestureEnd)
    fun reject() = simple(HapticFeedbackType.Reject)
    fun confirm() = simple(HapticFeedbackType.Confirm)

    // Pin climbs into the port in three steps (0, 1, 2), each tick stronger. Called on the frame Pin moves
    fun bindClimb(step: Int) {
        if (!allowed()) return
        val v = vibrator
        when {
            // Scales mean nothing without amplitude control, so those devices get the full tick
            v != null && tier <= HapticTier.T2 && Build.VERSION.SDK_INT >= 30 -> {
                val scale = if (v.hasAmplitudeControl()) 0.4f + 0.2f * step.coerceIn(0, 2) else 1f
                v.play(VibrationEffect.startComposition().addPrimitive(PRIMITIVE_TICK, scale).compose())
            }
            v != null && tier == HapticTier.T3 && Build.VERSION.SDK_INT >= 29 ->
                v.play(VibrationEffect.createPredefined(VibrationEffect.EFFECT_TICK))
            else -> feedback.performHapticFeedback(HapticFeedbackType.SegmentTick)
        }
    }

    // The thud on the frame Pin plugs in
    fun bindPlug() {
        if (!allowed()) return
        val v = vibrator
        when {
            // An envelope must start and end at zero intensity, with control points at least 20ms apart
            v != null && tier == HapticTier.T1 && Build.VERSION.SDK_INT >= 36 -> v.play(
                VibrationEffect.BasicEnvelopeBuilder()
                    .setInitialSharpness(0.2f)
                    .addControlPoint(1f, 0.2f, 20)
                    .addControlPoint(0f, 0.2f, 60)
                    .build(),
            )
            v != null && tier == HapticTier.T2 && Build.VERSION.SDK_INT >= 30 ->
                v.play(VibrationEffect.startComposition().addPrimitive(thud, 1f).compose())
            v != null && tier == HapticTier.T3 && Build.VERSION.SDK_INT >= 29 ->
                v.play(VibrationEffect.createPredefined(VibrationEffect.EFFECT_HEAVY_CLICK))
            else -> view.performHapticFeedback(
                if (Build.VERSION.SDK_INT >= 30) HapticFeedbackConstants.CONFIRM else HapticFeedbackConstants.CONTEXT_CLICK,
            )
        }
    }
}

// Touch usage on API 33 and later, so the system's touch feedback intensity applies too
private fun Vibrator.play(effect: VibrationEffect) =
    if (Build.VERSION.SDK_INT >= 33) vibrate(effect, VibrationAttributes.createForUsage(VibrationAttributes.USAGE_TOUCH))
    else vibrate(effect)

private fun Context.vibrator(): Vibrator? =
    if (Build.VERSION.SDK_INT >= 31) getSystemService(VibratorManager::class.java)?.defaultVibrator
    else @Suppress("DEPRECATION") getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator

val LocalHaptics = staticCompositionLocalOf<Haptics> { error("LcdTheme not set") }
