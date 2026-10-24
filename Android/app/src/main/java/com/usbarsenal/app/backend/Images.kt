package com.usbarsenal.app.backend

import android.annotation.SuppressLint
import android.content.Context
import android.net.Uri
import android.system.Os
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

// The app's folder on shared storage, as root sees it in the global mount namespace, which is
// where every path here is opened; the app's own view of storage is not involved
@SuppressLint("SdCardPath")
const val FOLDER = "/sdcard/USB-Arsenal"

// The folder Storage lists and Create writes to
const val IMAGES = "$FOLDER/Disks"

// What Storage serves: an image by its name, as the folder lists it and the picker takes it.
// A name that is only the extension (.iso) is a hidden file with none
private val IMAGE_EXTS = setOf("iso", "img", "bin", "raw", "ima", "dd")

fun isImage(path: String): Boolean {
    val name = path.substringAfterLast('/')
    val ext = name.substringAfterLast('.', "")
    return ext.lowercase() in IMAGE_EXTS && name.length > ext.length + 1
}

const val NOT_IMAGE = "[-] Not a disk image or ISO (.iso .img .bin .raw .ima .dd)"

// An image the folder holds: where it is, and how big
data class ImageFile(val path: String, val bytes: Long) {
    val name get() = path.substringAfterLast('/')
}

// The folder, made if it is not there yet, by its canonical path (/storage/emulated/0/...): the
// form the kernel reports a LUN's file in, so a file and the LUN serving it compare equal
suspend fun imagesDir(): String = Root.read("mkdir -p $IMAGES && readlink -f $IMAGES").out.firstOrNull() ?: IMAGES

// The folder's images; stat prints "<bytes> <path>"
suspend fun listImages(dir: String): List<ImageFile> = parseImages(
    Root.read("for f in ${shellQuote(dir)}/*; do [ -f \"\$f\" ] && stat -c '%s %n' \"\$f\"; done; true").out,
).filter { isImage(it.path) }

// What the folder's filesystem has left for a new image
suspend fun freeBytes(): Long? = Root.read("stat -f -c '%a %S' $IMAGES").out.firstOrNull()?.split(' ')?.let { (a, s) ->
    (a.toLongOrNull() ?: return@let null) * (s.toLongOrNull() ?: return@let null)
}

const val NO_PATH = "[-] That picker gives no file path; copy the image into $IMAGES"

/*
 * The file a provider actually opened for [uri], as a path root can open: Droidspaces'
 * FilePickerUtils.realPath. Guessing paths from document IDs differs per provider; the kernel
 * already knows the answer. It comes back canonical (/storage/emulated/0/..., never
 * /mnt/user/0/...), the spelling the folder's files and the kernel's LUNs use, so they compare
 * equal. A failure is the fault to show: no path when the provider hands out a pipe (cloud
 * providers) or root sees a different file there, or root that could not be asked.
 */
suspend fun realPath(context: Context, uri: Uri): Result<String> = withContext(Dispatchers.IO) {
    val pfd = runCatching { context.contentResolver.openFileDescriptor(uri, "r") }.getOrNull()
        ?: return@withContext Result.failure(IllegalStateException("[-] Cannot open that file"))
    pfd.use {
        val path = runCatching { Os.readlink("/proc/self/fd/${it.fd}") }.getOrNull()?.takeIf { p -> p.startsWith("/") }
            ?: return@withContext Result.failure(IllegalStateException(NO_PATH))
        val st = Os.fstat(it.fileDescriptor)
        val q = shellQuote(path)
        val r = Root.read("stat -c '%d %i' $q && readlink -f $q")
        when {
            r.timedOut || r.code == NO_ROOT -> Result.failure(IllegalStateException("[-] Cannot ask root about that file: ${r.why}"))
            r.out.firstOrNull() != "${st.st_dev} ${st.st_ino}" -> Result.failure(IllegalStateException(NO_PATH))
            else -> Result.success(r.out.getOrNull(1) ?: path)
        }
    }
}

// A sparse image: the size is set, no block is written until the host writes one
suspend fun createSparse(path: String, bytes: Long) = Root.change("truncate -s $bytes ${shellQuote(path)}")

// A full image is written out in megabytes; its progress is the file's size as it grows
suspend fun createFull(path: String, megabytes: Long) =
    Root.stream("dd if=/dev/zero of=${shellQuote(path)} bs=1048576 count=$megabytes")

suspend fun fileBytes(path: String): Long? = Root.read("stat -c %s ${shellQuote(path)}").out.firstOrNull()?.toLongOrNull()

suspend fun removeFile(path: String) = Root.change("rm -f ${shellQuote(path)}")
