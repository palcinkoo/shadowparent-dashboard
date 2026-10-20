package com.sp.core

import android.app.Activity
import android.content.Intent
import android.os.Bundle

/**
 * Second entry point.
 *
 * The launcher icon sits on the alias, so the icon can be hidden without
 * making the app unreachable - this exported activity is the way back in:
 * it forwards to the wizard and finishes immediately.
 */
class MainActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            startActivity(Intent(this, LauncherActivity::class.java))
        } catch (_: Throwable) {
        }
        finish()
    }
}
