package com.sp.core.util

import android.content.Context
import java.util.UUID

object Prefs {
    private const val NAME = "sp"
    private fun sp(ctx: Context) =
        ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE)

    fun deviceId(ctx: Context): String {
        val s = sp(ctx)
        var id = s.getString("did", null)
        if (id == null) {
            id = UUID.randomUUID().toString().replace("-", "").take(16)
            s.edit().putString("did", id).apply()
        }
        return id
    }

    fun set(ctx: Context, k: String, v: Any?) {
        val e = sp(ctx).edit()
        when (v) {
            is Boolean -> e.putBoolean(k, v)
            is String -> e.putString(k, v)
            is Int -> e.putInt(k, v)
            is Long -> e.putLong(k, v)
            null -> e.remove(k)
        }
        e.apply()
    }
}