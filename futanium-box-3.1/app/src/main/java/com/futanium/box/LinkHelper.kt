
package com.futanium.box

import android.content.Context
import android.content.Intent
import org.json.JSONObject
import java.util.Locale

object LinkHelper {

    private fun isDirectMedia(url: String): Boolean {

        val original = url.trim()

        // Verifica se o conteúdo é um JSON de configuração
        try {
            val json = JSONObject(original)

            val mediaUrl = json.optString("url", "").trim()
            val type = json.optString("type", "").trim()

            if (mediaUrl.isNotBlank()) {

                val media = mediaUrl.lowercase(Locale.ROOT)

                return type.equals("dash", true) ||
                       type.equals("hls", true) ||
                       media.contains(".m3u8") ||
                       media.contains(".mpd") ||
                       media.contains(".mp4") ||
                       media.contains(".webm") ||
                       media.contains(".mkv") ||
                       media.contains(".mov") ||
                       media.contains(".m4v") ||
                       media.contains(".ts")
            }

        } catch (_: Exception) {
            // Não é JSON: verifica como URL normal
        }

        val u = original.lowercase(Locale.ROOT)

        return u.contains(".m3u8") ||
               u.contains(".mpd") ||
               u.contains(".mp4") ||
               u.contains(".webm") ||
               u.contains(".mkv") ||
               u.contains(".mov") ||
               u.contains(".m4v") ||
               u.contains(".ts")
    }

    fun openLinkSmart(
        context: Context,
        url: String,
        title: String? = null,
        referer: String? = null,
        ua: String? = null
    ) {

        if (isDirectMedia(url)) {

            // Envia o JSON COMPLETO para o PlayerActivity
            val i = Intent(context, PlayerActivity::class.java).apply {

                putExtra(PlayerActivity.EXTRA_URL, url)

                if (!referer.isNullOrBlank())
                    putExtra(PlayerActivity.EXTRA_REFERER, referer)

                if (!ua.isNullOrBlank())
                    putExtra(PlayerActivity.EXTRA_USER_AGENT, ua)

                if (!title.isNullOrBlank())
                    putExtra(PlayerActivity.EXTRA_TITLE, title)
            }

            context.startActivity(i)

        } else {

            // Abre páginas comuns na WebView
            val i = Intent(context, WebViewActivity::class.java).apply {
                putExtra(WebViewActivity.EXTRA_URL, url)
            }

            context.startActivity(i)
        }
    }
}
