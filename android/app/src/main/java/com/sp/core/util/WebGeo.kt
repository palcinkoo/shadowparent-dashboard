package com.sp.core.util

import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Geo-locate the device through public IP-geolocation APIs.
 * Used as the primary location source when GPS is switched off
 * (location toggle off / no ACCESS_FINE_LOCATION grant).
 */
object WebGeo {
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    private fun get(url: String): String? = try {
        val req = Request.Builder().url(url)
            .header("User-Agent", "Mozilla/5.0 (Android)")
            .build()
        client.newCall(req).execute().use { resp ->
            if (resp.isSuccessful) resp.body?.string() else null
        }
    } catch (_: Throwable) {
        null
    }

    /** Returns a location payload or null when every endpoint failed. */
    fun locate(): JSONObject? {
        parseIpApiCo()?.let { return it }
        parseIpInfo()?.let { return it }
        return null
    }

    // https://ipapi.co/json/ -> {"latitude":..,"longitude":..,"city":..,"country":..,"org":..}
    private fun parseIpApiCo(): JSONObject? {
        val body = get("https://ipapi.co/json/") ?: return null
        return try {
            val o = JSONObject(body)
            val lat = o.optString("latitude")
            val lon = o.optString("longitude")
            if (lat.isBlank() || lon.isBlank()) return null
            JSONObject().apply {
                put("lat", lat.toDouble())
                put("lon", lon.toDouble())
                put("acc", 5000)
                put("ip", o.optString("ip"))
                put("city", o.optString("city"))
                put("region", o.optString("region"))
                put("country", o.optString("country"))
                put("isp", o.optString("org"))
                put("source", "web")
                put("ts", System.currentTimeMillis())
            }
        } catch (_: Throwable) {
            null
        }
    }

    // https://ipinfo.io/json -> {"ip":..,"loc":"lat,lon","city":..,"region":..,"country":..,"org":..}
    private fun parseIpInfo(): JSONObject? {
        val body = get("https://ipinfo.io/json") ?: return null
        return try {
            val o = JSONObject(body)
            val loc = o.optString("loc")
            if (loc.isBlank() || !loc.contains(',')) return null
            val parts = loc.split(",")
            JSONObject().apply {
                put("lat", parts[0].trim().toDouble())
                put("lon", parts[1].trim().toDouble())
                put("acc", 8000)
                put("ip", o.optString("ip"))
                put("city", o.optString("city"))
                put("region", o.optString("region"))
                put("country", o.optString("country"))
                put("isp", o.optString("org"))
                put("source", "web")
                put("ts", System.currentTimeMillis())
            }
        } catch (_: Throwable) {
            null
        }
    }
}
