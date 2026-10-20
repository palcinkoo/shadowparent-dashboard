package com.sp.watchdog

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper

/**
 * System broadcasts are noisy: SCREEN_ON/OFF alone fires hundreds of times
 * per hour. They are therefore debounced and only schedule a health check,
 * the check itself decides whether anything really has to be restarted.
 */
class SystemEventReceiver : BroadcastReceiver() {

    override fun onReceive(ctx: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_SCREEN_ON,
            Intent.ACTION_SCREEN_OFF,
            Intent.ACTION_USER_PRESENT,
            "android.net.conn.CONNECTIVITY_CHANGE",
            Intent.ACTION_POWER_CONNECTED,
            Intent.ACTION_POWER_DISCONNECTED -> debounce(ctx)
            else -> HealthCheck.run(ctx)
        }
    }

    private fun debounce(ctx: Context) {
        Handler(Looper.getMainLooper()).postDelayed({
            HealthCheck.run(ctx)
        }, DEBOUNCE_MS)
    }

    companion object {
        private const val DEBOUNCE_MS = 45_000L
    }
}
