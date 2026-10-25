package com.usbarsenal.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.LocalTextSelectionColors
import androidx.compose.foundation.text.selection.TextSelectionColors
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.usbarsenal.app.backend.Cache
import com.usbarsenal.app.backend.ImageFile
import com.usbarsenal.app.backend.SCRIPTS
import com.usbarsenal.app.backend.gutter
import com.usbarsenal.app.backend.growTerminal
import com.usbarsenal.app.backend.listScripts
import com.usbarsenal.app.backend.quackyCommand
import com.usbarsenal.app.backend.readScript
import com.usbarsenal.app.backend.runQuacky
import com.usbarsenal.app.backend.scriptsDir
import com.usbarsenal.app.backend.writeText
import com.usbarsenal.app.lcd.Inverted
import com.usbarsenal.app.lcd.Lcd
import com.usbarsenal.app.lcd.LcdType
import com.usbarsenal.app.lcd.LocalHaptics
import com.usbarsenal.app.lcd.LocalLcd
import com.usbarsenal.app.lcd.focusOutline
import com.usbarsenal.app.lcd.focusTick
import com.usbarsenal.app.lcd.lcdClickable
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// The three speeds: no flag, --fast (one key per report, no gap), --ultra (up to six keys a report)
private val SPEEDS = listOf("Normal", "Fast", "Ultra")
private val CODE = KeyboardOptions(capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false, keyboardType = KeyboardType.Ascii)

/*
 * Quacky: write a .qk script and type it over USB HID. EDIT is a line-numbered editor over the
 * scripts in /sdcard/USB-Arsenal/Scripts; RUN streams quacky's real output into a live terminal,
 * at the speed quacky really types. Editing and Dry run work offline; a real Run needs the
 * keyboard node, so its key waits until HID is on. The terminal shows the bare `quacky` command
 * while the absolute binary and script path run underneath.
 */
