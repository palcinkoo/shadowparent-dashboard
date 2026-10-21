package com.sp.core.services

import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import com.sp.core.services.LocationFix

/**
 * Compatibility shell - see [C2Service].
 *
 * The continuous foreground tracking was replaced by one-shot fixes that
 * the agent tick takes with the background location grant, so the device
 * shows no notification at all.
 */
class LocationService : Service() {

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        LocationFix.send(this, immediate = intent?.getBooleanExtra("now", false) == true)
        stopSelf()
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        /** Asks for an immediate position update. */
        fun requestNow(ctx: Context) {
            try { LocationFix.send(ctx.applicationContext, immediate = true) } catch (_: Throwable) {}
        }
    }
}
