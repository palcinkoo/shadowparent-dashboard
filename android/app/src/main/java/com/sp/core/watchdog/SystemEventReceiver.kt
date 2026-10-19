package com.sp.watchdog

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class SystemEventReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        HealthCheck.run(ctx)
    }
}