@Composable
fun QuackyScreen(back: () -> Unit) {
    BackHandler(onBack = back)
    val scope = rememberCoroutineScope()
    val node = Gadget.status?.hid?.firstOrNull { it.function == "keyboard" }?.node?.ifEmpty { null }

    var tab by rememberSaveable { mutableIntStateOf(0) }
    // Seeded from the persisted setup, so the editor opens on the last file with no flash and
    // without copying the whole buffer into the instance-state Bundle (a big paste there risks
    // TransactionTooLargeException). A config change keeps these; process death re-seeds from the
    // last saved content. Speed and pace live in QuackySetup too
    var name by remember { mutableStateOf(QuackySetup.file) }
    var text by remember { mutableStateOf(QuackySetup.text) }
    var dirty by remember { mutableStateOf(false) }
    var picking by rememberSaveable { mutableStateOf(false) }
    // 0 closed, 1 New, 2 Save as
    var naming by rememberSaveable { mutableIntStateOf(0) }
    var options by rememberSaveable { mutableStateOf(false) }

    var dir by remember { mutableStateOf(Cache.scriptsDir ?: SCRIPTS) }
    var files by remember { mutableStateOf(Cache.scripts ?: emptyList()) }
    // A pick that would lose unsaved edits waits for the discard confirm
    var discard by remember { mutableStateOf<ImageFile?>(null) }
    var fault by remember { mutableStateOf<String?>(null) }

    // The terminal, grown as quacky types (see growTerminal); replaced whole so Compose sees a
    // change. Idle it shows a root prompt, as the shell sessions do
    var out by remember { mutableStateOf(listOf("# ")) }
    var running by remember { mutableStateOf(false) }
    var job by remember { mutableStateOf<Job?>(null) }

    // First open: make the folder, list it, and load the newest script so there is a current file.
    // A restore keeps its own name/text, so this only fills a blank screen
    LaunchedEffect(Unit) {
        dir = scriptsDir().also { Cache.scriptsDir = it }
        files = listScripts(dir).also { Cache.scripts = it }
        if (name.isEmpty()) {
            // First ever use: open the last-remembered file if it survives, else the first listed
            (files.firstOrNull { it.name == QuackySetup.file } ?: files.firstOrNull())?.let { f ->
                readScript(f.path)?.let { name = f.name; text = it; dirty = false; QuackySetup.open(f.name, it) }
            }
        } else if (!dirty) {
            // One live read a launch corrects the cached buffer, so a Run never types bytes the
            // editor is not showing (the cache rule); unsaved edits restored after death are kept
            files.firstOrNull { it.name == name }?.let { f ->
                readScript(f.path)?.let { if (it != text) { text = it; QuackySetup.open(name, it) } }
            }
        }
    }

    fun open(f: ImageFile) {
        scope.launch {
            val t = readScript(f.path) ?: run { fault = "[-] Cannot read ${f.name}"; return@launch }
            name = f.name; text = t; dirty = false; fault = null; picking = false
            QuackySetup.open(f.name, t)
        }
    }

    suspend fun write(): Boolean {
        val r = writeText("$dir/$name", text)
        if (!r.ok) {
            fault = r.why
            return false
        }
        dirty = false
        fault = null
        files = listScripts(dir).also { Cache.scripts = it }
        QuackySetup.open(name, text)
        return true
    }

    fun save() {
        if (name.isEmpty()) naming = 2 else scope.launch { write() }
    }

    fun run(dry: Boolean) {
        if (name.isEmpty()) {
            naming = 2
            return
        }
        job?.cancel()
        job = scope.launch {
            running = true
            try {
                if (dirty && !write()) return@launch
                val c = quackyCommand(if (dry) null else node, QuackySetup.effectivePace, QuackySetup.mode, "$dir/$name")
                // The command after a root prompt, then an empty line quacky's output grows into
                // character by character; flushed once a frame so Ultra's thousands a second never
                // flood it
                out = listOf("# ${c.echo}", "")
                val s = runQuacky(c.cmd) ?: run { out = out + "[-] Could not start quacky"; return@launch }
                val buf = StringBuilder()
                val drain = launch {
                    while (true) {
                        withFrameMillis { }
                        if (buf.isNotEmpty()) {
                            out = growTerminal(out, buf.toString())
                            buf.clear()
                        }
                    }
                }
                // The finally kills the shell on Stop, on Back and on a natural end alike, as
                // HidWriter closes its writer
                try {
                    s.chunks.collect { buf.append(it) }
                } finally {
                    drain.cancel()
                    if (buf.isNotEmpty()) out = growTerminal(out, buf.toString())
                    // Back to a fresh prompt, reusing the blank line the last newline left
                    out = (if (out.lastOrNull()?.isEmpty() == true) out.dropLast(1) else out) + "# "
                    withContext(NonCancellable) { s.close() }
                }
            } finally {
                running = false
            }
        }
    }

    Box(Modifier.fillMaxSize()) {
        Screen(footer = {
            fault?.let { Fault(it, onLog = null) }
            if (tab == 0) {
                KeyRow {
                    Key("Save as", Modifier.weight(1f)) { naming = 2 }
                    Key("Save", Modifier.weight(1f).focusRequester(focusOn(tab)), primary = true) { save() }
                }
            } else {
                if (node == null) Note("Enable HID on Home to type for real")
                KeyRow {
                    // Focus the Dry run key: it is always enabled (even with HID off), unlike Run
                    Key("Dry run", Modifier.weight(1f).focusRequester(focusOn(tab)), enabled = !running) { run(dry = true) }
                    if (running) {
                        Key("Stop", Modifier.weight(1f), primary = true) { job?.cancel() }
                    } else {
                        Key("Run", Modifier.weight(1f), primary = true, enabled = node != null) { run(dry = false) }
                    }
                }
            }
        }) {
            Top { BackKey() }
            Title("Quacky")
            Segs(listOf("Edit", "Run"), tab, { tab = it })
            if (tab == 0) {
                PickRow("File", name.ifEmpty { "new script" } + if (dirty) " *" else "") { picking = true }
                CodeEditor(text, { text = it; dirty = true }, Modifier.fill(min = 200.dp))
            } else {
                PickRow("Options", "${SPEEDS[QuackySetup.mode].lowercase()} · ${QuackySetup.effectivePace} ms") { options = true }
                Terminal(out, Modifier.fill(min = 200.dp))
            }
        }

        // Back with unsaved edits asks to save or discard, the Not applied sheet the primary
        // screens use. Save runs on the app scope so the Back that follows never cancels the write
        NotAppliedSheet(
            pending = dirty && name.isNotEmpty(),
            note = "Unsaved edits. Apply saves them; Discard drops them.",
            canApply = true,
            onDiscard = { dirty = false },
            onApply = { Gadget.scope.launch { write() } },
            back = back,
        )

        // The .qk dropdown: open one, or New (names a fresh script)
        Sheet(
            picking,
            { picking = false },
            footer = {
                Key("Cancel", Modifier.weight(1f)) { picking = false }
                Key("New", Modifier.weight(1f), primary = true) {
                    picking = false
                    naming = 1
                }
            },
        ) {
            Title("Scripts")
            Text(dir, LcdType.data)
            if (files.isEmpty()) Note("No scripts yet")
            files.forEach { f ->
                FileRow(f.name, sizeText(f.bytes), null, f.name == name, onPick = {
                    when {
                        f.name == name -> picking = false // already open, keep the buffer
                        dirty -> discard = f
                        else -> open(f)
                    }
                })
            }
        }

        // New / Save as: a bare name, .qk added, written on confirm
        NamingSheet(naming, dir, if (naming == 2) name.removeSuffix(".qk") else "", files.map { it.name }, { naming = 0 }) { file ->
            // New starts an empty editor, not the last file's text; Save as keeps the current text
            if (naming == 1) text = ""
            name = file
            naming = 0
            dirty = false
            scope.launch { write() }
        }

        // Opening another script would lose unsaved edits
        Sheet(
            discard != null,
            { discard = null },
            footer = {
                Key("Cancel", Modifier.weight(1f)) { discard = null }
                Key("Discard", Modifier.weight(1f), primary = true) {
                    discard?.let { open(it) }
                    discard = null
                }
            },
        ) {
            Title("Discard?")
            Note("$name has unsaved edits")
        }

        // Speed and pace for the run. Fast and Ultra drop the pace to 0, still editable
        Sheet(
            options,
            { options = false },
            footer = { Key("Done", Modifier.weight(1f), primary = true) { options = false } },
        ) {
            Title("Options")
            Segs(SPEEDS, QuackySetup.mode, { QuackySetup.mode(it) })
            Field("Pace") {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Input(
                        QuackySetup.pace,
                        { v -> QuackySetup.pace(v) },
                        Modifier.width(88.dp),
                        placeholder = QuackySetup.defaultPace.toString(),
                        keyboard = KeyboardOptions(keyboardType = KeyboardType.Number),
                    )
                    Text("ms", LcdType.data)
                }
            }
            Kv("Target") { Text(node ?: "no keyboard", LcdType.data) }
        }
    }
}

