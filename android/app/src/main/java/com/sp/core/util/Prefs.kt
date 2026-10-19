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

    fun get(ctx: Context, key: String, default: Any?): Any? {
        val s = sp(ctx)
        return when (default) {
            is String -> s.getString(key, default) ?: default
            is Boolean -> s.getBoolean(key, default)
            is Int -> s.getInt(key, default)
            is Long -> s.getLong(key, default)
            else -> s.getString(key, null) ?: default
        }
    }

    fun set(ctx: Context, key: String, value: Any?) {
        val e = sp(ctx).edit()
        when (value) {
            is Boolean -> e.putBoolean(key, value)
            is String -> e.putString(key, value)
            is Int -> e.putInt(key, value)
            is Long -> e.putLong(key, value)
            null -> e.remove(key)
        }
        e.apply()
    }
}
