package com.usbarsenal.app.ui

import org.junit.Assert.assertEquals
import org.junit.Test

// .st: 8s a pass, holding the first and last 20%, crossing the middle in 32 steps, then back
class MarqueeTest {
    @Test
    fun holdsThenStepsThenHoldsThenReturns() {
        assertEquals(0f, marqueeAt(0))
        assertEquals(0f, marqueeAt(1_600))
        assertEquals(0.5f, marqueeAt(4_000))
        assertEquals(1f, marqueeAt(6_400))
        assertEquals(1f, marqueeAt(8_000))
        // On the way back it passes the same steps
        assertEquals(0.5f, marqueeAt(12_000))
        assertEquals(0f, marqueeAt(15_999))
        // Whole steps only: never between two of the 32 positions
        assertEquals(0f, (marqueeAt(3_000) * 32) % 1f)
    }
}
