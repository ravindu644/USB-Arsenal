package com.usbarsenal.app.lcd

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
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.ScrollableState
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
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
import androidx.compose.foundation.interaction.PressInteraction

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

/*
 * A part that only scrolls (a terminal) still takes keys: focus reaches it, which
 * scrolls a short screen to it, Up and Down then step [state] 80dp at a time, and at either end
 * they move focus on as usual. Focused, it wears the dashed cursor in [cursor]. Off while
 * [enabled] is false, so a part with nothing to scroll is no extra stop.
 */
fun Modifier.keyScroll(state: ScrollableState, cursor: Color, enabled: Boolean = true): Modifier = composed {
    val scope = rememberCoroutineScope()
    val step = with(LocalDensity.current) { 80.dp.toPx() }
    var focused by remember { mutableStateOf(false) }
    val glide = stepSpec<Float>(2)
    onFocusChanged { focused = it.isFocused }
        .onPreviewKeyEvent { e ->
            val down = e.key == Key.DirectionDown
            if (e.type != KeyEventType.KeyDown || !(down || e.key == Key.DirectionUp)) return@onPreviewKeyEvent false
            if (if (down) !state.canScrollForward else !state.canScrollBackward) return@onPreviewKeyEvent false
            scope.launch { state.animateScrollBy(if (down) step else -step, glide) }
            true
        }
        .focusTick()
        .focusable(enabled)
        .then(if (focused) Modifier.focusOutline(cursor) else Modifier)
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
