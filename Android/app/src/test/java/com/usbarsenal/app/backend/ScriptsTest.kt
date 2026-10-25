package com.usbarsenal.app.backend

import org.junit.Assert.assertEquals
import org.junit.Test

// The run command the Quacky screen builds: the real one uses the absolute binary and a quoted
// path; the echo the terminal shows uses the bare `quacky` and filename, as usb-arsenal does.
class ScriptsTest {
    private val path = "/sdcard/USB-Arsenal/Scripts/archinstall.qk"

    @Test
    fun dryRunNormal() {
        // No node is a dry run: no --hid, so quacky prints the transcript instead of typing
        val c = quackyCommand(node = null, pace = 5, mode = 0, path = path)
        assertEquals("$QUACKY -d 5 ${shellQuote(path)}", c.cmd)
        assertEquals("quacky -d 5 archinstall.qk", c.echo)
    }

    @Test
    fun realRunFast() {
        val c = quackyCommand(node = "/dev/hidg1", pace = 0, mode = 1, path = path)
        assertEquals("$QUACKY --hid ${shellQuote("/dev/hidg1")} -d 0 --fast ${shellQuote(path)}", c.cmd)
        assertEquals("quacky --hid /dev/hidg1 -d 0 --fast archinstall.qk", c.echo)
    }

    @Test
    fun ultra() {
        val c = quackyCommand(node = "/dev/hidg1", pace = 5, mode = 2, path = path)
        assertEquals("quacky --hid /dev/hidg1 -d 5 --ultra archinstall.qk", c.echo)
    }

    @Test
    fun growSameLine() {
        // Characters without a newline build up on the current line, as quacky types them
        var t = listOf("quacky demo.qk", "")
        for (c in "notepad") t = growTerminal(t, c.toString())
        assertEquals(listOf("quacky demo.qk", "notepad"), t)
    }

    @Test
    fun growNewlines() {
        assertEquals(listOf("xa", "b", "c"), growTerminal(listOf("x"), "a\nb\nc"))
        assertEquals(listOf("hi"), growTerminal(emptyList(), "hi"))
        // Carriage returns are dropped; an empty chunk leaves the lines unchanged
        assertEquals(listOf("ab"), growTerminal(listOf(""), "a\rb"))
        assertEquals(listOf("x"), growTerminal(listOf("x"), ""))
    }
}
