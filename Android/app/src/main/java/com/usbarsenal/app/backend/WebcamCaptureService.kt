package com.usbarsenal.app.backend

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.DisplayMetrics
import android.view.WindowManager
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.exoplayer.ExoPlayer
import com.usbarsenal.app.R
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class WebcamCaptureService : LifecycleService() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var frameExecutor: ExecutorService? = null
    private var frameThread: HandlerThread? = null
    private var frameHandler: Handler? = null
    private var cameraProvider: ProcessCameraProvider? = null
    private var imageReader: ImageReader? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var projection: MediaProjection? = null
    private var player: ExoPlayer? = null
    private var monitorJob: kotlinx.coroutines.Job? = null
    @Volatile private var source = ""
    @Volatile private var outputPath = ""
    @Volatile private var rotation = 0
    @Volatile private var frameWidth = 640
    @Volatile private var frameHeight = 480
    @Volatile private var frameSize = 640
    @Volatile private var frameAspect = "auto"
    @Volatile private var jpegQuality = 85
    @Volatile private var frameIntervalMs = 33L
    @Volatile private var frameByteLimit = MAX_FRAME_BYTES
    private var lastFrameAt = 0L
    @Volatile private var captureFailed = false
    @Volatile private var captureEpoch = 0
    private var webcamSeen = false
    private var webcamMisses = 0

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        when (intent?.action) {
            ACTION_START -> startCapture(intent)
            ACTION_RELEASE -> {
                releaseCapture()
                stopSelf(startId)
            }
            ACTION_STOP_OUTPUT -> serviceScope.launch {
                val stopped = Root.change("$UA webcam stop")
                if (stopped.ok) {
                    releaseCapture()
                    stopSelf(startId)
                } else {
                    WebcamCapture.publish(
                        WebcamCaptureState(
                            WebcamCapturePhase.ERROR,
                            source,
                            outputPath,
                            R.string.webcam_capture_failed,
                        ),
                    )
                }
            }
        }
        return START_NOT_STICKY
    }

    private fun startCapture(intent: Intent) {
        releaseCapture()
        source = intent.getStringExtra(EXTRA_SOURCE).orEmpty()
        val input = intent.getStringExtra(EXTRA_INPUT).orEmpty()
        outputPath = intent.getStringExtra(EXTRA_OUTPUT).orEmpty()
        frameSize = intent.getIntExtra(EXTRA_SIZE, 1280).let {
            when { it >= 1920 -> 1920; it >= 1280 -> 1280; else -> 640 }
        }
        frameAspect = intent.getStringExtra(EXTRA_ASPECT).orEmpty().ifEmpty { "auto" }
        frameWidth = frameSize
        frameHeight = if (frameAspect == "4:3") frameSize * 3 / 4 else frameSize * 9 / 16
        jpegQuality = intent.getIntExtra(EXTRA_QUALITY, 85).coerceIn(30, 95)
        val fps = intent.getIntExtra(EXTRA_FPS, 30).coerceIn(15, 30)
        frameIntervalMs = 1000L / fps
        frameByteLimit = MAX_FRAME_BYTES * 12 / fps
        captureFailed = false
        val epoch = ++captureEpoch
        lastFrameAt = 0L
        webcamSeen = false
        webcamMisses = 0
        if (source.isEmpty() || outputPath.isEmpty()) {
            fail(R.string.webcam_capture_failed, epoch)
            return
        }
        WebcamCapture.publish(WebcamCaptureState(WebcamCapturePhase.STARTING, source, outputPath))
        try {
            enterForeground(source)
            frameThread = HandlerThread("webcam-frame").also { it.start() }
            frameHandler = Handler(frameThread!!.looper)
            frameExecutor = Executors.newSingleThreadExecutor()
            when {
                source.startsWith("camera:") -> startCamera(source, epoch)
                source == "screen" -> startScreen(intent, epoch)
                source == "video" || source == "stream" -> startPlayback(input, epoch)
                else -> fail(R.string.webcam_capture_failed, epoch)
            }
            monitorOutput(epoch)
        } catch (_: SecurityException) {
            fail(if (source.startsWith("camera:")) R.string.webcam_camera_denied else R.string.webcam_capture_failed, epoch)
        } catch (_: Exception) {
            fail(R.string.webcam_capture_failed, epoch)
        }
    }

    private fun startCamera(source: String, epoch: Int) {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED)
            throw SecurityException("Camera permission is not granted")
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            if (!isCurrent(epoch)) return@addListener
            try {
                val resolution = ResolutionSelector.Builder()
                    .setResolutionStrategy(ResolutionStrategy(android.util.Size(frameWidth, frameHeight), ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER))
                    .build()
                val analysis = ImageAnalysis.Builder()
                    .setResolutionSelector(resolution)
                    .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()
                analysis.setAnalyzer(frameExecutor!!) { proxy ->
                    try {
                        if (shouldEncode(epoch)) {
                            val bitmap = bitmapFromProxy(proxy)
                            writeFrame(bitmap, proxy.imageInfo.rotationDegrees, epoch)
                        }
                    } catch (_: Exception) {
                        fail(R.string.webcam_capture_failed, epoch)
                    } finally {
                        proxy.close()
                    }
                }
                cameraProvider = future.get()
                val selector = if (source == "camera:front") CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA
                cameraProvider!!.bindToLifecycle(this, selector, analysis)
            } catch (_: Exception) {
                fail(R.string.webcam_capture_failed, epoch)
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun startScreen(intent: Intent, epoch: Int) {
        val result = intent.getIntExtra(EXTRA_PROJECTION_RESULT, 0)
        val data = projectionData(intent) ?: throw SecurityException("Screen permission is missing")
        val manager = getSystemService(MediaProjectionManager::class.java)
        projection = manager.getMediaProjection(result, data)
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        getSystemService(WindowManager::class.java).defaultDisplay.getRealMetrics(metrics)
        val reader = ImageReader.newInstance(metrics.widthPixels, metrics.heightPixels, PixelFormat.RGBA_8888, 2)
        imageReader = reader
        reader.setOnImageAvailableListener({ images ->
            val image = images.acquireLatestImage() ?: return@setOnImageAvailableListener
            try {
                if (shouldEncode(epoch)) writeFrame(bitmapFromImage(image), 0, epoch)
            } catch (_: Exception) {
                fail(R.string.webcam_capture_failed, epoch)
            } finally {
                image.close()
            }
        }, frameHandler)
        projection!!.registerCallback(
            object : MediaProjection.Callback() {
                override fun onStop() {
                    fail(R.string.webcam_capture_failed, epoch)
                }
            },
            frameHandler,
        )
        virtualDisplay = projection!!.createVirtualDisplay(
            "USB Arsenal Webcam",
            metrics.widthPixels,
            metrics.heightPixels,
            metrics.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            reader.surface,
            null,
            frameHandler,
        )
    }

    private fun startPlayback(input: String, epoch: Int) {
        val uri = Uri.parse(input)
        val reader = ImageReader.newInstance(frameWidth, frameHeight, PixelFormat.RGBA_8888, 2)
        imageReader = reader
        reader.setOnImageAvailableListener({ images ->
            val image = images.acquireLatestImage() ?: return@setOnImageAvailableListener
            try {
                if (shouldEncode(epoch)) writeFrame(bitmapFromImage(image), rotation, epoch)
            } catch (_: Exception) {
                fail(R.string.webcam_capture_failed, epoch)
            } finally {
                image.close()
            }
        }, frameHandler)
        player = ExoPlayer.Builder(this).build().also { video ->
            video.repeatMode = Player.REPEAT_MODE_ONE
            video.volume = 0f
            video.setVideoSurface(reader.surface)
            video.addListener(
                object : Player.Listener {
                    @Suppress("DEPRECATION")
                    override fun onVideoSizeChanged(videoSize: VideoSize) {
                        if (isCurrent(epoch)) rotation = videoSize.unappliedRotationDegrees
                    }

                    override fun onPlayerError(error: PlaybackException) {
                        fail(R.string.webcam_capture_failed, epoch)
                    }
                },
            )
            val mime = when (uri.path?.substringAfterLast('.')?.lowercase()) {
                "m3u8" -> MimeTypes.APPLICATION_M3U8
                "mpd" -> MimeTypes.APPLICATION_MPD
                else -> null
            }
            video.setMediaItem(MediaItem.Builder().setUri(uri).setMimeType(mime).build())
            video.prepare()
            video.play()
        }
    }

    private fun monitorOutput(epoch: Int) {
        monitorJob?.cancel()
        monitorJob = serviceScope.launch {
            while (isActive && isCurrent(epoch)) {
                delay(4_000)
                val status = readStatus().getOrNull()
                if (!isCurrent(epoch)) return@launch
                if (status?.webcam != null) {
                    webcamSeen = true
                    webcamMisses = 0
                } else if (status != null && webcamSeen) {
                    webcamMisses++
                    if (webcamMisses >= 3) {
                        releaseCapture()
                        stopSelf()
                        return@launch
                    }
                }
            }
        }
    }

    private fun shouldEncode(epoch: Int): Boolean {
        val now = SystemClock.elapsedRealtime()
        if (now - lastFrameAt < frameIntervalMs || !isCurrent(epoch)) return false
        lastFrameAt = now
        return true
    }

    private fun writeFrame(input: Bitmap, rotationDegrees: Int, epoch: Int) {
        var oriented: Bitmap? = null
        var frame: Bitmap? = null
        try {
            if (!isCurrent(epoch)) return
            val rotated = if (rotationDegrees % 360 == 0) input else Bitmap.createBitmap(
                input,
                0,
                0,
                input.width,
                input.height,
                Matrix().apply { postRotate(rotationDegrees.toFloat()) },
                true,
            )
            oriented = rotated.takeIf { it !== input }
            val ratio = when (frameAspect) {
                "4:3" -> 4f / 3f
                "16:9" -> 16f / 9f
                else -> if (rotated.width.toFloat() / rotated.height < 1.55f) 4f / 3f else 16f / 9f
            }
            if (ratio < 1.5f) {
                frameWidth = frameSize
                frameHeight = frameWidth * 3 / 4
            } else {
                frameWidth = frameSize
                frameHeight = frameSize * 9 / 16
            }
            val normalized = Bitmap.createBitmap(frameWidth, frameHeight, Bitmap.Config.ARGB_8888)
            frame = normalized
            Canvas(normalized).apply {
                val scale = maxOf(frameWidth.toFloat() / rotated.width, frameHeight.toFloat() / rotated.height)
                val width = rotated.width * scale
                val height = rotated.height * scale
                val left = (frameWidth - width) / 2
                val top = (frameHeight - height) / 2
                val save = save()
                clipRect(0f, 0f, frameWidth.toFloat(), frameHeight.toFloat())
                drawBitmap(
                    rotated,
                    null,
                    RectF(left, top, left + width, top + height),
                    Paint(Paint.FILTER_BITMAP_FLAG),
                )
                restoreToCount(save)
            }
            val bytes = ByteArrayOutputStream()
            var quality = jpegQuality
            do {
                bytes.reset()
                if (!frame.compress(Bitmap.CompressFormat.JPEG, quality, bytes))
                    throw IOException("Could not encode a webcam frame")
                quality -= 10
            } while (bytes.size() > frameByteLimit && quality >= 35)
            if (bytes.size() > frameByteLimit) throw IOException("The webcam frame is too large")
            if (!isCurrent(epoch)) return

            val file = File(outputPath)
            if (!file.parentFile!!.isDirectory && !file.parentFile!!.mkdirs())
                throw IOException("Could not create the webcam frame directory")
            val temp = File(file.parentFile, "${file.name}.tmp")
            FileOutputStream(temp).use { stream ->
                bytes.writeTo(stream)
                stream.fd.sync()
            }
            android.system.Os.rename(temp.absolutePath, file.absolutePath)
            if (isCurrent(epoch)) WebcamCapture.publish(WebcamCaptureState(WebcamCapturePhase.RUNNING, source, outputPath))
        } finally {
            frame?.recycle()
            if (oriented != null && oriented !== input) oriented.recycle()
            input.recycle()
        }
    }

    private fun bitmapFromProxy(proxy: ImageProxy): Bitmap {
        val plane = proxy.planes.single()
        return rgbaBitmap(proxy.width, proxy.height, plane.rowStride, plane.pixelStride, plane.buffer)
    }

    private fun bitmapFromImage(image: Image): Bitmap {
        val plane = image.planes.single()
        return rgbaBitmap(image.width, image.height, plane.rowStride, plane.pixelStride, plane.buffer)
    }

    private fun rgbaBitmap(width: Int, height: Int, rowStride: Int, pixelStride: Int, buffer: ByteBuffer): Bitmap {
        val base = buffer.position()
        val pixels = IntArray(width * height)
        for (y in 0 until height) {
            val row = base + y * rowStride
            for (x in 0 until width) {
                val index = row + x * pixelStride
                val red = buffer.get(index).toInt() and 0xff
                val green = buffer.get(index + 1).toInt() and 0xff
                val blue = buffer.get(index + 2).toInt() and 0xff
                val alpha = buffer.get(index + 3).toInt() and 0xff
                pixels[y * width + x] = Color.argb(alpha, red, green, blue)
            }
        }
        return Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
    }

    private fun enterForeground(source: String) {
        val type = when {
            source.startsWith("camera:") -> ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
            source == "screen" -> ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            else -> ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
            startForeground(NOTIFICATION_ID, notification(), type)
        else
            startForeground(NOTIFICATION_ID, notification())
    }

    private fun notification(): Notification {
        val stop = PendingIntent.getService(
            this,
            1,
            Intent(this, WebcamCaptureService::class.java).setAction(ACTION_STOP_OUTPUT),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.presence_video_online)
            .setContentTitle(getString(R.string.webcam_title))
            .setContentText(getString(R.string.webcam_notification_running))
            .setOngoing(true)
            .addAction(0, getString(R.string.webcam_notification_stop), stop)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.webcam_notification_channel),
                NotificationManager.IMPORTANCE_LOW,
            )
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun fail(error: Int, epoch: Int? = null) {
        if (captureFailed || (epoch != null && !isCurrent(epoch))) return
        captureFailed = true
        captureEpoch++
        WebcamCapture.publish(WebcamCaptureState(WebcamCapturePhase.ERROR, source, outputPath, error))
        releaseResources()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun releaseCapture() {
        captureFailed = true
        captureEpoch++
        releaseResources()
        WebcamCapture.publish(WebcamCaptureState())
        stopForeground(STOP_FOREGROUND_REMOVE)
    }

    private fun isCurrent(epoch: Int) = epoch == captureEpoch && !captureFailed

    private fun releaseResources() {
        monitorJob?.cancel()
        monitorJob = null
        cameraProvider?.unbindAll()
        cameraProvider = null
        player?.clearVideoSurface()
        player?.release()
        player = null
        virtualDisplay?.release()
        virtualDisplay = null
        projection?.stop()
        projection = null
        imageReader?.setOnImageAvailableListener(null, null)
        imageReader?.close()
        imageReader = null
        frameExecutor?.shutdownNow()
        frameExecutor = null
        frameThread?.quitSafely()
        frameThread = null
        frameHandler = null
    }

    @Suppress("DEPRECATION")
    private fun projectionData(intent: Intent): Intent? =
        if (Build.VERSION.SDK_INT >= 33)
            intent.getParcelableExtra(EXTRA_PROJECTION_DATA, Intent::class.java)
        else
            intent.getParcelableExtra(EXTRA_PROJECTION_DATA)

    override fun onDestroy() {
        releaseResources()
        serviceScope.cancel()
        super.onDestroy()
    }

    companion object {
        const val ACTION_START = "com.usbarsenal.app.webcam.START"
        const val ACTION_RELEASE = "com.usbarsenal.app.webcam.RELEASE"
        const val ACTION_STOP_OUTPUT = "com.usbarsenal.app.webcam.STOP_OUTPUT"
        const val EXTRA_SOURCE = "source"
        const val EXTRA_INPUT = "input"
        const val EXTRA_OUTPUT = "output"
        const val EXTRA_SIZE = "size"
        const val EXTRA_ASPECT = "aspect"
        const val EXTRA_QUALITY = "quality"
        const val EXTRA_FPS = "fps"
        const val EXTRA_PROJECTION_RESULT = "projection_result"
        const val EXTRA_PROJECTION_DATA = "projection_data"
        private const val CHANNEL_ID = "webcam_capture"
        private const val NOTIFICATION_ID = 34
        private const val MAX_FRAME_BYTES = 640 * 480 * 2
    }
}
