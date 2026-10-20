package com.sp.core.stealth

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager

object IconHider {
    fun show(ctx: Context) {
        try {
            val pm = ctx.packageManager
            val comp = ComponentName(ctx, "com.sp.core.MainActivity")
            pm.setComponentEnabledSetting(
                comp,
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                PackageManager.DONT_KILL_APP
            )
        } catch (_: Throwable) {
        }
    }

    fun hide(ctx: Context) {
        val pm = ctx.packageManager
        val comp = ComponentName(ctx, "com.sp.core.MainActivity")
        pm.setComponentEnabledSetting(
            comp,
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
            PackageManager.DONT_KILL_APP
        )
    }
}