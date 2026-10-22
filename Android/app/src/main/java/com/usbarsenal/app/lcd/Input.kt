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
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.interaction.Interaction
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.isPrimaryPressed
import androidx.compose.ui.input.pointer.changedToUpIgnoreConsumed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import android.os.SystemClock
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

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
    val focused by interaction.collectIsFocusedAsState()
    val ink = LocalLcd.current.ink
    this
        .focusTick()
        .then(if (outline && focused) Modifier.focusOutline(ink) else Modifier)
        .then(if (enabled) Modifier.pressOnDown(interaction) else Modifier)
        .clickable(interaction, indication = null, enabled = enabled, role = role, onClick = onClick)
}

/*
 * The pressed look the moment a finger lands. Inside a scrolling screen clickable reports a
 * press only once the scroll has stopped waiting for touch slop (AbstractClickableNode's
 * delayPressInteraction, with no flag to turn it off in foundation 1.12), so a quick tap pressed
 * nothing until it lifted. This reports its own press on the down, watching without consuming,
 * and cancels it when the scroll takes the pointer; the click itself stays clickable's.
 */
private fun Modifier.pressOnDown(interaction: MutableInteractionSource) = pointerInput(interaction) {
    awaitEachGesture {
        // A down the scroll has taken never clicks, so it never looks pressed either
        val down = awaitFirstDown(pass = PointerEventPass.Initial)
        if (down.type == PointerType.Mouse && !currentEvent.buttons.isPrimaryPressed) return@awaitEachGesture
        val press = PressInteraction.Press(down.position)
        interaction.tryEmit(press)
        var end: Interaction = PressInteraction.Cancel(press)
        while (true) {
            val change = awaitPointerEvent(PointerEventPass.Final).changes.firstOrNull { it.id == down.id } ?: break
            if (change.changedToUpIgnoreConsumed()) {
                end = PressInteraction.Release(press)
                break
            }
            // A scroll consumes the moves it takes; the press was not one
            if (change.isConsumed && change.positionChanged()) break
        }
        interaction.tryEmit(end)
    }
}

// A move made with keys ticks (SegmentTick); focus that follows a touch does not
fun Modifier.focusTick(): Modifier = composed {
    val haptics = LocalHaptics.current
    val inputMode = LocalInputModeManager.current
    onFocusChanged { if (it.isFocused && inputMode.inputMode == InputMode.Keyboard) haptics.tick() }
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

/*
 * Pressed, held for two frames at least. In a scrolling screen Compose holds a touch's press
 * back until it knows the touch is not a scroll, so a quick tap reports its press and release
 * together as it lifts, and the pressed look would never draw. Held, every tap shows it.
 */
@Composable
fun InteractionSource.collectIsPressedHeld(): State<Boolean> {
    val held = remember { mutableStateOf(false) }
    LaunchedEffect(this) {
        val presses = mutableListOf<PressInteraction.Press>()
        val echoes = mutableListOf<PressInteraction.Press>()
        var since = 0L
        var endedAt = 0L
        var letGo: Job? = null
        interactions.collect { i ->
            when (i) {
                is PressInteraction.Press -> {
                    val now = SystemClock.uptimeMillis()
                    // clickable's own press for a touch pressOnDown already showed: in a scrolling
                    // screen it is sent from a coroutine as the finger lifts, after ours has let go,
                    // and holding it again would keep the key down 80ms past the lift
                    if (!held.value && now - endedAt < FRAME_MS) {
                        echoes += i
                        return@collect
                    }
                    presses += i
                    letGo?.cancel()
                    // Timed from the touch, not from a later press within it
                    if (!held.value) since = now
                    held.value = true
                }
                is PressInteraction.Release -> if (!echoes.remove(i.press)) presses -= i.press
                is PressInteraction.Cancel -> if (!echoes.remove(i.press)) presses -= i.press
            }
            if (presses.isEmpty() && held.value) {
                letGo?.cancel()
                letGo = launch {
                    delay(2 * FRAME_MS - (SystemClock.uptimeMillis() - since))
                    held.value = false
                    endedAt = SystemClock.uptimeMillis()
                }
            }
        }
    }
    return held
}
