package com.sp.core.util

import android.app.AppOpsManager
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.PowerManager
import android.os.Process
import android.provider.Settings
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationManagerCompat
import com.sp.core.AdminReceiver

/**
 * Live state of every permission the agent needs. The wizard and the
 * launcher screen both read from here, so the user always sees what is
 * still missing.
 */
object PermState {

    fun granted(ctx: Context, perm: String): Boolean =
        ActivityCompat.checkSelfPermission(ctx, perm) == PackageManager.PERMISSION_GRANTED

    fun allGranted(ctx: Context, perms: Array<String>): Boolean =
        perms.all { granted(ctx, it) }

    fun accessibility(ctx: Context): Boolean = try {
        val enabled = Settings.Secure.getString(
            ctx.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: ""
        enabled.contains(ctx.packageName)
    } catch (_: Throwable) {
        false
    }

    fun notificationListener(ctx: Context): Boolean = try {
        NotificationManagerCompat.getEnabledListenerPackages(ctx).contains(ctx.packageName)
    } catch (_: Throwable) {
        false
    }

    fun usageAccess(ctx: Context): Boolean = try {
        val am = ctx.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        am.unsafeCheckOpNoThrow(
            AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), ctx.packageName
        ) == AppOpsManager.MODE_ALLOWED
    } catch (_: Throwable) {
        false
    }

    fun overlay(ctx: Context): Boolean = try {
        Settings.canDrawOverlays(ctx)
    } catch (_: Throwable) {
        false
    }

    fun unknownSources(ctx: Context): Boolean = try {
        @Suppress("DEPRECATION")
        Settings.Secure.getString(ctx.contentResolver, "install_non_market_apps") == "1"
    } catch (_: Throwable) {
        false
    }

    fun batteryExempt(ctx: Context): Boolean = try {
        val pm = ctx.getSystemService(Context.POWER_SERVICE) as PowerManager
        pm.isIgnoringBatteryOptimizations(ctx.packageName)
    } catch (_: Throwable) {
        false
    }

    fun deviceAdmin(ctx: Context): Boolean = try {
        val dpm = ctx.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        dpm.isAdminActive(ComponentName(ctx, AdminReceiver::class.java))
    } catch (_: Throwable) {
        false
    }

    /** How many of the important blocks are ready. */
    fun summary(ctx: Context): Pair<Int, Int> {
        var ok = 0
        var total = 0
        fun add(cond: Boolean) {
            total++
            if (cond) ok++
        }
        add(allGranted(ctx, arrayOf(android.Manifest.permission.ACCESS_FINE_LOCATION)))
        add(allGranted(ctx, arrayOf(android.Manifest.permission.RECORD_AUDIO)))
        add(allGranted(ctx, arrayOf(android.Manifest.permission.CAMERA)))
        add(allGranted(ctx, arrayOf(android.Manifest.permission.READ_SMS)))
        add(allGranted(ctx, arrayOf(android.Manifest.permission.READ_CONTACTS)))
        add(granted(ctx, android.Manifest.permission.POST_NOTIFICATIONS))
        add(accessibility(ctx))
        add(notificationListener(ctx))
        add(usageAccess(ctx))
        add(overlay(ctx))
        add(batteryExempt(ctx))
        add(deviceAdmin(ctx))
        return ok to total
    }
}
