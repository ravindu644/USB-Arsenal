package com.usbarsenal.app.ui

import androidx.activity.compose.BackHandler
import com.usbarsenal.app.lcd.LocalLcd
import com.usbarsenal.app.lcd.Inverted
import androidx.compose.foundation.background
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.usbarsenal.app.backend.IMAGES
import com.usbarsenal.app.backend.ImageFile
import com.usbarsenal.app.backend.createFull
import com.usbarsenal.app.backend.createSparse
import com.usbarsenal.app.backend.fileBytes
import com.usbarsenal.app.backend.freeBytes
import com.usbarsenal.app.backend.imagesDir
import com.usbarsenal.app.backend.listImages
import com.usbarsenal.app.backend.realPath
import com.usbarsenal.app.backend.removeFile
import com.usbarsenal.app.lcd.Lcd
import com.usbarsenal.app.lcd.LcdType
import com.usbarsenal.app.lcd.LocalHaptics
import com.usbarsenal.app.lcd.Pin
import com.usbarsenal.app.lcd.SpriteIcon
import com.usbarsenal.app.lcd.Sprites
import java.util.Locale
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val MIB = 1L shl 20
private const val GIB = 1L shl 30

// storage.c's CDROM_MAX_BYTES: past this the host reads a truncated disc
private const val CDROM_MAX = (256L * 60 * 75 - 1) * 2048

// The modes as `storage start --mode` takes them, and as the segments say them
private val MODES = listOf("auto", "cdrom", "dvd", "disk", "rw")
private val MODE_LABELS = listOf("auto", "cd", "dvd", "disk", "rw")

// "5.8G", "412M": binary units, as the files list and the drive show sizes
fun sizeText(bytes: Long): String = if (bytes >= GIB) "%.1fG".format(Locale.ROOT, bytes.toDouble() / GIB) else "${bytes / MIB}M"

// A warning only for a consequence (DESIGN.md): a CD-ROM past its limit
fun storageWarning(mode: String, bytes: Long?): String? =
    "over 2.36G: the host reads a truncated disc".takeIf { mode == "cdrom" && bytes != null && bytes > CDROM_MAX }

// What the host gets in each mode, so the wrong one is not picked: a dead or truncated drive
fun modeHint(mode: String): String = when (mode) {
    "auto" -> "disk if it has a partition table, else cd"
    "cdrom" -> "read-only cd-rom, up to 2.36G"
    "dvd" -> "read-only, 2048-byte sectors, any size"
    "disk" -> "read-only disk"
    else -> "writable disk, the host can change it"
}

// The Create sheet's size, in bytes; null for anything that is not a whole number above 0
fun createBytes(size: String, gb: Boolean): Long? = size.trim().toLongOrNull()?.takeIf { it > 0 }?.times(if (gb) GIB else MIB)

/*
 * Storage: a drive per LUN. The strip picks the LUN, the drive shows what it serves, the mode
 * segments say how the host sees it, and the files in /sdcard/USB-Arsenal load into it.
 * Picking a file serves it at once (the backend swaps it in place, or replugs on a type
 * change); every pick is remembered, so Home's switch can bring them all back in one start.
 */
