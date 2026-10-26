package com.usbarsenal.app.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

// The pad's gestures do what a laptop touchpad does: every case here is one libinput rule
class TouchpadTest {
    private val log = mutableListOf<String>()
    private val out = object : Pad.Out {
        override fun move(dx: Int, dy: Int) { log += "move $dx $dy" }
        override fun press(bit: Int, down: Boolean) { log += "button $bit ${if (down) "down" else "up"}" }
        override fun scroll(wheel: Int, pan: Int) { log += "scroll $wheel $pan" }
        override fun zoom(notches: Int) { log += "zoom $notches" }
        override fun zoomHold(on: Boolean) { log += "ctrl $on" }
        override fun drag(bit: Int) { log += "drag $bit" }
        override fun click(bit: Int) { log += "click $bit" }
        override fun tick() {}
    }

    // Slop 8px, a notch every 16px, a zoom notch every 24px, a tick every 8px, one px per dp
    private fun pad() = Pad(Pad.Config(8f, 16f, 24f, 8f, 1f), out)
    private fun f(id: Long, x: Float, y: Float) = Finger(id, x, y)
    private fun buttons() = log.filter { it.startsWith("button") }

    @Test
    fun tapClicksAndLetsGoOnTheTimer() {
        val p = pad()
        p.event(0, listOf(f(1, 50f, 50f)))
        val due = p.event(100, emptyList())
        assertEquals(listOf("button 1 down", "click 1"), log)
        assertEquals(400L, due)
        assertNull(p.timeout(400))
        assertEquals(listOf("button 1 down", "click 1", "button 1 up"), log)
    }

    @Test
    fun twoAndThreeFingersTapRightAndMiddle() {
        val p = pad()
        p.event(0, listOf(f(1, 50f, 50f)))
        p.event(5, listOf(f(1, 50f, 50f), f(2, 90f, 50f)))
        p.event(100, emptyList())
        p.timeout(400)
        p.event(500, listOf(f(3, 50f, 50f), f(4, 90f, 50f), f(5, 130f, 50f)))
        p.event(600, emptyList())
        p.timeout(900)
        assertEquals(listOf("button 2 down", "click 2", "button 2 up", "button 4 down", "click 4", "button 4 up"), log)
    }

    @Test
    fun aSecondTapHasItsOwnFingerCount() {
        val p = pad()
        p.event(0, listOf(f(1, 50f, 50f)))
        p.event(100, emptyList())
        p.event(200, listOf(f(2, 50f, 50f)))
        p.event(205, listOf(f(2, 50f, 50f), f(3, 90f, 50f)))
        p.event(300, emptyList())
        p.timeout(600)
        assertEquals(listOf("button 1 down", "button 1 up", "button 2 down", "button 2 up"), buttons())
    }

    @Test
    fun heldStillOrMovedIsNoTap() {
        val p = pad()
        p.event(0, listOf(f(1, 50f, 50f)))
        p.timeout(180)
        p.event(500, emptyList())
        p.event(600, listOf(f(1, 50f, 50f)))
        // Past the slop the tap's timer is off, and a move needs none
        assertNull(p.event(650, listOf(f(1, 80f, 50f))))
        p.event(700, emptyList())
        assertTrue(buttons().isEmpty())
        assertTrue(log.any { it.startsWith("move") })
    }

    @Test
    fun tapThenTouchDragsWithTheButtonUntilLifted() {
        val p = pad()
        p.event(0, listOf(f(1, 50f, 50f)))
        p.event(100, emptyList())
        // Back within the drag timeout: the release is called off, the move drags
        assertEquals(380L, p.event(200, listOf(f(2, 50f, 50f))))
        p.event(250, listOf(f(2, 70f, 50f)))
        assertEquals(listOf("button 1 down"), buttons())
        assertTrue(log.contains("drag 1"))
        assertTrue(log.any { it.startsWith("move") })
        // Lifted: let go at once, no drag lock
        assertNull(p.event(300, emptyList()))
        assertEquals(listOf("button 1 down", "button 1 up"), buttons())
        assertEquals("drag 0", log.last())
        // A finger back now is a fresh touch
        p.event(350, listOf(f(3, 50f, 50f)))
        p.event(400, listOf(f(3, 90f, 50f)))
        p.event(450, emptyList())
        assertEquals(listOf("button 1 down", "button 1 up"), buttons())
    }

