package com.sp.core.stealth

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import com.sp.core.AdminReceiver

object HideFromSettings {
    fun hideViaDO(ctx: Context, packageName: String = ctx.packageName) {
        try {
            val dpm = ctx.getSystemService(Context.DEVICE_POLICY_SERVICE)
                    as DevicePolicyManager
            val admin = ComponentName(ctx, AdminReceiver::class.java)
            if (dpm.isDeviceOwnerApp(ctx.packageName)) {
                dpm.setApplicationHidden(admin, packageName, true)
            }
        } catch (_: Throwable) {}
    }
}