package com.sp.payload

import android.content.Context
import android.content.Intent
import com.sp.payload.services.LocationServiceImpl

object Entry {
    @JvmStatic
    fun start(ctx: Context) {
        // Регистрируем impl-сервисы через stub-сервисы в основном APK.
        // Здесь можно запустить вспомогательные задачи.
        val i = Intent(ctx, LocationServiceImpl::class.java)
        // LocationServiceImpl — Service в payload APK, запускается отдельно
        // после установки payload APK через PayloadInstaller.
    }
}