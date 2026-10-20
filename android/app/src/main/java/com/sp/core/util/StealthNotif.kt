package com.sp.core.util

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import androidx.core.app.NotificationCompat

/**
 * One single silent channel for every foreground service.
 *
 * Android forces a foreground service to post a notification, but nothing has
 * to be visible: the channel is created with IMPORTANCE_NONE, so the entry
 * never reaches the status bar or the notification shade, and the lock screen
 * entry is marked as secret.
 *
 * The channel id is new on purpose - the importance of an existing channel
 * can not be lowered, so a fresh id is needed for the change to take effect
 * on devices that already ran an older build.
 */
object StealthNotif {

    const val CHANNEL_ID = "sp_fg_silent"

    fun channel(ctx: Context): String {
        try {
            val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val ch = NotificationChannel(
                CHANNEL_ID, "Система", NotificationManager.IMPORTANCE_NONE
            ).apply {
                setShowBadge(false)
                enableVibration(false)
                enableLights(false)
                setSound(null, null)
                lockscreenVisibility = Notification.VISIBILITY_SECRET
            }
            nm.createNotificationChannel(ch)
        } catch (_: Throwable) {
        }
        return CHANNEL_ID
    }

    fun build(ctx: Context, title: String, text: String): Notification {
        val id = channel(ctx)
        return NotificationCompat.Builder(ctx, id)
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setVisibility(NotificationCompat.VISIBILITY_SECRET)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setSilent(true)
            .setOngoing(true)
            .build()
    }
}
