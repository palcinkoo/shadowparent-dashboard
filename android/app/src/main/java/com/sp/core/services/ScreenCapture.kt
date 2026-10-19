package com.sp.core.services

import android.app.Service
import android.content.Intent
import android.os.IBinder

class ScreenCapture : Service() {
    override fun onBind(intent: Intent?): IBinder? = null
}
