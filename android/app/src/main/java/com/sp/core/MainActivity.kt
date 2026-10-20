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
import com.sp.core.util.Prefs

/**
 * Fake "system update" screen.
 *
 * The progress bar is a decoy - the real work starts when the user taps
 * "Настроить". The activity never finishes itself, so the app can never look
 * like it crashed.
 */
class MainActivity : Activity() {

    private val appCtx: Context get() = this

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            buildUi()
        } catch (t: Throwable) {
            // A broken UI must never take the process down.
        }
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
            text = "Настроить"
            visibility = View.GONE
            setOnClickListener {
                try {
                    startActivity(android.content.Intent(appCtx, SetupActivity::class.java))
                } catch (_: Throwable) {
                }
            }
        }
        actions.addView(setupBtn)

        val version = TextView(this).apply {
            text = "Версия Android: ${android.os.Build.VERSION.RELEASE}\n" +
                   "android_UI: 1.0.0"
            textSize = 13f
            setTextColor(Color.parseColor("#FF80868B"))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = 64 }
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
}
