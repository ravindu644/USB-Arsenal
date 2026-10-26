package com.usbarsenal.app.ui

import kotlin.math.abs
import kotlin.math.hypot

/*
 * The trackpad's gestures, done the way libinput does a laptop touchpad so the pad feels like
 * the one under every Linux laptop's keyboard. The tap state machine (touch, tapped, drag or
 * double tap, dragging, drag lock) and its timeouts follow libinput's evdev-mt-touchpad-tap.c and
 * its tapping docs: a tap is 180 ms and under the touch slop; the button it put down stays down
 * 300 ms so a finger coming back drags with it, and lifting that finger ends the drag (no drag
 * lock, libinput's default too). One, two and three fingers tap left,
 * right and middle; four is nothing. Two fingers moving together scroll (held to an axis when
 * the motion is near one), spreading or closing pinches, which the host sees as Ctrl and the
 * wheel. Three fingers moving do nothing: a swipe means something else on every host. The
 * acceleration ramp is our own. No Compose in here, so a JVM test drives it with bare fingers.
 */
class Finger(val id: Long, val x: Float, val y: Float)

class Pad(private val cfg: Config, private val out: Out) {
    class Config(
        // px: under this travel a touch is still a tap and a scroll has not begun (the platform's touch slop)
        val slop: Float,
        // px of common travel per wheel notch, and of spread change per zoom notch
        val scrollStep: Float,
        val zoomStep: Float,
        // px of pointer travel per haptic tick (DESIGN.md: 8dp)
        val tick: Float,
        // px per dp: speed is measured in dp per ms so every phone accelerates alike
        val dpPx: Float,
        val tapMs: Long = 180,
        val dragMs: Long = 300,
    )

    interface Out {
        // Host pixels, accelerated, the remainder kept in here
        fun move(dx: Int, dy: Int)
        fun press(bit: Int, down: Boolean)
        // Notches, already turned for Natural scroll
        fun scroll(wheel: Int, pan: Int)
        // Notches of Ctrl + wheel while [zoomHold] is on
        fun zoom(notches: Int)
        fun zoomHold(on: Boolean)
        // The button a drag holds (a tap's 300 ms hold is not one), 0 when it lets go
        fun drag(bit: Int)
        // A tap's click, with its button; and a notch or 8dp of travel, to be felt
        fun click(bit: Int)
        fun tick()
    }

    // The Speed slider in quarters (4 is 1.0x, half a host pixel per pixel at walking pace) and the Natural scroll DIP
    var speed = 4
    var natural = true

    private enum class S { IDLE, TOUCH, MOVE, SCROLL, PINCH, TAPPED, DRAG_OR_TAP, DRAGGING, DEAD }

    private var state = S.IDLE
    private var t0 = 0L
    private var lastT = 0L
    private var tapOk = false
    private var maxFingers = 0
    private var start = emptyMap<Long, Finger>()
    private var prev = emptyMap<Long, Finger>()
    private var spread0 = 0f
    // Scroll: 0 free, 1 vertical only, 2 horizontal only
    private var axis = 0
    private var accX = 0f
    private var accY = 0f
    private var held = 0
    private var rx = 0f
    private var ry = 0f
    private var vel = 0f
    private var travel = 0f
    // The touch after a tap went past the slop: a drag, not a second tap
    private var moved = false
    private var deadline: Long? = null

    // Every pointer event: the fingers down now, in ms. Returns when [timeout] is owed, or null
    fun event(t: Long, fingers: List<Finger>): Long? {
        val n = fingers.size
        // Nothing lifted when nothing was down (a hover, a touch another view took): a held
        // button stays held, as a tap's does until its timer
        if (n == 0 && prev.isEmpty()) return deadline
        when {
            n == 0 -> allUp(t)
            n > prev.size -> down(t, fingers)
            n < prev.size -> lifted(fingers)
            else -> motion(t, fingers)
        }
        prev = fingers.associateBy { it.id }
        lastT = t
        return deadline
    }

    fun timeout(t: Long): Long? {
        deadline = null
        when (state) {
            // Held still past a tap: a plain touch now, which lifts to nothing
            S.TOUCH -> tapOk = false
            // The tap's release, once no finger came back to drag
            S.TAPPED -> {
                out.press(held, false)
                idle()
            }
            S.DRAG_OR_TAP -> drag()
            else -> {}
        }
        return deadline
    }

