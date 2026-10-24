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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.usbarsenal.app.backend.Cache
import com.usbarsenal.app.backend.IMAGES
import com.usbarsenal.app.backend.ImageFile
import com.usbarsenal.app.backend.NOT_IMAGE
import com.usbarsenal.app.backend.NO_PATH
import com.usbarsenal.app.backend.Root
import com.usbarsenal.app.backend.shellQuote
import com.usbarsenal.app.backend.createFull
import com.usbarsenal.app.backend.createSparse
import com.usbarsenal.app.backend.fileBytes
import com.usbarsenal.app.backend.freeBytes
import com.usbarsenal.app.backend.imagesDir
import com.usbarsenal.app.backend.isImage
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
fun sizeText(bytes: Long): String = when {
    bytes >= GIB -> "%.1fG".format(Locale.ROOT, bytes.toDouble() / GIB)
    bytes >= MIB -> "${bytes / MIB}M"
    bytes >= 1024 -> "${bytes / 1024}K"
    else -> "${bytes}B"
}

// The kind a drive shows: the kernel's mode (`status --json`) or the setup's, as the segments name
// it; rw is a disk, its tag says the access
fun kindOf(mode: String) = when (mode) {
    "cdrom" -> "cd"
    "rw" -> "disk"
    else -> mode
}

// A warning only for a consequence (DESIGN.md): a CD-ROM past its limit
fun storageWarning(mode: String, bytes: Long?): String? =
    "over 2.36G: the host reads a truncated disc".takeIf { mode == "cdrom" && bytes != null && bytes > CDROM_MAX }

// What the host gets in each mode, so the wrong one is not picked: a dead or truncated drive
fun modeHint(mode: String): String = when (mode) {
    "auto" -> "disk if partitioned; cd, or dvd past 2.36G"
    "cdrom" -> "read-only cd-rom, up to 2.36G"
    "dvd" -> "read-only, 2048-byte sectors, any size"
    "disk" -> "read-only disk"
    else -> "writable disk, the host can change it"
}

// The Create sheet's size, in bytes; null for anything that is not a whole number from 1 to the
// field's six digits, which also keeps the product inside a Long
fun createBytes(size: String, gb: Boolean): Long? = size.trim().toLongOrNull()?.takeIf { it in 1..999_999 }?.times(if (gb) GIB else MIB)

/*
 * Storage: a drive per LUN. The strip picks the LUN, the drive shows what is picked for it
 * (its LED, whether that LUN is live), the mode segments say how the host will see it, and
 * the images in /sdcard/USB-Arsenal/Disks load into it. It only builds the setup Home's switch
 * starts, every LUN in one start; a change while storage runs is applied, or discarded, on the way back.
 */
