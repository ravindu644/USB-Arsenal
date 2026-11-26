package com.usbarsenal.app.ui

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.content.pm.PackageManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.usbarsenal.app.R
import com.usbarsenal.app.backend.Root
import com.usbarsenal.app.backend.WORKSPACE
import com.usbarsenal.app.backend.WebcamCapture
import com.usbarsenal.app.backend.WebcamCapturePhase
import com.usbarsenal.app.backend.shellQuote
import com.usbarsenal.app.lcd.Lcd
import com.usbarsenal.app.lcd.LcdType
import com.usbarsenal.app.lcd.SpriteIcon
import com.usbarsenal.app.lcd.Sprites
import com.usbarsenal.app.str
import java.io.File
import java.io.IOException
import java.net.URI
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val WEBCAM_IMAGE = "$WORKSPACE/Gadget/webcam.jpg"
private const val MAX_URL_LENGTH = 2_000

@Composable
fun WebcamScreen(back: () -> Unit, openLog: () -> Unit) {
    BackHandler(onBack = back)
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val path = Gadget.setupValue("webcam_image")
    val configuredSource = Gadget.setupValue("webcam_source")
        .ifEmpty { if (path.isNotEmpty()) "image" else "" }
    val webcamOn = Gadget.status.isOn(Fn.WEBCAM)
    val capture = WebcamCapture.state
    var configuredInput by rememberSaveable { mutableStateOf("") }
    var inputLoaded by rememberSaveable { mutableStateOf(false) }
    var streamUrl by rememberSaveable { mutableStateOf("") }
    var pendingCamera by rememberSaveable { mutableStateOf("") }
    var choosingSize by rememberSaveable { mutableStateOf(false) }
    var choosingAspect by rememberSaveable { mutableStateOf(false) }
    var choosingQuality by rememberSaveable { mutableStateOf(false) }
    var choosingFps by rememberSaveable { mutableStateOf(false) }
    val webcamSize = Gadget.setupValue("webcam_size").ifEmpty { "1280" }
    val webcamAspect = Gadget.setupValue("webcam_aspect").ifEmpty { "auto" }
    val webcamQuality = Gadget.setupValue("webcam_quality").ifEmpty { "90" }
    val webcamFps = Gadget.setupValue("webcam_fps").ifEmpty { "30" }

    LaunchedEffect(configuredSource) {
        inputLoaded = false
        try {
            configuredInput = withContext(Dispatchers.IO) {
                if (configuredSource == "video" || configuredSource == "stream")
                    WebcamCapture.savedInput(context, configuredSource)
                else ""
            }
            if (configuredSource == "stream" && streamUrl.isEmpty()) streamUrl = configuredInput
        } catch (_: IOException) {
            captureFault(str(R.string.webcam_capture_failed))
        } finally {
            inputLoaded = true
        }
    }

    suspend fun activateSource(source: String, input: String, projectionResult: Int? = null, projectionData: Intent? = null) {
        Gadget.fault = null
        try {
            val output = WebcamCapture.start(
                context, source, input, webcamSize.toIntOrNull() ?: 1280, webcamAspect,
                webcamQuality.toIntOrNull() ?: 90, webcamFps.toIntOrNull() ?: 30,
                projectionResult, projectionData,
            )
            if (!WebcamCapture.awaitFrame(output)) {
                val message = str(WebcamCapture.errorOr(R.string.webcam_capture_timeout))
                WebcamCapture.stop(context)
                captureFault(message)
                return
            }
            withContext(Dispatchers.IO) { WebcamCapture.saveInput(context, source, input) }
            Gadget.set(
                "webcam_source" to source,
                "webcam_image" to output,
            )
        } catch (_: SecurityException) {
            WebcamCapture.stop(context)
            captureFault(str(R.string.webcam_capture_failed))
        } catch (_: IllegalStateException) {
            WebcamCapture.stop(context)
            captureFault(str(R.string.webcam_capture_failed))
        } catch (_: IOException) {
            WebcamCapture.stop(context)
            captureFault(str(R.string.webcam_capture_failed))
        }
    }

    val cameraPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) scope.launch { activateSource(pendingCamera, "") }
        else captureFault(str(R.string.webcam_camera_denied))
    }
    val screenPermission = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null)
            scope.launch { activateSource("screen", "", result.resultCode, result.data) }
    }
    val imagePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null && !webcamOn) {
            Gadget.fault = null
            WebcamCapture.stop(context)
            scope.launch {
                try {
                    val image = prepareWebcamImage(
                        context, uri, webcamSize.toIntOrNull() ?: 1280, webcamAspect,
                        webcamFps.toIntOrNull() ?: 30,
                        webcamQuality.toIntOrNull() ?: 90,
                    )
                    val copied = Root.change(
                        "mkdir -p $WORKSPACE/Gadget && cp -- ${shellQuote(image.absolutePath)} " +
                            "${shellQuote(WEBCAM_IMAGE)} && chmod 0644 ${shellQuote(WEBCAM_IMAGE)}",
                    )
                    if (!copied.ok) {
                        Gadget.fault = Fault(Fn.WEBCAM, copied.err.lastOrNull() ?: "[-] " + str(R.string.webcam_copy_failed))
                    } else {
                        Gadget.set(
                            "webcam_source" to "image",
                            "webcam_image" to WEBCAM_IMAGE,
                        )
                    }
                } catch (_: IOException) {
                    captureFault(str(R.string.webcam_bad_image))
                } catch (_: SecurityException) {
                    captureFault(str(R.string.webcam_bad_image))
                } catch (_: IllegalArgumentException) {
                    captureFault(str(R.string.webcam_bad_image))
                }
            }
        }
    }
    val videoPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null && !webcamOn) {
            try {
                context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                scope.launch { activateSource("video", uri.toString()) }
            } catch (_: SecurityException) {
                captureFault(str(R.string.webcam_capture_failed))
            }
        }
    }
    val fault = Gadget.fault?.takeIf { it.where == Fn.WEBCAM }
    val savedSource = configuredSource
    val savedInput = configuredInput
    val projectionManager = remember(context) { context.getSystemService(MediaProjectionManager::class.java) }

    fun requestCamera(source: String) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            scope.launch { activateSource(source, "") }
        } else {
            pendingCamera = source
            cameraPermission.launch(Manifest.permission.CAMERA)
        }
    }

    fun requestScreen() {
        screenPermission.launch(projectionManager.createScreenCaptureIntent())
    }

    fun resumeSource() {
        when {
            savedSource.startsWith("camera:") -> requestCamera(savedSource)
            savedSource == "screen" -> requestScreen()
            savedSource == "video" && savedInput.isNotEmpty() -> scope.launch { activateSource(savedSource, savedInput) }
            savedSource == "video" -> videoPicker.launch(arrayOf("video/*"))
            savedSource == "stream" && savedInput.isNotEmpty() -> scope.launch { activateSource(savedSource, savedInput) }
            savedSource == "stream" -> captureFault(str(R.string.webcam_bad_url))
        }
    }

    Screen(
        footer = {
            fault?.let { Fault(it.message, onLog = openLog, modifier = Modifier.opener("webcam/fault")) }
            if (configuredSource in LIVE_SOURCES && !webcamOn) {
                KeyRow {
                    if (capture.phase == WebcamCapturePhase.RUNNING) {
                        Key(
                            str(R.string.webcam_pause),
                            Modifier.fillMaxWidth().focusRequester(focusOn()).opener("webcam/pause"),
                            primary = true,
                        ) { WebcamCapture.stop(context) }
                    } else {
                        Key(
                            str(R.string.webcam_resume),
                            Modifier.fillMaxWidth().focusRequester(focusOn()).opener("webcam/resume"),
                            primary = true,
                            enabled = inputLoaded && capture.phase != WebcamCapturePhase.STARTING,
                        ) { resumeSource() }
                    }
                }
            }
        },
    ) {
        Top { BackKey() }
        Title(str(R.string.webcam_title))
        Row(
            Modifier.fillMaxWidth().heightIn(min = Lcd.touch),
            horizontalArrangement = Arrangement.spacedBy(Lcd.tight),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SpriteIcon(Sprites.Webcam, 3.dp)
            Column(verticalArrangement = Arrangement.spacedBy(Lcd.lines)) {
                Text(sourceName(configuredSource), LcdType.data)
                val frameWidth = webcamSize.toIntOrNull() ?: 1280
                val frameHeight = if (webcamAspect == "4:3") frameWidth * 3 / 4 else frameWidth * 9 / 16
                val frameFormat = if (webcamAspect == "auto") {
                    str(R.string.webcam_frame_format_auto, webcamSize, webcamFps)
                } else {
                    str(R.string.webcam_frame_format, frameWidth, frameHeight, webcamFps)
                }
                Text(frameFormat, LcdType.data)
            }
        }
        when {
            webcamOn -> Note(str(R.string.webcam_source_on_note))
            capture.phase == WebcamCapturePhase.STARTING -> Note(str(R.string.webcam_capture_starting))
            capture.phase == WebcamCapturePhase.RUNNING -> Note(str(R.string.webcam_capture_running))
            capture.phase == WebcamCapturePhase.ERROR -> Note(str(capture.error ?: R.string.webcam_capture_failed))
            configuredSource.isEmpty() -> Note(str(R.string.webcam_no_source))
            else -> Note(str(R.string.webcam_frame_note))
        }
        KeyRow {
            Key(str(R.string.webcam_setting_value, str(R.string.webcam_resolution), str(R.string.webcam_resolution_value, webcamSize)), Modifier.weight(1f).opener("webcam/size"), enabled = !webcamOn) { choosingSize = true }
            Key(str(R.string.webcam_setting_value, str(R.string.webcam_aspect), if (webcamAspect == "auto") str(R.string.webcam_aspect_auto).uppercase() else webcamAspect), Modifier.weight(1f).opener("webcam/aspect"), enabled = !webcamOn) { choosingAspect = true }
        }
        KeyRow {
            Key(str(R.string.webcam_setting_value, str(R.string.webcam_quality), str(R.string.webcam_quality_value, webcamQuality)), Modifier.weight(1f).opener("webcam/quality"), enabled = !webcamOn) { choosingQuality = true }
            Key(str(R.string.webcam_setting_value, str(R.string.webcam_fps), webcamFps), Modifier.weight(1f).opener("webcam/fps"), enabled = !webcamOn) { choosingFps = true }
        }
        KeyRow {
            Key(
                str(R.string.webcam_pick),
                Modifier.weight(1f).opener("webcam/picture"),
                enabled = !webcamOn,
            ) { imagePicker.launch(arrayOf("image/*")) }
            Key(
                str(R.string.webcam_pick_video),
                Modifier.weight(1f).opener("webcam/video"),
                enabled = !webcamOn,
            ) { videoPicker.launch(arrayOf("video/*")) }
        }
        KeyRow {
            Key(
                str(R.string.webcam_camera_front),
                Modifier.weight(1f).opener("webcam/front"),
                enabled = !webcamOn && capture.phase != WebcamCapturePhase.STARTING,
            ) { requestCamera("camera:front") }
            Key(
                str(R.string.webcam_camera_back),
                Modifier.weight(1f).opener("webcam/back"),
                enabled = !webcamOn && capture.phase != WebcamCapturePhase.STARTING,
            ) { requestCamera("camera:back") }
        }
        KeyRow {
            Key(
                str(R.string.webcam_screen),
                Modifier.fillMaxWidth().opener("webcam/screen"),
                enabled = !webcamOn && capture.phase != WebcamCapturePhase.STARTING,
            ) { requestScreen() }
        }
        Field(str(R.string.webcam_stream_label)) {
            Input(
                value = streamUrl,
                onChange = { if (it.length <= MAX_URL_LENGTH) streamUrl = it },
                placeholder = str(R.string.webcam_stream_placeholder),
                keyboard = KeyboardOptions(keyboardType = KeyboardType.Uri),
                enabled = !webcamOn,
            )
        }
        Note(str(R.string.webcam_stream_note))
        KeyRow {
            Key(
                str(R.string.webcam_stream_start),
                Modifier.fillMaxWidth().opener("webcam/stream"),
                primary = true,
                enabled = !webcamOn && capture.phase != WebcamCapturePhase.STARTING,
            ) {
                val url = streamUrl.trim()
                when {
                    url.length > MAX_URL_LENGTH -> captureFault(str(R.string.webcam_url_too_long))
                    !validWebcamStreamUrl(url) -> captureFault(str(R.string.webcam_bad_url))
                    else -> scope.launch { activateSource("stream", url) }
                }
            }
        }
    }
    UnappliedSheet(Fn.WEBCAM, back)
    ChoiceSheet(choosingSize, str(R.string.webcam_resolution), listOf("640", "1280", "1920"), webcamSize, "", {
            Gadget.set("webcam_size" to it)
            WebcamCapture.stop(context)
            choosingSize = false
    }, { choosingSize = false })
    ChoiceSheet(choosingAspect, str(R.string.webcam_aspect), listOf(str(R.string.webcam_aspect_auto), "4:3", "16:9"), if (webcamAspect == "auto") str(R.string.webcam_aspect_auto) else webcamAspect, "", {
            Gadget.set("webcam_aspect" to if (it == str(R.string.webcam_aspect_auto)) "auto" else it)
            WebcamCapture.stop(context)
            choosingAspect = false
    }, { choosingAspect = false })
    ChoiceSheet(choosingQuality, str(R.string.webcam_quality), listOf("30", "40", "50", "60", "70", "80", "90", "95"), webcamQuality, "", {
            Gadget.set("webcam_quality" to it)
            WebcamCapture.stop(context)
            choosingQuality = false
    }, { choosingQuality = false })
    ChoiceSheet(choosingFps, str(R.string.webcam_fps), listOf("15", "20", "24", "25", "30"), webcamFps, "", {
            Gadget.set("webcam_fps" to it)
            WebcamCapture.stop(context)
            choosingFps = false
    }, { choosingFps = false })
}

