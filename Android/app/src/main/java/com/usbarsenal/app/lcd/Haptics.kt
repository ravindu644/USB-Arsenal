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

    /*
     * The plug-in thud for this device's tier in DESIGN.md's Haptics section, chosen once. One
     * unsupported primitive silences a whole composition, so T2 checks every one it plays. Null is
     * T4, the view's own feedback.
     */
    private val plug: VibrationEffect? = when {
        vibrator == null -> null
        // T1. An envelope must start and end at zero intensity, with control points at least 20ms apart
        Build.VERSION.SDK_INT >= 36 && vibrator.areEnvelopeEffectsSupported() &&
            vibrator.areAllPrimitivesSupported(PRIMITIVE_TICK) -> VibrationEffect.BasicEnvelopeBuilder()
            .setInitialSharpness(0.2f)
            .addControlPoint(1f, 0.2f, 20)
            .addControlPoint(0f, 0.2f, 60)
            .build()
        Build.VERSION.SDK_INT >= 30 && vibrator.areAllPrimitivesSupported(thud) ->
            VibrationEffect.startComposition().addPrimitive(thud, 1f).compose()
        Build.VERSION.SDK_INT >= 29 -> VibrationEffect.createPredefined(VibrationEffect.EFFECT_HEAVY_CLICK)
        else -> null
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

    // Trackpad travel, spaced 8px apart by the caller, and a shell landing characters
    fun frequentTick() = simple(HapticFeedbackType.SegmentFrequentTick)

    // Stop all and eject
    fun gestureEnd() = simple(HapticFeedbackType.GestureEnd)
    fun reject() = simple(HapticFeedbackType.Reject)
    fun confirm() = simple(HapticFeedbackType.Confirm)

    // The thud on the frame Pin plugs in
    fun bindPlug() {
        if (!allowed()) return
        if (vibrator != null && plug != null) vibrator.play(plug)
        else view.performHapticFeedback(
            if (Build.VERSION.SDK_INT >= 30) HapticFeedbackConstants.CONFIRM else HapticFeedbackConstants.CONTEXT_CLICK,
        )
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
