package com.usbarsenal.app.ui

import org.junit.Assert.assertEquals
import org.junit.Test

// The session timeline Root and Backend play: beat, command, Enter, output, next prompt
class ShellTest {
    private val session = listOf(ShellLine("# ", "ls", listOf("a", "bb")), ShellLine("# "))
    private val p = LINE_PAUSE

    @Test
    fun beatThenTypingThenOutputThenPrompt() {
        assertEquals(listOf("# "), session.shownAt(p))
        assertEquals(listOf("# l"), session.shownAt(p + 1))
        // Enter: no empty output line on the frame the command ends
        assertEquals(listOf("# ls"), session.shownAt(p + 2))
        assertEquals(listOf("# ls", "a"), session.shownAt(p + 3))
        // The beat between output lines, with no empty line as it ends
        assertEquals(listOf("# ls", "a"), session.shownAt(2 * p + 3))
        // The last output line and the next prompt arrive together
        assertEquals(listOf("# ls", "a", "bb", "# "), session.shownAt(2 * p + 4))
    }

    // 3 characters every 2 frames: 1, 3, then the rest
    @Test
    fun typingSpeed() {
        val s = listOf(ShellLine("$ ", "abcd"))
        assertEquals(listOf("$ a"), s.shownAt(p + 1))
        assertEquals(listOf("$ abc"), s.shownAt(p + 2))
        assertEquals(listOf("$ abcd"), s.shownAt(p + 3))
        assertEquals(p + 3, s.enterAt(0))
    }

    @Test
    fun stepsAndEnter() {
        assertEquals(p + 2, session.enterAt(0))
        assertEquals(3 * p + 4, session.steps())
    }
}
