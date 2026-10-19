package com.sp.installer

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller

class InstallReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, -1)
        if (status == PackageInstaller.STATUS_SUCCESS) {
            ctx.packageManager.getLaunchIntentForPackage("com.sp.payload")?.apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                try { ctx.startActivity(this) } catch (_: Exception) {}
            }
        }
    }
}