private val LIVE_SOURCES = setOf("camera:front", "camera:back", "screen", "video", "stream")

private fun sourceName(source: String): String = str(
    when (source) {
        "image", "" -> R.string.webcam_source_image
        "camera:front" -> R.string.webcam_source_front
        "camera:back" -> R.string.webcam_source_back
        "screen" -> R.string.webcam_source_screen
        "video" -> R.string.webcam_source_video
        "stream" -> R.string.webcam_source_stream
        else -> R.string.webcam_no_source
    },
)

private fun captureFault(message: String) {
    val line = "[-] $message"
    Gadget.fault = Fault(Fn.WEBCAM, line)
    Gadget.note(line)
}

internal fun validWebcamStreamUrl(value: String): Boolean {
    if (value.isBlank() || value.length > MAX_URL_LENGTH || value.any(Char::isISOControl)) return false
    val uri = runCatching { URI(value) }.getOrNull() ?: return false
    return uri.scheme?.lowercase() in setOf("http", "https", "rtsp") &&
        !uri.host.isNullOrBlank()
}

private suspend fun prepareWebcamImage(context: Context, uri: Uri, size: Int, aspect: String, fps: Int, quality: Int): File = withContext(Dispatchers.IO) {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        ?: throw IOException("The selected picture could not be opened")
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) throw IOException("The selected file is not a picture")
    var sample = 1
    while (maxOf(bounds.outWidth / sample, bounds.outHeight / sample) > size * 2) sample *= 2
    val source = context.contentResolver.openInputStream(uri)?.use {
        BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
    } ?: throw IOException("The selected picture could not be decoded")
    val ratio = when (aspect) {
        "4:3" -> 4f / 3f
        "16:9" -> 16f / 9f
        else -> if (source.width.toFloat() / source.height < 1.55f) 4f / 3f else 16f / 9f
    }
    val width = size
    val height = if (ratio < 1.5f) width * 3 / 4 else size * 9 / 16
    val output = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    try {
        val scale = maxOf(width.toFloat() / source.width, height.toFloat() / source.height)
        val scaledWidth = source.width * scale
        val scaledHeight = source.height * scale
        Canvas(output).apply {
            clipRect(0f, 0f, width.toFloat(), height.toFloat())
            drawBitmap(source, null, RectF((width - scaledWidth) / 2, (height - scaledHeight) / 2, (width + scaledWidth) / 2, (height + scaledHeight) / 2), Paint(Paint.FILTER_BITMAP_FLAG))
        }
        val file = File(context.cacheDir, "webcam.jpg")
        val bytes = java.io.ByteArrayOutputStream()
        var jpegQuality = quality
        do {
            bytes.reset()
            if (!output.compress(Bitmap.CompressFormat.JPEG, jpegQuality, bytes))
                throw IOException("JPEG conversion failed")
            jpegQuality -= 10
        } while (bytes.size() > 640 * 480 * 2 * 12 / fps && jpegQuality >= 35)
        if (bytes.size() > 640 * 480 * 2 * 12 / fps) throw IOException("The webcam frame is too large")
        file.writeBytes(bytes.toByteArray())
        file
    } finally {
        source.recycle()
        output.recycle()
    }
}
