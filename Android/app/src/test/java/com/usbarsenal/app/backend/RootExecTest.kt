package com.usbarsenal.app.backend

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

// The host's sh stands in for su; the process handling is the same
class RootExecTest {
    private val root = RootExec { ProcessBuilder("sh", "-c", it).start() }

    private fun since(start: Long) = (System.nanoTime() - start) / 1_000_000

    private fun running(marker: String) = ProcessBuilder("pgrep", "-f", marker).start().waitFor() == 0

    @Test
    fun hungChangeBlocksNeitherReadsNorTheLaneForLong() = runBlocking<Unit> {
        val start = System.nanoTime()
        val hung = async(Dispatchers.Default) { root.change("sleep 30", timeoutMs = 2_000) }
        delay(300)

        val read = root.read("echo read")
        assertEquals(listOf("read"), read.out)
        assertTrue("the read waited for the hung change: ${since(start)}ms", since(start) < 1_500)

        // Queued behind the hung change, it runs once the timeout kills that and frees the lane
        val next = root.change("echo next")
        assertTrue(hung.await().timedOut)
        assertEquals(listOf("next"), next.out)
        assertTrue("the lane stayed stuck: ${since(start)}ms", since(start) < 5_000)
    }

    @Test
    fun timeoutKillsTheWholeTree() = runBlocking<Unit> {
        val marker = "ua-tree-${System.nanoTime()}"
        val r = root.change("sh -c 'sleep 30; echo $marker' & wait", timeoutMs = 500)
        assertTrue(r.timedOut)
        assertTrue("a grandchild survived the timeout", !running(marker))
    }

    @Test
    fun cancelKillsTheWholeTree() = runBlocking<Unit> {
        val marker = "ua-cancel-${System.nanoTime()}"
        runCatching { withTimeout(500) { root.read("sh -c 'sleep 30; echo $marker' & wait") } }
        assertTrue("a grandchild survived the cancel", !running(marker))
    }

    @Test
    fun outputErrorsAndExitCode() = runBlocking<Unit> {
        val r = root.read("echo a; echo b >&2; echo c; exit 3")
        assertEquals(RootResult(3, listOf("a", "c"), listOf("b")), r)
    }

    @Test
    fun aForkedDaemonHoldingThePipesDoesNotHangTheCall() = runBlocking<Unit> {
        val marker = "ua-daemon-${System.nanoTime()}"
        val start = System.nanoTime()
        val r = root.change("(sleep 5; echo $marker) & echo started")
        assertEquals(RootResult(0, listOf("started"), emptyList()), r)
        assertTrue("waited for the daemon: ${since(start)}ms", since(start) < 3_000)
        ProcessBuilder("pkill", "-f", marker).start().waitFor()
    }

    @Test
    fun streamTakesInputAndStopsOnClose() = runBlocking<Unit> {
        val marker = "ua-stream-${System.nanoTime()}"
        // A loop keeps sh itself alive, so pgrep can find the marker in its command line
        val s = root.stream("sh -c 'while read l; do echo \$l; done # $marker'")!!
        s.input.write("hello\n".toByteArray())
        s.input.flush()
        assertEquals("hello", withTimeout(2_000) { s.lines.first() })
        assertTrue(running(marker))
        s.close()
        assertTrue("the stream outlived close()", !running(marker))
    }

    @Test
    fun noSuIsAFailureNotACrash() = runBlocking<Unit> {
        val r = RootExec { ProcessBuilder("/nonexistent/su", "-c", it).start() }.read("id")
        assertEquals(NO_ROOT, r.code)
    }

    @Test
    fun streamWithoutSuIsNull() = runBlocking<Unit> {
        assertNull(RootExec { ProcessBuilder("/nonexistent/su", "-c", it).start() }.stream("cat"))
    }

    @Test
    fun streamMergesStderr() = runBlocking<Unit> {
        val s = root.stream("sh -c 'echo to-stderr >&2; sleep 5'")!!
        assertEquals("to-stderr", withTimeout(2_000) { s.lines.first() })
        s.close()
    }

    @Test
    fun quoting() = runBlocking<Unit> {
        assertEquals(listOf("it's \$HOME"), root.read("echo ${shellQuote("it's \$HOME")}").out)
    }
}
