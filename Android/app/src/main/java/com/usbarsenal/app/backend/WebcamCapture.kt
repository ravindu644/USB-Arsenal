package com.usbarsenal.app.backend

import android.content.Context
import android.content.Intent
import androidx.annotation.StringRes
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.usbarsenal.app.R
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull

enum class WebcamCapturePhase { IDLE, STARTING, RUNNING, ERROR }

data class WebcamCaptureState(
    val phase: WebcamCapturePhase = WebcamCapturePhase.IDLE,
    val source: String = "",
    val output: String = "",
    @StringRes val error: Int? = null,
)

object WebcamCapture {
    var state by mutableStateOf(WebcamCaptureState())
        private set

    fun framePath(context: Context, source: String, input: String): String {
        val key = key("$source\n$input")
        return File(context.cacheDir, "webcam-$key.jpg").absolutePath
    }

    fun saveInput(context: Context, source: String, input: String) {
        val file = inputFile(context, source)
        if (input.isEmpty()) {
            if (file.exists() && !file.delete()) throw IOException("Could not clear the saved webcam source")
            return
        }
        val temp = File(file.parentFile, "${file.name}.tmp")
        try {
            FileOutputStream(temp).use { stream ->
                stream.write(input.toByteArray(Charsets.UTF_8))
                stream.fd.sync()
            }
            try {
                android.system.Os.rename(temp.absolutePath, file.absolutePath)
            } catch (error: android.system.ErrnoException) {
                throw IOException("Could not save the selected webcam source", error)
            }
        } finally {
            if (temp.exists()) temp.delete()
        }
    }

    fun savedInput(context: Context, source: String): String {
        val file = inputFile(context, source)
        if (!file.exists()) return ""
        if (file.length() > MAX_SAVED_INPUT_BYTES) throw IOException("Saved webcam input is too large")
        return file.readText(Charsets.UTF_8)
    }

    fun start(
        context: Context,
        source: String,
        input: String,
        projectionResult: Int? = null,
        projectionData: Intent? = null,
    ): String {
        val output = framePath(context, source, input)
        state = WebcamCaptureState(WebcamCapturePhase.STARTING, source, output)
        val intent = Intent(context, WebcamCaptureService::class.java)
            .setAction(WebcamCaptureService.ACTION_START)
            .putExtra(WebcamCaptureService.EXTRA_SOURCE, source)
            .putExtra(WebcamCaptureService.EXTRA_INPUT, input)
            .putExtra(WebcamCaptureService.EXTRA_OUTPUT, output)
        projectionResult?.let { intent.putExtra(WebcamCaptureService.EXTRA_PROJECTION_RESULT, it) }
        projectionData?.let { intent.putExtra(WebcamCaptureService.EXTRA_PROJECTION_DATA, it) }
        androidx.core.content.ContextCompat.startForegroundService(context, intent)
        return output
    }

    fun stop(context: Context) {
        state = WebcamCaptureState()
        context.startService(
            Intent(context, WebcamCaptureService::class.java)
                .setAction(WebcamCaptureService.ACTION_RELEASE),
        )
    }

    internal fun publish(next: WebcamCaptureState) {
        state = next
    }

    suspend fun awaitFrame(path: String): Boolean = withTimeoutOrNull(30_000) {
        while (true) {
            val now = state
            if (now.output == path && now.phase == WebcamCapturePhase.RUNNING && File(path).length() > 4)
                return@withTimeoutOrNull true
            if (now.output == path && now.phase == WebcamCapturePhase.ERROR)
                return@withTimeoutOrNull false
            delay(100)
        }
        false
    } ?: false

    fun errorOr(default: Int = R.string.webcam_capture_failed) =
        state.error ?: default

    private fun inputFile(context: Context, source: String) =
        File(context.noBackupFilesDir, "webcam-input-${key(source)}")

    private fun key(value: String) = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray())
        .take(10)
        .joinToString("") { "%02x".format(it.toInt() and 0xff) }

    private const val MAX_SAVED_INPUT_BYTES = 8 * 1024
}
