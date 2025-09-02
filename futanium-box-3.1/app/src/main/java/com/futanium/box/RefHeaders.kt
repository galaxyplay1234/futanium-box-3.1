package com.futanium.box

import android.net.Uri
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

object RefHeaders {
    private const val BLOCKLIST_URL =
        "https://raw.githubusercontent.com/galaxyplay1234/bloqueio-ads-futanium/refs/heads/main/blocklist.txt"

    val DEFAULT_UA =
        "Mozilla/5.0 (Linux; Android 13; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"

    private val client = OkHttpClient()
    private val loaded = AtomicBoolean(false)

    private val refRules = LinkedHashMap<String, String>() // chave: host ou substring; valor: referer
    private val uaRules  = LinkedHashMap<String, String>() // chave: host ou substring; valor: UA

    @Synchronized
    private fun ensureLoaded() {
        if (loaded.get()) return
        try {
            val req = Request.Builder().url(BLOCKLIST_URL).build()
            client.newCall(req).execute().use { res ->
                val body = res.body?.string().orEmpty()
                parse(body)
            }
        } catch (_: Exception) {
            // segue sem regras
        } finally {
            loaded.set(true)
        }
    }

    private fun parse(text: String) {
        refRules.clear(); uaRules.clear()
        text.lineSequence().forEach { raw ->
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("#")) return@forEach
            val lower = line.lowercase(Locale.ROOT)

            if (lower.startsWith("ref:")) {
                val rule = line.substringAfter("ref:", "").trim()
                val k = rule.substringBefore("=").trim()
                val v = rule.substringAfter("=", "").trim()
                if (k.isNotEmpty() && v.isNotEmpty()) refRules[k] = v
            } else if (lower.startsWith("ua:")) {
                val rule = line.substringAfter("ua:", "").trim()
                val k = rule.substringBefore("=").trim()
                val v = rule.substringAfter("=", "").trim()
                if (k.isNotEmpty() && v.isNotEmpty()) uaRules[k] = v
            }
        }
    }

    fun getForUrl(url: String): Map<String, String> {
        ensureLoaded()

        val uLower = url.lowercase(Locale.ROOT)
        val host = runCatching { Uri.parse(url).host?.lowercase(Locale.ROOT) }.getOrNull()
        var referer: String? = null
        var ua: String? = null

        // 1) match por host
        if (host != null) {
            refRules.forEach { (k, v) ->
                val key = k.lowercase(Locale.ROOT)
                if (host == key || host.endsWith(".$key")) { referer = v; return@forEach }
            }
            uaRules.forEach { (k, v) ->
                val key = k.lowercase(Locale.ROOT)
                if (host == key || host.endsWith(".$key")) { ua = v; return@forEach }
            }
        }

        // 2) fallback: substring
        if (referer == null) {
            refRules.forEach { (k, v) ->
                if (uLower.contains(k.lowercase(Locale.ROOT))) { referer = v; return@forEach }
            }
        }
        if (ua == null) {
            uaRules.forEach { (k, v) ->
                if (uLower.contains(k.lowercase(Locale.ROOT))) { ua = v; return@forEach }
            }
        }

        val headers = LinkedHashMap<String, String>()
        headers["User-Agent"] = ua ?: DEFAULT_UA
        headers["Accept"] = "*/*"
        headers["Connection"] = "keep-alive"

        referer?.let {
            headers["Referer"] = it
            runCatching { Uri.parse(it) }.getOrNull()?.let { r ->
                val scheme = r.scheme ?: "https"
                val h = r.host
                if (!h.isNullOrBlank()) headers["Origin"] = "$scheme://$h"
            }
        }
        return headers
    }
}