    // The button the pad holds is let go now: L M R pressed it, or the system took the touch.
    // Fingers still down are nobody's until they lift, so none of them taps
    fun cancel() {
        drop()
        if (prev.isEmpty()) idle() else {
            state = S.DEAD
            held = 0
            deadline = null
        }
    }

    // The pad was drawn anew (full screen in or out): nothing it knew is still true
    fun reset() {
        drop()
        prev = emptyMap()
        idle()
    }

    private fun drop() {
        if (held != 0) out.press(held, false)
        if (state == S.DRAGGING) out.drag(0)
        endGesture()
        tapOk = false
    }

    private fun down(t: Long, fingers: List<Finger>) {
        val n = fingers.size
        when (state) {
            S.IDLE -> {
                state = S.TOUCH
                t0 = t
                tapOk = true
                maxFingers = n
                deadline = t + cfg.tapMs
            }
            S.TOUCH, S.DRAG_OR_TAP -> maxFingers = maxOf(maxFingers, n)
            // A gesture never changes its finger count: this one ends, the new count starts afresh
            S.MOVE, S.SCROLL, S.PINCH -> {
                endGesture()
                state = S.TOUCH
                tapOk = false
            }
            // The tap's release is called off: this finger may drag with the button, or tap again
            S.TAPPED -> {
                state = S.DRAG_OR_TAP
                t0 = t
                maxFingers = n
                moved = false
                deadline = t + cfg.tapMs
            }
            else -> {}
        }
        if (state == S.TOUCH && n > 3) state = S.DEAD
        begin(fingers)
    }

    private fun lifted(fingers: List<Finger>) {
        when (state) {
            S.MOVE, S.SCROLL, S.PINCH -> {
                endGesture()
                state = S.TOUCH
                tapOk = false
            }
            else -> {}
        }
        begin(fingers)
    }

    private fun allUp(t: Long) {
        when (state) {
            S.TOUCH -> if (tapOk && maxFingers <= 3 && t - t0 <= cfg.tapMs) tap(t) else idle()
            S.MOVE, S.SCROLL, S.PINCH -> {
                endGesture()
                idle()
            }
            // A second tap: the click is let go and done again, a double click
            S.DRAG_OR_TAP -> if (!moved && t - t0 <= cfg.tapMs) {
                out.press(held, false)
                tap(t)
            } else release()
            // The finger that dragged is up: the button is, at once
            S.DRAGGING -> release()
            else -> idle()
        }
    }

    private fun motion(t: Long, fingers: List<Finger>) {
        val f = fingers.minBy { it.id }
        when (state) {
            S.TOUCH -> when (fingers.size) {
                1 -> if (from(f) > cfg.slop) {
                    gesture(S.MOVE)
                    // The travel that proved it a move is not lost: the pointer starts from the landing
                    prev = start
                    lastT = t0
                    pointer(t, fingers)
                }
                2 -> {
                    val ds = abs(spread(fingers) - spread0)
                    val c = centroid(fingers)
                    val c0 = centroid(start.values)
                    val dx = c.x - c0.x
                    val dy = c.y - c0.y
                    if (ds > 2 * cfg.slop) {
                        gesture(S.PINCH)
                        spread0 = spread(fingers)
                        accX = 0f
                        out.zoomHold(true)
                    } else if (hypot(dx, dy) > cfg.slop) {
                        gesture(S.SCROLL)
                        // Near straight is held straight: within 30 degrees of an axis, as libinput does
                        axis = if (abs(dy) >= abs(dx) * 1.73f) 1 else if (abs(dx) >= abs(dy) * 1.73f) 2 else 0
                        accX = 0f
                        accY = 0f
                    }
                }
                else -> if (fingers.any { from(it) > cfg.slop }) tapOk = false
            }
            S.MOVE -> pointer(t, fingers)
            S.SCROLL -> scroll(fingers)
            S.PINCH -> pinch(fingers)
            S.DRAG_OR_TAP -> if (from(f) > cfg.slop) {
                drag()
                prev = start
                lastT = t0
                pointer(t, fingers)
            }
            S.DRAGGING -> pointer(t, fingers)
            else -> {}
        }
    }

    // A touch became a gesture: no longer a tap, and the tap's timer is off
    private fun gesture(s: S) {
        state = s
        tapOk = false
        deadline = null
    }