    @Test
    fun doubleTapIsTwoClicks() {
        val p = pad()
        p.event(0, listOf(f(1, 50f, 50f)))
        p.event(100, emptyList())
        p.event(200, listOf(f(2, 50f, 50f)))
        p.event(300, emptyList())
        p.timeout(600)
        assertEquals(listOf("button 1 down", "button 1 up", "button 1 down", "button 1 up"), buttons())
    }

    @Test
    fun nothingLiftedKeepsAHeldButton() {
        val p = pad()
        p.event(0, listOf(f(1, 50f, 50f)))
        p.event(100, emptyList())
        // A hover, or a touch another view took, while the tap's button is held
        assertEquals(400L, p.event(200, emptyList()))
        assertEquals(listOf("button 1 down"), buttons())
        p.timeout(400)
        assertEquals(listOf("button 1 down", "button 1 up"), buttons())
    }

    @Test
    fun aCancelledTouchNeverTapsAndLetsGo() {
        val p = pad()
        p.event(0, listOf(f(1, 50f, 50f)))
        p.cancel()
        p.event(50, emptyList())
        assertTrue(buttons().isEmpty())
        // Mid drag with the finger down: the button is let go, the finger left is nobody's
        p.event(100, listOf(f(2, 50f, 50f)))
        p.event(200, emptyList())
        p.event(300, listOf(f(3, 50f, 50f)))
        p.event(350, listOf(f(3, 80f, 50f)))
        assertEquals(listOf("button 1 down"), buttons())
        p.cancel()
        assertEquals(listOf("button 1 down", "button 1 up"), buttons())
        assertEquals("drag 0", log.last())
        p.event(360, emptyList())
        assertEquals(listOf("button 1 down", "button 1 up"), buttons())
    }

    @Test
    fun twoFingersScrollEitherWayAndSideways() {
        for (natural in listOf(false, true)) {
            log.clear()
            val p = pad()
            p.natural = natural
            p.event(0, listOf(f(1, 50f, 100f), f(2, 90f, 100f)))
            // Up 10px passes the slop, then 32px more is two notches: the wheel turns away unless natural
            p.event(50, listOf(f(1, 50f, 90f), f(2, 90f, 90f)))
            p.event(100, listOf(f(1, 50f, 58f), f(2, 90f, 58f)))
            assertEquals(natural, log.contains("scroll -2 0"))
            assertEquals(!natural, log.contains("scroll 2 0"))
            p.event(150, emptyList())
            log.clear()
            p.event(200, listOf(f(3, 50f, 100f), f(4, 90f, 100f)))
            p.event(250, listOf(f(3, 60f, 100f), f(4, 100f, 100f)))
            p.event(300, listOf(f(3, 92f, 100f), f(4, 132f, 100f)))
            assertEquals(if (natural) "scroll 0 -2" else "scroll 0 2", log.single { it.startsWith("scroll") })
            assertTrue(buttons().isEmpty())
        }
    }

    @Test
    fun pinchIsCtrlAndNotches() {
        val p = pad()
        p.event(0, listOf(f(1, 100f, 100f), f(2, 120f, 100f)))
        // The spread grows 20px: a pinch, not a scroll
        p.event(50, listOf(f(1, 90f, 100f), f(2, 130f, 100f)))
        p.event(100, listOf(f(1, 60f, 100f), f(2, 160f, 100f)))
        p.event(150, emptyList())
        assertEquals(listOf("ctrl true", "zoom 2", "ctrl false"), log)
    }

    @Test
    fun aFastFingerGoesFurther() {
        val p = pad()
        p.event(0, listOf(f(1, 0f, 0f)))
        p.timeout(180)
        p.event(500, listOf(f(1, 10f, 0f)))
        for (i in 1..10) p.event(500 + i * 50L, listOf(f(1, 10f + i * 10f, 0f)))
        val slow = log.sumOf { it.removePrefix("move ").split(" ")[0].toInt() }
        log.clear()
        p.event(2000, emptyList())
        p.event(2100, listOf(f(1, 0f, 0f)))
        p.timeout(2280)
        p.event(2600, listOf(f(1, 10f, 0f)))
        for (i in 1..10) p.event(2600 + i * 5L, listOf(f(1, 10f + i * 10f, 0f)))
        val fast = log.sumOf { it.removePrefix("move ").split(" ")[0].toInt() }
        assertTrue("$slow < $fast", slow < fast)
    }
}
