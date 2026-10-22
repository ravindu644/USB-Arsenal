package com.usbarsenal.app.ui

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

// The reports match hid.c's descriptors, and typed text maps to the keys a US keyboard uses
class ReportsTest {
    @Test
    fun mouse() {
        assertArrayEquals(byteArrayOf(1, 10, -2, 0), mouseReport(MOUSE_LEFT, 10, -2))
        // A signed byte each: a long drag is cut to what one report can carry
        assertArrayEquals(byteArrayOf(0, 127, -127, 1), mouseReport(0, 500, -500, 1))
    }

    @Test
    fun keyboard() {
        assertArrayEquals(byteArrayOf(MOD_CTRL.toByte(), 0, 0x06, 0, 0, 0, 0, 0), keyReport(MOD_CTRL, 0x06))
        assertArrayEquals(ByteArray(8), keyReport())
    }

    @Test
    fun typing() {
        assertEquals(0 to 0x04, keyFor('a'))
        assertEquals(MOD_SHIFT to 0x1D, keyFor('Z'))
        assertEquals(0 to 0x27, keyFor('0'))
        assertEquals(0 to 0x1E, keyFor('1'))
        assertEquals(MOD_SHIFT to 0x1F, keyFor('@'))
        assertEquals(MOD_SHIFT to 0x27, keyFor(')'))
        assertEquals(0 to 0x38, keyFor('/'))
        assertEquals(MOD_SHIFT to 0x38, keyFor('?'))
        assertEquals(MOD_SHIFT to 0x34, keyFor('"'))
        assertEquals(0 to 0x28, keyFor('\n'))
        assertNull(keyFor('é'))
    }

    @Test
    fun namedKeys() {
        assertEquals(0x3A, NAMED_KEYS["F1"])
        assertEquals(0x45, NAMED_KEYS["F12"])
        assertEquals(0x52, NAMED_KEYS["UP"])
        // Every key the Keys tab shows has a code or is a modifier
        (TABLE_NAMES + (1..12).map { "F$it" } + listOf("UP", "DOWN", "LEFT", "RIGHT")).forEach {
            assert(it in NAMED_KEYS || it in MODIFIERS) { it }
        }
    }

    // ENTER twice is one wide key; the gaps beside the up arrow stay two, so it sits in the middle
    @Test
    fun keyRows() {
        assertEquals(listOf("ENTER" to 2f, "PGUP" to 1f, "PGDN" to 1f), runs(listOf("ENTER", "ENTER", "PGUP", "PGDN")))
        assertEquals(listOf("" to 1f, "UP" to 1f, "" to 1f), runs(listOf("", "UP", "")))
    }

    @Test
    fun interval() {
        assertEquals("125 us", periodText(1))
        assertEquals("1 ms", periodText(4))
        assertEquals("512 ms", periodText(13))
        assertEquals("4.096 s", periodText(16))
        assertEquals(listOf("hid", "start"), setupArgs(Fn.HID))
    }
}
