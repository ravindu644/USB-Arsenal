package com.usbarsenal.app.backend

// Quacky's scripts, beside the images and profiles folders. The editor keeps its buffer here as a
// .qk file, because quacky only runs a file path, never stdin.
const val SCRIPTS = "$FOLDER/Scripts"

// The folder, made if missing, by its canonical path (as imagesDir does): the form root opens
suspend fun scriptsDir(): String = Root.read("mkdir -p $SCRIPTS && readlink -f $SCRIPTS").out.firstOrNull() ?: SCRIPTS

// The folder's .qk files; stat prints "<bytes> <path>", as listImages reads them. ImageFile is just
// a path and a size, so it serves here too
suspend fun listScripts(dir: String): List<ImageFile> = parseImages(
    Root.read("for f in ${shellQuote(dir)}/*; do [ -f \"\$f\" ] && stat -c '%s %n' \"\$f\"; done; true").out,
).filter { it.path.endsWith(".qk") }

// A script's exact text, to load into the editor; base64 so a trailing newline and CR bytes
// survive (Root.read splits on newlines and drops trailing blanks). Null when unreadable
suspend fun readScript(path: String): String? {
    val r = Root.read("base64 ${shellQuote(path)}").takeIf { it.ok } ?: return null
    return runCatching {
        String(android.util.Base64.decode(r.out.joinToString(""), android.util.Base64.DEFAULT), Charsets.UTF_8)
    }.getOrNull()
}

/*
 * The quacky invocation: [cmd] is what really runs (the absolute binary and script path, the node
 * shell-quoted), [echo] is what the terminal shows (bare `quacky` and filename, the convention
 * usb-arsenal follows). A null [node] is a dry run: no --hid, so quacky writes its transcript to
 * stdout instead of typing. -d is always passed; [mode] 1 is --fast, 2 is --ultra.
 */
data class QuackyCmd(val cmd: String, val echo: String)

fun quackyCommand(node: String?, pace: Int, mode: Int, path: String): QuackyCmd {
    val flag = when (mode) {
        1 -> " --fast"
        2 -> " --ultra"
        else -> ""
    }
    val name = path.substringAfterLast('/')
    return QuackyCmd(
        cmd = "$QUACKY${node?.let { " --hid ${shellQuote(it)}" } ?: ""} -d $pace$flag ${shellQuote(path)}",
        echo = "quacky${node?.let { " --hid $it" } ?: ""} -d $pace$flag $name",
    )
}

// quacky's run, streamed into the terminal (merged stdout+stderr), off the command lane as
// hidWrite is; close() the stream to stop and kill the shell. Its `chunks` grow the terminal as
// quacky types, not a line at a time
suspend fun runQuacky(cmd: String): RootStream? = Root.stream(cmd)

/*
 * Fold a raw output [chunk] into the terminal's lines: the first part joins the line being typed,
 * each newline starts a new one, so `n`, `o`, `t`... land on the same line until quacky prints a
 * newline. Carriage returns are dropped. Pure, so the typing effect is testable off-device.
 */
/*
 * The editor's line numbers: one per real line, then a blank row for each extra row the field
 * soft-wraps that line onto, so the numbers stay beside their lines. [rowOf] is the field's layout
 * (offset to visual row), [rows] its row count; null before the first layout, one row a line.
 */
fun gutter(text: String, rowOf: ((Int) -> Int)?, rows: Int): String = buildString {
    var start = 0
    var n = 1
    while (true) {
        val nl = text.indexOf('\n', start)
        val span = if (rowOf == null) 1 else (if (nl < 0) rows else rowOf(nl + 1)) - rowOf(start)
        if (n > 1) append('\n')
        append(n)
        repeat(span - 1) { append('\n') }
        if (nl < 0) break
        start = nl + 1
        n++
    }
}

fun growTerminal(lines: List<String>, chunk: String): List<String> {
    val clean = chunk.replace("\r", "")
    if (clean.isEmpty()) return lines
    val parts = clean.split('\n')
    val base = lines.ifEmpty { listOf("") }
    return base.dropLast(1) + (base.last() + parts.first()) + parts.drop(1)
}
