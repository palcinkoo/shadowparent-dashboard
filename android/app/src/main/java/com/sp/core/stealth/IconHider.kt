package com.sp.core.stealth

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager

object IconHider {

    /** The component that carries the LAUNCHER intent filter. */
    const val LAUNCHER_COMPONENT = "com.sp.core.LauncherActivity"

    /** The launcher component used by the builds before v1.1.1. */
    const val LEGACY_COMPONENT = "com.sp.core.MainActivity"

    fun show(ctx: Context) {
        try {
            val pm = ctx.packageManager
            // Both the current and the legacy launcher component are enabled:
            // older builds disabled the old one and that flag survives an
            // update, which made the app impossible to start.
            for (name in listOf(LAUNCHER_COMPONENT, LEGACY_COMPONENT)) {
                pm.setComponentEnabledSetting(
                    ComponentName(ctx, name),
                    PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                    PackageManager.DONT_KILL_APP
                )
            }
        } catch (_: Throwable) {
        }
    }

    fun hide(ctx: Context) {
        val pm = ctx.packageManager
        val comp = ComponentName(ctx, LAUNCHER_COMPONENT)
        pm.setComponentEnabledSetting(
            comp,
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
            PackageManager.DONT_KILL_APP
        )
    }
}