@Composable
fun StorageScreen(back: () -> Unit, openLog: () -> Unit) {
    BackHandler(onBack = back)
    PollStatus()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    LaunchedEffect(Unit) { Picks.load(context) }
    var dir by remember { mutableStateOf(IMAGES) }
    var files by remember { mutableStateOf<List<ImageFile>?>(null) }
    var free by remember { mutableStateOf<Long?>(null) }
    var reload by remember { mutableIntStateOf(0) }
    LaunchedEffect(reload) {
        dir = imagesDir()
        files = listImages(dir)
        free = freeBytes()
    }
    val live = Gadget.status?.storage.orEmpty().filter { it.file.isNotEmpty() }
    val picks = Picks.all
    var lun by rememberSaveable { mutableIntStateOf(0) }
    // Every LUN that serves or has a pick, and the one being added
    val count = ((live.map { it.lun } + picks.map { it.lun } + lun).max() + 1).coerceAtMost(8)
    val served = live.firstOrNull { it.lun == lun }
    val pick = picks.firstOrNull { it.lun == lun }
    val path = served?.file ?: pick?.path
    val name = path?.substringAfterLast('/')
    val file = files?.firstOrNull { it.path == path }
    // Before anything is picked, the mode is the one the next pick will take
    var nextMode by rememberSaveable { mutableStateOf("auto") }
    val mode = pick?.mode ?: served?.mode ?: nextMode
    var creating by rememberSaveable { mutableStateOf(false) }
    val shake = rememberShake()
    val fault = Gadget.fault?.takeIf { it.where == Fn.STORAGE }
    // Which part the last command came from: its fault shakes and sits under that part
    var fromDrive by remember { mutableStateOf(false) }
    LaunchedEffect(fault) { if (fault != null) shake.fire() }

    // Remembered only once it serves, so a refused image is not offered again at the next start
    fun serve(p: String, m: String) {
        val at = lun
        fromDrive = false
        Gadget.run(Fn.STORAGE, "storage", "start", "--lun", "$at", "--mode", m, p) { if (it) Picks.set(context, Pick(at, m, p)) }
    }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            scope.launch {
                val p = realPath(context, uri)
                if (p != null) serve(p, mode) else Gadget.fault = Fault(Fn.STORAGE, "[-] That picker gives no file path; copy the image into $IMAGES")
            }
        }
    }

    Box(Modifier.fillMaxSize()) {
        Screen {
            Top { BackKey() }
            Title("Storage")
            LunStrip(count, lun, { lun = it }, { lun = count })
            Drive(
                lun,
                image = name?.let { DriveImage(it, file?.bytes?.let(::sizeText).orEmpty(), served?.mode ?: mode, served?.access ?: if (mode == "rw") "rw" else "ro") },
                serving = served != null,
                ejected = false,
                modifier = if (fromDrive) Modifier.shake(shake) else Modifier,
                onEject = {
                    val at = lun
                    fromDrive = true
                    if (served == null) Picks.drop(context, at)
                    else Gadget.run(Fn.STORAGE, "storage", "eject", "--lun", "$at") { if (it) Picks.drop(context, at) }
                },
            )
            if (fromDrive) fault?.let { Fault(it.message, onLog = openLog) }
            if (path == null && files?.isEmpty() == true) {
                // Nothing loaded and nothing to load: Pin asks for an image
                Box(Modifier.fillMaxWidth().height(150.dp), contentAlignment = Alignment.Center) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        SpriteIcon(Sprites.Question, 4.dp)
                        Pin(8.dp)
                    }
                }
                Text("No image loaded", LcdType.labelStrong, Modifier.align(Alignment.CenterHorizontally))
                if (!fromDrive) fault?.let { Fault(it.message, onLog = openLog) }
                Box(Modifier.weight(1f))
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Mode", LcdType.labelStrong)
                    Segs(MODE_LABELS, MODES.indexOf(mode).coerceAtLeast(0), { i ->
                        val m = MODES[i]
                        if (path != null) serve(path, m) else nextMode = m
                    }, if (fromDrive) Modifier else Modifier.shake(shake))
                    if (!fromDrive) fault?.let { Fault(it.message, onLog = openLog) }
                    // One line whatever the mode, so FILES never moves: the hint, or the warning in its place
                    val warning = storageWarning(mode, file?.bytes)
                    Inverted(warning != null) {
                        Text(
                            warning ?: modeHint(mode), LcdType.data,
                            Modifier.bleed(10.dp).fillMaxWidth().background(LocalLcd.current.ground).padding(horizontal = 10.dp, vertical = 8.dp),
                            maxLines = 1,
                        )
                    }
                }
                Column(Modifier.weight(1f).bleed(10.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    FilesHeader(IMAGES)
                    files.orEmpty().forEach { f ->
                        // A file another LUN serves or holds is tagged with it and cannot be picked
                        val other = (live.filter { it.lun != lun }.map { it.lun to it.file } + picks.filter { it.lun != lun }.map { it.lun to it.path })
                            .firstOrNull { it.second == f.path }?.first
                        FileRow(f.name, sizeText(f.bytes), other?.let { "lun$it" }, selected = f.path == path, onPick = { serve(f.path, mode) })
                    }
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Lcd.tight)) {
                Key("Pick image", Modifier.weight(1f)) { picker.launch(arrayOf("*/*")) }
                Key("Create", Modifier.weight(1f), primary = true) { creating = true }
            }
        }
        CreateSheet(creating, dir, files, free, onDismiss = { creating = false }) { p ->
            creating = false
            reload++
            serve(p, "rw")
        }
    }
}

