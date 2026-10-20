package com.sp.core.util

import android.content.Context
import android.os.BatteryManager

/** Battery level in percent (or -1 when it cannot be read). */
object Battery {
    fun percent(ctx: Context): Int {
        return try {
            val bm = ctx.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
            if (bm == null) -1
            else {
                val v = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
                if (v in 0..100) v else -1
            }
        } catch (_: Throwable) {
            -1
        }
    }
}
