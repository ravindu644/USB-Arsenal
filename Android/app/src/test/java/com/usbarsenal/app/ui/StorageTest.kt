package com.usbarsenal.app.ui

import com.usbarsenal.app.backend.isImage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

// Storage says sizes, warnings and the Create size the way the boards and storage.c do
class StorageTest {
    @Test
    fun sizes() {
        assertEquals("412M", sizeText(412L shl 20))
        assertEquals("100K", sizeText(100L shl 10))
        assertEquals("512B", sizeText(512))
        assertEquals("4.0G", sizeText(4L shl 30))
        assertEquals("5.8G", sizeText((5.8 * (1L shl 30)).toLong()))
    }

    @Test
    fun warnings() {
        // rw needs none: the segment and the drive's tag already say it
        assertNull(storageWarning("rw", 1L shl 30))
        // Over the CD-ROM limit only matters in cdrom mode; auto picks dvd for it
        assertEquals("over 2.36G: the host reads a truncated disc", storageWarning("cdrom", 5L shl 30))
        assertNull(storageWarning("cdrom", 1L shl 30))
        assertNull(storageWarning("auto", 5L shl 30))
    }

    // Each hint shares one line with the warning, so none may be longer than it
    @Test
    fun hintsFitTheLine() {
        val line = storageWarning("cdrom", 5L shl 30)!!.length
        listOf("auto", "cdrom", "dvd", "disk", "rw").forEach { assert(modeHint(it).length <= line) { it } }
    }

    @Test
    fun createSize() {
        assertEquals(4L shl 30, createBytes("4", gb = true))
        assertEquals(512L shl 20, createBytes(" 512 ", gb = false))
        assertNull(createBytes("0", gb = true))
        assertNull(createBytes("", gb = true))
        assertNull(createBytes("4.5", gb = true))
        // Past the field's six digits it would overflow a Long
        assertNull(createBytes("99999999999", gb = true))
    }

    // Only an image is listed or picked, by its extension in any case
    @Test
    fun imagesByExtension() {
        assertTrue(isImage("/x/Win11.ISO"))
        assertTrue(isImage("/x/pen drive.img"))
        assertFalse(isImage("/x/movie.mp4"))
        assertFalse(isImage("/x/img"))
        assertFalse(isImage("/x/.iso"))
    }
}