    private fun tap(t: Long) {
        held = when (maxFingers) {
            1 -> MOUSE_LEFT
            2 -> MOUSE_RIGHT
            else -> MOUSE_MIDDLE
        }
        out.press(held, true)
        out.click(held)
        state = S.TAPPED
        deadline = t + cfg.dragMs
    }

    private fun drag() {
        state = S.DRAGGING
        moved = true
        deadline = null
        out.drag(held)
    }

    private fun release() {
        out.press(held, false)
        out.drag(0)
        idle()
    }

    private fun idle() {
        state = S.IDLE
        deadline = null
        held = 0
        moved = false
    }

    private fun endGesture() {
        if (state == S.PINCH) out.zoomHold(false)
    }

    // A new finger set: where each one is now is where its gesture begins
    private fun begin(fingers: List<Finger>) {
        start = fingers.associateBy { it.id }
        spread0 = spread(fingers)
        vel = 0f
    }

    private fun from(f: Finger): Float {
        val s = start[f.id] ?: return 0f
        return hypot(f.x - s.x, f.y - s.y)
    }

    private fun centroid(fs: Collection<Finger>) = Finger(0, fs.map { it.x }.average().toFloat(), fs.map { it.y }.average().toFloat())

    private fun spread(fs: Collection<Finger>): Float {
        if (fs.size < 2) return 0f
        val (a, b) = fs.sortedBy { it.id }
        return hypot(a.x - b.x, a.y - b.y)
    }

    /*
     * The primary finger's travel since the last event, scaled by Speed and by how fast it goes:
     * a slow finger places the pointer, a fast one crosses the screen. Half of Speed at rest,
     * three times it flat out.
     * ponytail: one straight ramp on a two-sample average, 0.5 + v/2 clamped to 0.5..3 with v in
     * dp/ms; libinput's tracker window and its curve if a phone shows it jump
     */
    private fun pointer(t: Long, fingers: List<Finger>) {
        val f = fingers.minBy { it.id }
        val p = prev[f.id] ?: return
        val dx = f.x - p.x
        val dy = f.y - p.y
        if (dx == 0f && dy == 0f) return
        val d = hypot(dx, dy)
        val v = d / cfg.dpPx / (t - lastT).coerceIn(1, 50)
        vel = if (vel == 0f) v else (vel + v) / 2
        val gain = speed / 8f * (0.5f + vel / 2).coerceIn(0.5f, 3f)
        rx += dx * gain
        ry += dy * gain
        val ix = rx.toInt()
        val iy = ry.toInt()
        rx -= ix
        ry -= iy
        if (ix != 0 || iy != 0) out.move(ix, iy)
        // Felt every 8dp of travel, so a drag is felt without buzzing
        travel += d
        if (travel >= cfg.tick) {
            travel = 0f
            out.tick()
        }
    }

    private fun scroll(fingers: List<Finger>) {
        val before = fingers.mapNotNull { prev[it.id] }
        if (before.size != fingers.size) return
        val c = centroid(fingers)
        val c0 = centroid(before)
        if (axis != 2) accY += c.y - c0.y
        if (axis != 1) accX += c.x - c0.x
        var wheel = 0
        var pan = 0
        // Fingers moving up turn the wheel away, as on a laptop; natural scroll moves the content
        // with the fingers instead, as a touchscreen does. Sideways alike
        while (abs(accY) >= cfg.scrollStep) {
            val up = accY < 0
            accY += if (up) cfg.scrollStep else -cfg.scrollStep
            wheel += if (up != natural) 1 else -1
        }
        while (abs(accX) >= cfg.scrollStep) {
            val right = accX > 0
            accX -= if (right) cfg.scrollStep else -cfg.scrollStep
            pan += if (right != natural) 1 else -1
        }
        if (wheel != 0 || pan != 0) {
            out.scroll(wheel, pan)
            out.tick()
        }
    }

    private fun pinch(fingers: List<Finger>) {
        val s = spread(fingers)
        accX += s - spread0
        spread0 = s
        var notches = 0
        while (abs(accX) >= cfg.zoomStep) {
            val wider = accX > 0
            accX -= if (wider) cfg.zoomStep else -cfg.zoomStep
            notches += if (wider) 1 else -1
        }
        if (notches != 0) {
            out.zoom(notches)
            out.tick()
        }
    }
}
