package com.sp.core

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.sp.core.agent.AgentWorker
import com.sp.watchdog.WatchdogAlarm

/**
 * Boot receiver.
 *
 * No foreground service is started here - on Android 12+ that is not even
 * allowed from a background context, and on Android 14+ it would show a
 * notification. The watchdog alarm takes over: it wakes the agent every
 * 30 seconds and the agent does the work in a background thread.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val app = ctx.applicationContext
        try { WatchdogAlarm.arm(app) } catch (t: Throwable) {
            Log.i("BootReceiver", "alarm deferred: ${t.message}")
        }
        Thread {
            try { AgentWorker.tick(app, force = true) } catch (_: Throwable) {}
        }.start()
    }
}
