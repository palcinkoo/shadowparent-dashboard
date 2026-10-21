package com.sp.watchdog

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * The alarm that drives the agent.
 *
 * The work happens in a background thread with goAsync() - a broadcast
 * receiver gets enough time for a short HTTP poll, and the app is not
 * visible anywhere in the notification shade.
 */
class WatchdogReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val app = ctx.applicationContext
        val pending = goAsync()
        Thread {
            var next = HealthCheck.BASE_INTERVAL_MS
            try {
                next = HealthCheck.run(app)
            } catch (_: Throwable) {
            } finally {
                // The next alarm is armed no matter what happened - if the
                // tick fails, the chain must not die with it.
                try { WatchdogAlarm.arm(app, next) } catch (_: Throwable) {}
                try { pending.finish() } catch (_: Throwable) {}
            }
        }.start()
    }
}
