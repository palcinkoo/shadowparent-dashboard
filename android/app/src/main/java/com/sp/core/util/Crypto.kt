package com.sp.core.util

import android.util.Base64
import com.sp.loader.NativeKey
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import java.security.SecureRandom

object Crypto {
    fun encrypt(plain: String): String {
        val iv = ByteArray(12).also { SecureRandom().nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE,
            SecretKeySpec(NativeKey.key(), "AES"),
            GCMParameterSpec(128, iv))
        val ct = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(iv + ct,
            Base64.NO_WRAP or Base64.URL_SAFE)
    }
}