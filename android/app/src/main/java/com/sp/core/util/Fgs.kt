package com.sp.core.util

import android.app.Notification
import android.app.Service
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import android.util.Log

/**
 * Safe foreground-service start.
 *
 * A service that was started with startForegroundService() MUST call
 * startForeground() within a few seconds - otherwise Android kills the whole
 * process with ForegroundServiceDidNotStartInTimeException, which looks like
 * a random app crash on the target device.
 *
 * startForeground() also throws SecurityException when the service type needs
 * a runtime permission that was not granted (location, microphone, camera,
 * mediaProjection). This helper tries the requested type first and falls back
 * to a type that never needs a runtime grant, so the call always succeeds.
 */
object Fgs {

    private const val TAG = "Fgs"

    /** @return true when the service is running in the foreground. */
    fun start(
        service: Service,
        type: Int,
        notification: Notification,
        fallbackType: Int = if (Build.VERSION.SDK_INT >= 29)
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0
    ): Boolean {
        val order = if (fallbackType == type) intArrayOf(type) else intArrayOf(type, fallbackType)
        for (t in order) {
            try {
                if (Build.VERSION.SDK_INT >= 29) {
                    service.startForeground(0x5F62, notification, t)
                } else {
                    service.startForeground(0x5F62, notification)
                }
                return true
            } catch (t: Throwable) {
                Log.w(TAG, "startForeground(type=$t) failed: ${t.message}")
            }
        }
        return false
    }

    /** Starts a foreground service without letting a failure kill the app. */
    fun startService(ctx: Context, cls: Class<*>) {
        try {
            val i = android.content.Intent(ctx, cls)
            if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i) else ctx.startService(i)
        } catch (t: Throwable) {
            Log.i(TAG, "deferred start of ${cls.simpleName}: ${t.message}")
        }
    }
}
