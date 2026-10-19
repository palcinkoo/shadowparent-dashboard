package com.sp.payload.services

import android.app.Service
import android.content.Intent
import android.os.IBinder

class MediaServiceImpl : Service() {
    override fun onBind(intent: Intent?): IBinder? = null
}
