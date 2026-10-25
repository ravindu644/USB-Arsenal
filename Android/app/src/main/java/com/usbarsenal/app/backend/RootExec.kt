package com.usbarsenal.app.backend

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.TimeUnit

const val TIMED_OUT = -1

// How long a gadget change may take before its shell is killed: a replug waits up to 10s on Android
const val CHANGE_TIMEOUT_MS = 30_000L

// What a shell returns for a command it cannot find
const val NO_ROOT = 127

data class RootResult(val code: Int, val out: List<String>, val err: List<String>) {
    val ok get() = code == 0
    val timedOut get() = code == TIMED_OUT

    // What a failed change shows: the backend's last word, or its exit code when it said nothing
    val why get() = err.lastOrNull() ?: "[-] Failed (exit $code)"
}

/*
 * Root commands, so that one stuck command can never freeze the app:
 * - every call runs in its own su process, which ends with it;
 * - every call has a timeout, and a timeout or a cancel kills the shell and everything under
 *   it, not only the su client. What a command daemonized has left the tree and lives on;
 * - commands that change the gadget queue on one lane, in order, so two never race. A timeout
 *   kills its command and frees the lane;
 * - reads never wait on the lane;
 * - a stream (a log tail, a HID writer) owns its shell until it is closed.
 *
 * The pipes are polled, never read blocking: a daemon a command forks can hold them open for
 * hours, and a blocked read cannot be interrupted.
 *
 * [launch] starts `sh -c SCRIPT` as root. Each script prints its shell's pid first: with Magisk
 * the root shell is not our child, so its pid is the only handle on it.
 */
class RootExec(private val launch: (String) -> Process) {
    private val lane = Mutex()

    suspend fun read(cmd: String, timeoutMs: Long = 15_000) = run(cmd, timeoutMs)

    suspend fun change(cmd: String, timeoutMs: Long = CHANGE_TIMEOUT_MS) = lane.withLock { run(cmd, timeoutMs) }

    private suspend fun run(cmd: String, timeoutMs: Long): RootResult = withContext(Dispatchers.IO) {
        // No su at all is how KernelSU says no: it hides su from apps it has not granted root
        val p = start("echo $$; $cmd") ?: return@withContext RootResult(NO_ROOT, emptyList(), listOf("no su"))
        p.outputStream.close()
        val out = ByteArrayOutputStream()
        val err = ByteArrayOutputStream()
        val buf = ByteArray(8192)
        fun pump() {
            p.inputStream.pumpInto(out, buf)
            p.errorStream.pumpInto(err, buf)
        }
        // The shell's pid, the first line. Under Magisk the shell is not our child, so a kill
        // before that line came would free the lane while the command runs on: wait for it
        suspend fun pid(): Int {
            repeat(100) {
                pump()
                out.lines().firstOrNull()?.toIntOrNull()?.let { return it }
                if (!p.isAlive) return 0
                delay(10)
            }
            return 0
        }
        val code = try {
            withTimeoutOrNull(timeoutMs) {
                while (p.isAlive) {
                    pump()
                    delay(10)
                }
                p.exitValue()
            } ?: TIMED_OUT.also { kill(p, pid()) }
        } catch (e: CancellationException) {
            withContext(NonCancellable) { kill(p, pid()) }
            throw e
        }
        // Once the shell has exited, what is still in the pipes is the rest of its output
        pump()
        RootResult(code, out.lines().drop(1), err.lines())
    }

    /*
     * A long-lived command: stdin is [RootStream.input], stdout and stderr are [RootStream.lines].
     * Null without root, or when the shell does not start within [timeoutMs]. Its lines are
     * polled like every pipe here, so cancelling a collector always gets through, and a cancel
     * while it starts still kills what it started.
     */
    suspend fun stream(cmd: String, timeoutMs: Long = 15_000): RootStream? {
        val p = withContext(Dispatchers.IO) { start("echo $$; exec $cmd 2>&1") } ?: return null
        // Buffered, so polling a byte at a time costs no system call per byte
        val raw = BufferedInputStream(p.inputStream)
        val shellPid = try {
            withTimeoutOrNull(timeoutMs) { raw.pollLine(p) }?.toIntOrNull()
        } catch (e: CancellationException) {
            // Its pid is likely a moment away; with Magisk it is the only handle on the shell
            withContext(NonCancellable + Dispatchers.IO) {
                kill(p, withTimeoutOrNull(1_000) { raw.pollLine(p) }?.toIntOrNull() ?: 0)
            }
            throw e
        }
        if (shellPid == null) {
            withContext(Dispatchers.IO) { kill(p, 0) }
            return null
        }
        val lines = flow { while (true) emit(raw.pollLine(p) ?: break) }.flowOn(Dispatchers.IO)
        // Bytes as they arrive, holding any trailing incomplete UTF-8 sequence until the rest comes
        val chunks = flow {
            val buf = ByteArray(8192)
            var pending = ByteArray(0)
            while (true) {
                val n = raw.readAvailable(p, buf) ?: break
                val all = if (pending.isEmpty()) buf.copyOf(n) else pending + buf.copyOf(n)
                val end = utf8SafeEnd(all)
                if (end > 0) emit(String(all, 0, end, Charsets.UTF_8))
                pending = if (end < all.size) all.copyOfRange(end, all.size) else ByteArray(0)
            }
            if (pending.isNotEmpty()) emit(String(pending, Charsets.UTF_8))
        }.flowOn(Dispatchers.IO)
        return RootStream(p.outputStream, lines, chunks) {
            withContext(Dispatchers.IO) {
                kill(p, shellPid)
                runCatching { raw.close() }
            }
        }
    }