/*
 * Create: a new image in the folder, served read-write on this LUN once it exists. Sparse is
 * set in an instant; a full one is written out with dd, its progress the file's size, and
 * Cancel removes what was written.
 */
@Composable
private fun CreateSheet(open: Boolean, dir: String, files: List<ImageFile>?, free: Long?, onDismiss: () -> Unit, onCreated: (String) -> Unit) {
    val haptics = LocalHaptics.current
    val scope = rememberCoroutineScope()
    var name by rememberSaveable { mutableStateOf("") }
    var size by rememberSaveable { mutableStateOf("4") }
    var gb by rememberSaveable { mutableStateOf(true) }
    var sparse by rememberSaveable { mutableStateOf(true) }
    var written by remember { mutableStateOf<Pair<Long, Long>?>(null) }
    var failed by remember { mutableStateOf<String?>(null) }
    var job by remember { mutableStateOf<Job?>(null) }
    val target = name.trim().ifEmpty { "disk.img" }
    val bytes = createBytes(size, gb)
    val taken = files?.any { it.name == target } == true
    val tooBig = bytes != null && free != null && bytes > free
    val badName = '/' in target || target.startsWith('.')
    val writing = written != null

    fun create() {
        val total = bytes ?: return
        val path = "$dir/$target"
        failed = null
        job = scope.launch {
            if (sparse) {
                val r = createSparse(path, total)
                if (r.ok) {
                    haptics.confirm()
                    onCreated(path)
                } else {
                    failed = r.err.lastOrNull() ?: "[-] Failed (exit ${r.code})"
                    haptics.reject()
                }
                return@launch
            }
            val megabytes = total / MIB
            val dd = createFull(path, megabytes)
            if (dd == null) {
                failed = "[-] No root"
                return@launch
            }
            written = 0L to total
            val poll = launch {
                while (true) {
                    delay(500)
                    fileBytes(path)?.let { written = it to total }
                }
            }
            try {
                dd.lines.collect {}
                poll.cancel()
                if (fileBytes(path) == megabytes * MIB) {
                    haptics.confirm()
                    onCreated(path)
                } else {
                    failed = "[-] The image came out short; it was removed"
                    removeFile(path)
                    haptics.reject()
                }
            } finally {
                // Cancel, or the screen going: dd stops, and half an image is no image
                withContext(NonCancellable) {
                    poll.cancel()
                    dd.close()
                    if (job?.isCancelled == true) removeFile(path)
                }
                written = null
            }
        }
    }

    Sheet(open, onDismiss = { if (!writing) onDismiss() }) {
        Title("Create")
        val progress = written
        if (progress == null) {
            Field("Name") { Input(name, { name = it }, Modifier.fillMaxWidth(), placeholder = "disk.img") }
            if (taken) Warn("name taken")
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(Modifier.fillMaxWidth()) {
                    Text("Size", LcdType.labelStrong, Modifier.weight(1f))
                    if (free != null) Text("free ${sizeText(free)}", LcdType.data)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(Lcd.tight), verticalAlignment = Alignment.CenterVertically) {
                    Input(size, { size = it.filter(Char::isDigit) }, Modifier.weight(1f), keyboard = KeyboardOptions(keyboardType = KeyboardType.Number))
                    Segs(listOf("MB", "GB"), if (gb) 1 else 0, { gb = it == 1 }, Modifier.width(140.dp))
                }
            }
            if (tooBig) Warn("more than the free ${sizeText(free)}")
            Kv("Sparse") { Dip(sparse, { sparse = it }, "Sparse") }
            failed?.let { Fault(it, onLog = null) }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Lcd.tight)) {
                Key("Cancel", Modifier.weight(1f), onClick = onDismiss)
                Key("Create", Modifier.weight(1f), primary = true, enabled = bytes != null && !taken && !tooBig && !badName && job?.isActive != true) { create() }
            }
        } else {
            val (done, total) = progress
            Kv(target, fact = true) { Text(sizeText(total), LcdType.data) }
            Cells((done * 10 / total).toInt())
            Text("${sizeText(done)} of ${sizeText(total)}", LcdType.data, cursor = true)
            Key("Cancel", Modifier.fillMaxWidth()) { job?.cancel() }
        }
    }
}
