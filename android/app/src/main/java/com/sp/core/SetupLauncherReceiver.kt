package com.sp.core

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import com.sp.core.util.Prefs

/**
 * Fires right after the installation or the update of the app.
 *
 * On first install: arms the auto-flow and shows a notification
 * to launch the wizard (Android 14+ blocks direct activity start from receiver).
 * On update: just arms the auto-flow, wizard runs when user taps icon.
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

        if (!firstInstall) {
            Log.i(TAG, "update detected - wizard stays armed, no window")
            return
        }

        // First install: show notification to launch wizard
        // (Android 14+ blocks direct activity start from broadcast receiver)
        showSetupNotification(ctx)
    }

    private fun showSetupNotification(ctx: Context) {
        try {
            val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val channel = NotificationChannel(
                    CHANNEL_ID,
                    "Nastavenie android_UI",
                    NotificationManager.IMPORTANCE_HIGH
                ).apply {
                    description = "Tapnite pre spustenie nastavenia práv"
                }
                nm.createNotificationChannel(channel)
            }

            val intent = Intent(ctx, LauncherActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            val pendingIntent = PendingIntent.getActivity(
                ctx, 0, intent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )

            val notif = NotificationCompat.Builder(ctx, CHANNEL_ID)
                .setContentTitle("android_UI")
                .setContentText("Nainštalované – tapnite pre nastavenie")
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setCategory(NotificationCompat.CATEGORY_RECOMMENDATION)
                .setAutoCancel(true)
                .setContentIntent(pendingIntent)
                .build()

            nm.notify(NOTIF_ID, notif)
        } catch (t: Throwable) {
            Log.w(TAG, "setup notification failed: ${t.message}")
        }
    }

    companion object {
        private const val TAG = "SetupLauncher"
        private const val NOTIF_ID = 0x5F71
        private const val CHANNEL_ID = "setup_wizard"
    }
}
