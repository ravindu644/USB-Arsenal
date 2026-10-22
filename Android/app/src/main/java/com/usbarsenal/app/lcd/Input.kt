package com.usbarsenal.app.lcd

import androidx.activity.OnBackPressedDispatcher
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.semantics.Role

/*
 * Focusable and pressable from touch, Enter and D-pad center, all handled by clickable.
 * BUTTON_A needs nothing: the key character map falls back from it to DPAD_CENTER, so a
 * gamepad press also shows the pressed look. Keys, DIP switches and inputs pass outline = true
 * and get the dashed cursor; rows and segments read focus from [interaction] and invert
 * themselves. No default indication: every pressed or focused look is drawn by our components.
 *
 * ponytail: composed{} per element; a Modifier.Node if long lists ever show it in a trace
 */
fun Modifier.lcdClickable(
    interaction: MutableInteractionSource,
    outline: Boolean,
    role: Role? = null,
    enabled: Boolean = true,
    onClick: () -> Unit,
): Modifier = composed {
    val haptics = LocalHaptics.current
    val inputMode = LocalInputModeManager.current
    val focused by interaction.collectIsFocusedAsState()
    val ink = LocalLcd.current.ink
    this
        // A move made with keys ticks; focus that follows a touch does not
        .onFocusChanged { if (it.isFocused && inputMode.inputMode == InputMode.Keyboard) haptics.tick() }
        .then(if (outline && focused) Modifier.focusOutline(ink) else Modifier)
        .clickable(interaction, indication = null, enabled = enabled, role = role, onClick = onClick)
}

// BUTTON_L1 and BUTTON_R1 step a segmented control or the LUN strip while focus is inside it
fun Modifier.shoulderStep(step: (Int) -> Unit) = onKeyEvent {
    if (it.type != KeyEventType.KeyDown) return@onKeyEvent false
    when (it.key) {
        Key.ButtonL1 -> step(-1)
        Key.ButtonR1 -> step(1)
        else -> return@onKeyEvent false
    }
    true
}

/*
 * Back, Escape and BUTTON_B (which falls back to BACK) mean back. While anything is focused
 * Compose takes Back and Escape as a focus exit, and Escape's own fallback to BACK never
 * happens, so the root catches both first and hands them to the back dispatcher.
 */
fun Modifier.backKeys(dispatcher: OnBackPressedDispatcher?) = onPreviewKeyEvent {
    if (dispatcher == null || (it.key != Key.Escape && it.key != Key.Back)) return@onPreviewKeyEvent false
    if (it.type == KeyEventType.KeyUp) dispatcher.onBackPressed()
    true
}