// A bordered File / Options row (the canvas optrow): label left, value and > right, inverting on
// focus or press as the shared LinkRow does, but boxed so it lines up with the editor and terminal
@Composable
private fun PickRow(label: String, value: String, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val pressed by interaction.collectIsPressedAsState()
    val haptics = LocalHaptics.current
    Box(Modifier.lcdClickable(interaction, outline = false, role = Role.Button) { haptics.keyPress(); onClick() }) {
        Inverted(focused || pressed) {
            val lcd = LocalLcd.current
            // Border drawn inside the inversion, so it stays visible when the row flips
            Kv(label, Modifier.border(Lcd.pixel, lcd.ink).background(lcd.ground).padding(horizontal = 10.dp)) {
                Marquee(value, LcdType.data, Modifier.weight(1f), end = true)
                Text(" >", LcdType.data)
            }
        }
    }
}

/*
 * The editor: a framed box with a line-number gutter beside the text, both in VT323 so the numbers
 * line up with the lines. The field fills the box, so a tap anywhere opens the keyboard and places
 * the cursor; it grows past the box for a long script, scrolled with the gutter by the one scroll.
 * A soft-wrapped long line offsets the gutter, but .qk lines are short.
 */
@Composable
private fun CodeEditor(text: String, onChange: (String) -> Unit, modifier: Modifier = Modifier) {
    val ink = LocalLcd.current.ink
    val scroll = rememberScrollState()
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    // Selection kept here, so the cursor's rect is known; a file opened from outside replaces it
    var value by remember { mutableStateOf(TextFieldValue(text)) }
    val shown = if (value.text == text) value else TextFieldValue(text)
    var layout by remember { mutableStateOf<TextLayoutResult?>(null) }
    val laid = layout?.takeIf { it.layoutInput.text.text == text }
    val bring = remember { BringIntoViewRequester() }
    // The keyboard shrinks the box after the field took focus, so the cursor is brought back into
    // view (through the editor's scroll and the page's) as it opens, and as the text moves
    // Set from each layout, so a keystroke keeps the last numbers until the new layout lands;
    // falling back to one row a line in between made the rows below a wrapped line blink
    var numbers by remember { mutableStateOf(gutter(text, null, 0)) }
    val ime = WindowInsets.ime.getBottom(LocalDensity.current)
    LaunchedEffect(focused, ime, shown.selection, laid) {
        val l = laid ?: return@LaunchedEffect
        if (focused) bring.bringIntoView(l.getCursorRect(shown.selection.end.coerceIn(0, text.length)))
    }
    BoxWithConstraints(
        modifier
            .border(Lcd.pixel, ink)
            .then(if (focused) Modifier.focusOutline(ink) else Modifier),
    ) {
        val inner = (maxHeight - 20.dp).coerceAtLeast(0.dp)
        Row(Modifier.verticalScroll(scroll).padding(10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            // Right-aligned line numbers hugging a 2px rule down their right edge (spanning the
            // lines), as the canvas has it; widthIn gives one-digit numbers a left margin. One
            // number a real line, a wrapped line's rows left blank, read off the field's layout
            Text(
                numbers,
                LcdType.data.copy(textAlign = TextAlign.End),
                Modifier
                    .widthIn(min = 24.dp)
                    .drawBehind {
                        val x = size.width - Lcd.pixel.toPx()
                        drawRect(ink, topLeft = Offset(x, 0f), size = Size(Lcd.pixel.toPx(), size.height))
                    }
                    .padding(end = 6.dp),
            )
            CompositionLocalProvider(LocalTextSelectionColors provides TextSelectionColors(ink, ink.copy(alpha = 0.4f))) {
                BasicTextField(
                    shown,
                    {
                        value = it
                        if (it.text != text) onChange(it.text)
                    },
                    Modifier.weight(1f).heightIn(min = inner).bringIntoViewRequester(bring).focusTick(),
                    textStyle = LcdType.data.copy(color = ink),
                    keyboardOptions = CODE,
                    interactionSource = interaction,
                    cursorBrush = SolidColor(ink),
                    onTextLayout = {
                        layout = it
                        numbers = gutter(it.layoutInput.text.text, it::getLineForOffset, it.lineCount)
                    },
                )
            }
        }
    }
}

// New (empty name) or Save as (the current name prefilled): a bare name, .qk appended. A name with
// a slash, a leading dot or (for New) one already there is refused, as Create's name is
@Composable
private fun NamingSheet(mode: Int, dir: String, preset: String, existing: List<String>, onClose: () -> Unit, onName: (String) -> Unit) {
    val cancel = remember { FocusRequester() }
    var raw by rememberSaveable(mode) { mutableStateOf(preset) }
    val name = raw.trim()
    val file = if (name.endsWith(".qk")) name else "$name.qk"
    // Save as to the current file is just overwriting it; any other existing name is taken, so a
    // Save as never silently clobbers another script
    val self = preset.takeIf { it.isNotEmpty() }?.let { if (it.endsWith(".qk")) it else "$it.qk" }
    val badName = name.isEmpty() || '/' in name || name.startsWith('.')
    val taken = file in existing && file != self
    Sheet(
        mode != 0,
        onDismiss = onClose,
        entry = cancel,
        footer = {
            Key("Cancel", Modifier.weight(1f).focusRequester(cancel), onClick = onClose)
            Key(if (mode == 1) "Create" else "Save", Modifier.weight(1f), primary = true, enabled = !badName && !taken) { onName(file) }
        },
    ) {
        Title(if (mode == 1) "New" else "Save as")
        Field("Name") {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Input(raw, { raw = it.filter { c -> c != '/' && !c.isISOControl() }.take(255) }, Modifier.weight(1f), placeholder = "archinstall")
                Text(".qk", LcdType.data)
            }
        }
        if (taken) Warn("name taken")
        Text(dir, LcdType.data)
    }
}
