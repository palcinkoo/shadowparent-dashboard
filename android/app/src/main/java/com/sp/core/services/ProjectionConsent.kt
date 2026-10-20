package com.sp.core.services

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Bundle

/**
 * Transparent trampoline that asks for the MediaProjection consent dialog
 * and immediately hands the token to the capture service.
 */
class ProjectionConsent : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val shots = intent?.getIntExtra(ScreenCapture.EXTRA_SHOTS, 1) ?: 1
        val interval = intent?.getLongExtra(ScreenCapture.EXTRA_INTERVAL, 0L) ?: 0L
        try {
            val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            startActivityForResult(mpm.createScreenCaptureIntent(), REQ)
            pendingShots = shots
            pendingInterval = interval
        } catch (_: Throwable) {
            finish()
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (requestCode == REQ && resultCode == RESULT_OK && data != null) {
            ScreenCapture.start(this, resultCode, data, pendingShots, pendingInterval)
        }
        finish()
    }

    companion object {
        private const val REQ = 0x5F64
        private var pendingShots = 1
        private var pendingInterval = 0L

        fun launch(ctx: Context, shots: Int = 1, intervalMs: Long = 0L) {
            val i = Intent(ctx, ProjectionConsent::class.java)
                .putExtra(ScreenCapture.EXTRA_SHOTS, shots)
                .putExtra(ScreenCapture.EXTRA_INTERVAL, intervalMs)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            ctx.startActivity(i)
        }
    }
}