@Composable
fun StorageScreen(back: () -> Unit, openLog: () -> Unit) {
    BackHandler(onBack = back)
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val live = Gadget.status?.storage.orEmpty().filter { it.file.isNotEmpty() }
    // The folder as last listed, so the screen has its final layout from the first frame; a
    // fresh listing replaces it
    var dir by remember { mutableStateOf(Cache.imagesDir ?: IMAGES) }
    var files by remember { mutableStateOf(Cache.images) }
    var free by remember { mutableStateOf(Cache.free) }
    var reload by remember { mutableIntStateOf(0) }
    LaunchedEffect(reload) {
        dir = imagesDir().also { Cache.imagesDir = it }
        files = listImages(dir).also { Cache.images = it }
        free = freeBytes().also { Cache.free = it }
    }
    val picks = picks()
    var lun by rememberSaveable { mutableIntStateOf(0) }
    // Every LUN set up and the one on screen: an ejected LUN leaves when you move off it, even
    // while it still runs, since Back's unapplied sheet is where that shows
    val count = ((picks.map { it.lun } + lun).max() + 1).coerceAtMost(LUNS)
    val served = live.firstOrNull { it.lun == lun }
    val pick = picks.firstOrNull { it.lun == lun }
    val path = pick?.path
    val name = path?.substringAfterLast('/')
    val file = files?.firstOrNull { it.path == path }
    // A file picked from elsewhere is not in the listing: its size is read once, for the drive
    // and the CD-ROM warning
    var picked by remember { mutableStateOf<Pair<String, Long?>?>(null) }
    LaunchedEffect(path, file) { if (path != null && file == null && picked?.first != path) picked = path to fileBytes(path) }
    val bytes = file?.bytes ?: picked?.takeIf { it.first == path }?.second
    // Before anything is picked, the mode is the one the next pick will take
    var nextMode by rememberSaveable { mutableStateOf("auto") }
    val mode = pick?.mode ?: nextMode
    // What the host is served on this LUN, when it is the file picked: the kernel's own words
    val runs = served?.takeIf { it.file == path }
    var creating by rememberSaveable { mutableStateOf(false) }
    val shake = rememberShake()
    val fault = Gadget.fault?.takeIf { it.where == Fn.STORAGE }
    LaunchedEffect(fault) { if (fault != null) shake.fire() }

    fun serve(p: String, m: String) = setPick(Pick(lun, m, p))
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            scope.launch {
                realPath(context, uri)
                    .onSuccess { if (isImage(it)) serve(it, mode) else Gadget.fault = Fault(Fn.STORAGE, NOT_IMAGE) }
                    .onFailure { Gadget.fault = Fault(Fn.STORAGE, it.message ?: NO_PATH) }
            }
        }
    }

    Box(Modifier.fillMaxSize()) {
        Screen(
            footer = {
                // Over the keys, which is where a pick fails; the body above never moves for it
                fault?.let { Fault(it.message, onLog = openLog, modifier = Modifier.shake(shake).opener("storage/fault")) }
                KeyRow {
                    Key("Pick image", Modifier.weight(1f)) { picker.launch(arrayOf("*/*")) }
                    Key("Create", Modifier.weight(1f).focusRequester(focusOn()).opener("storage/create"), primary = true) { creating = true }
                }
            },
        ) {
            Top { BackKey() }
            Title("Storage")
            LunStrip(count, lun, { lun = it }, { if (count < LUNS) lun = count })
            Drive(
                lun,
                image = name?.let {
                    DriveImage(it, bytes?.let(::sizeText).orEmpty(), kindOf(runs?.mode ?: mode), runs?.access ?: if (mode == "rw") "rw" else "ro")
                },
                serving = served != null,
                onEject = { dropPick(lun) },
            )
            run {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Mode", LcdType.labelStrong)
                    Segs(MODE_LABELS, MODES.indexOf(mode).coerceAtLeast(0), { i ->
                        val m = MODES[i]
                        if (path != null) serve(path, m) else nextMode = m
                    })
                    // One line whatever the mode, so FILES never moves: the hint, or the warning in its place
                    val warning = storageWarning(mode, bytes)
                    Inverted(warning != null) {
                        Marquee(
                            warning ?: modeHint(mode), LcdType.data,
                            Modifier.bleed(10.dp).fillMaxWidth().background(LocalLcd.current.ground).padding(horizontal = 10.dp, vertical = 8.dp),
                        )
                    }
                }
                // The drive and its mode above, the folder to load from below
                Rule()
                Column(Modifier.bleed(10.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    FilesHeader(IMAGES)
                    // Nothing to load: Pin asks for an image, where the rows would be, so nothing
                    // above moves when the listing comes back
                    if (path == null && files?.isEmpty() == true) {
                        Box(Modifier.fillMaxWidth().height(150.dp), contentAlignment = Alignment.Center) {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                SpriteIcon(Sprites.Question, 4.dp)
                                Pin(8.dp)
                            }
                        }
                        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { Text("No image loaded", LcdType.labelStrong) }
                    }
                    files.orEmpty().forEach { f ->
                        // A file another LUN holds is tagged with it and cannot be picked
                        val other = picks.firstOrNull { it.lun != lun && it.path == f.path }?.lun
                        FileRow(f.name, sizeText(f.bytes), other?.let { "lun$it" }, selected = f.path == path, onPick = { serve(f.path, mode) })
                    }
                }
            }
        }
        UnappliedSheet(Fn.STORAGE, back)
        CreateSheet(creating, dir, files, free, onDismiss = { creating = false }) { p, size ->
            creating = false
            // Listed at once, so the drive shows its size from the first frame; the reload confirms it
            files = files.orEmpty().filter { it.path != p } + ImageFile(p, size)
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
private fun CreateSheet(open: Boolean, dir: String, files: List<ImageFile>?, free: Long?, onDismiss: () -> Unit, onCreated: (String, Long) -> Unit) {
    val haptics = LocalHaptics.current
    val shake = rememberShake()
    val scope = rememberCoroutineScope()
    var name by rememberSaveable { mutableStateOf("") }
    var size by rememberSaveable { mutableStateOf("4") }
    var gb by rememberSaveable { mutableStateOf(true) }
    var sparse by rememberSaveable { mutableStateOf(true) }
    var written by remember { mutableStateOf<Pair<Long, Long>?>(null) }
    var failed by remember { mutableStateOf<String?>(null) }
    var job by remember { mutableStateOf<Job?>(null) }
    // A name without an image's extension gets .img, so the image is listed once it exists
    val target = name.trim().ifEmpty { "disk.img" }.let { if (isImage(it)) it else "$it.img" }
    val bytes = createBytes(size, gb)
    // Each opening starts with no name, so the last image's name is never shown as taken
    LaunchedEffect(open) {
        if (open) {
            name = ""
            failed = null
        }
    }
    val taken = files?.any { it.name == target } == true
    // A sparse image takes no blocks until the host writes them
    val tooBig = !sparse && bytes != null && free != null && bytes > free
    val badName = '/' in target || target.startsWith('.')
    val writing = written != null

    fun create() {
        val total = bytes ?: return
        val path = "$dir/$target"
        failed = null
        fun fail(why: String) {
            failed = why
            shake.fire()
        }
        job = scope.launch {
            // Anything there by that name, a folder too, which the listing leaves out
            if (Root.read("test -e ${shellQuote(path)}").ok) return@launch fail("[-] $target is already there")
            if (sparse) {
                val r = createSparse(path, total)
                if (r.ok) {
                    haptics.confirm()
                    onCreated(path, total)
                } else {
                    fail(r.why)
                }
                return@launch
            }
            val megabytes = total / MIB
            val dd = createFull(path, megabytes) ?: return@launch fail("[-] No root")
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
                    onCreated(path, megabytes * MIB)
                } else {
                    removeFile(path)
                    fail("[-] The image came out short; it was removed")
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

    val cancelKey = remember { FocusRequester() }
    val progress = written
    // The Create key goes when writing starts; its Cancel takes the focus, so a pad can still stop dd
    LaunchedEffect(writing) { if (writing) cancelKey.requestFocus() }
    Sheet(
        open,
        onDismiss = { if (!writing) onDismiss() },
        entry = cancelKey,
        footer = {
            if (progress == null) {
                Key("Cancel", Modifier.weight(1f).focusRequester(cancelKey), onClick = onDismiss)
                Key("Create", Modifier.weight(1f), primary = true, enabled = bytes != null && !taken && !tooBig && !badName && job?.isActive != true) { create() }
            } else {
                Key("Cancel", Modifier.weight(1f).focusRequester(cancelKey)) { job?.cancel() }
            }
        },
    ) {
        Title("Create")
        if (progress == null) {
            Field("Name") { Input(name, { name = it.filter { c -> c != '/' && !c.isISOControl() }.take(255) }, Modifier.fillMaxWidth(), placeholder = "disk.img") }
            if (taken) Warn("name taken")
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(Modifier.fillMaxWidth()) {
                    Text("Size", LcdType.labelStrong, Modifier.weight(1f))
                    if (free != null) Text("free ${sizeText(free)}", LcdType.data)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(Lcd.tight), verticalAlignment = Alignment.CenterVertically) {
                    Input(size, { size = it.filter(Char::isDigit).take(6) }, Modifier.weight(1f), keyboard = KeyboardOptions(keyboardType = KeyboardType.Number))
                    Segs(listOf("MB", "GB"), if (gb) 1 else 0, { gb = it == 1 }, Modifier.width(140.dp))
                }
            }
            if (tooBig) Warn("more than the free ${sizeText(free)}")
            Kv("Sparse") { Dip(sparse, { sparse = it }, "Sparse") }
            failed?.let { Fault(it, onLog = null, modifier = Modifier.shake(shake)) }
        } else {
            val (done, total) = progress
            Kv(target, fact = true) { Text(sizeText(total), LcdType.data) }
            Cells((done * 10 / total).toInt())
            Text("${sizeText(done)} of ${sizeText(total)}", LcdType.data, cursor = true)
        }
    }
}
