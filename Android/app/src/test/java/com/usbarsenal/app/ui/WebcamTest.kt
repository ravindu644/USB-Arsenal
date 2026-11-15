package com.usbarsenal.app.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WebcamTest {
    @Test
    fun acceptsSupportedDirectStreamSchemes() {
        assertTrue(validWebcamStreamUrl("https://media.example/live.m3u8"))
        assertTrue(validWebcamStreamUrl("http://media.example/live.mpd"))
        assertTrue(validWebcamStreamUrl("rtsp://192.0.2.10:8554/camera"))
        assertTrue(validWebcamStreamUrl("rtsp://user:password@192.0.2.10:8554/camera"))
    }

    @Test
    fun rejectsUnsafeOrUnsupportedStreamUrls() {
        assertFalse(validWebcamStreamUrl(""))
        assertFalse(validWebcamStreamUrl("file:///sdcard/movie.mp4"))
        assertFalse(validWebcamStreamUrl("javascript:alert(1)"))
        assertFalse(validWebcamStreamUrl("https://media.example/\nstream"))
        assertFalse(validWebcamStreamUrl("https://media.example/${"a".repeat(2_001)}"))
    }
}