    private fun start(script: String): Process? = try {
        launch(script)
    } catch (_: IOException) {
        null
    }

    /*
     * As root: stop each process before touching its children, so nothing in the tree can fork
     * a replacement, then kill the stopped tree. The client goes last.
     * ponytail: under Magisk the su client can outlive its shell by a moment, so a pid freed in
     * that moment could be reused; a pid file descriptor (pidfd) closes the gap if it ever bites
     */
    private fun kill(p: Process, pid: Int) {
        if (!p.isAlive) return
        if (pid > 0) {
            start("k() { kill -STOP $1; for c in $(pgrep -P $1); do k \$c; done; kill -9 $1; }; k $pid")
                ?.waitFor(5, TimeUnit.SECONDS)
        }
        p.destroyForcibly()
    }
}

// Reads only what is already there, so it never blocks. destroyForcibly() closes the pipes after a kill
private fun InputStream.pumpInto(to: ByteArrayOutputStream, buf: ByteArray) {
    runCatching { while (available() > 0) to.write(buf, 0, read(buf).takeIf { it > 0 } ?: break) }
}

// One line, a byte at a time and only once it has arrived; null if the process ends first
private suspend fun InputStream.pollLine(p: Process): String? {
    val line = StringBuilder()
    while (true) {
        if (available() == 0) {
            if (!p.isAlive) return null
            delay(10)
            continue
        }
        when (val c = read()) {
            -1 -> return null
            '\n'.code -> return line.toString()
            else -> line.append(c.toChar())
        }
    }
}

// Whatever bytes have already arrived, into the reused [buf], not waiting for a newline, so a line
// builds up on screen as quacky types it; null once the process ends
private suspend fun InputStream.readAvailable(p: Process, buf: ByteArray): Int? {
    while (available() == 0 && p.isAlive) delay(10)
    // Alive with data, or exited: read() returns it, then drains the pipe's last bytes and hits
    // EOF (-1) without blocking, since an exited quacky has closed the write end. So the final
    // chunk it wrote as it quit is never dropped
    return read(buf).takeIf { it > 0 }
}

// The length of [b] up to its last complete UTF-8 character, so a multi-byte char split across two
// reads is carried to the next chunk instead of decoding as a replacement character
internal fun utf8SafeEnd(b: ByteArray): Int {
    var i = b.size - 1
    var back = 0
    while (i >= 0 && (b[i].toInt() and 0xC0) == 0x80 && back < 3) {
        i--
        back++
    }
    if (i < 0) return b.size
    val lead = b[i].toInt() and 0xFF
    val need = when {
        lead and 0x80 == 0 -> 1
        lead and 0xE0 == 0xC0 -> 2
        lead and 0xF0 == 0xE0 -> 3
        lead and 0xF8 == 0xF0 -> 4
        else -> 1
    }
    return if (b.size - i >= need) b.size else i
}

private fun ByteArrayOutputStream.lines() = toString(Charsets.UTF_8.name()).lines().dropLastWhile(String::isEmpty)

// close() kills the command; it starts a root shell to do it, so it is a suspend call
class RootStream internal constructor(
    val input: OutputStream,
    val lines: Flow<String>,
    // The same output, not split on newlines: for a terminal that grows a line as it is typed.
    // Collect either lines or chunks, never both: they read the one pipe, so each byte goes to one
    val chunks: Flow<String>,
    private val stop: suspend () -> Unit,
) {
    suspend fun close() = stop()
}

// One single-quoted shell word, whatever the text holds
fun shellQuote(s: String) = "'" + s.replace("'", "'\\''") + "'"
