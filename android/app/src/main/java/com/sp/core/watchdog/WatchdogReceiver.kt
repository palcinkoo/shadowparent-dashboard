package com.sp.watchdog

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class WatchdogReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        HealthCheck.run(ctx)
        WatchdogAlarm.arm(ctx)
    }
}