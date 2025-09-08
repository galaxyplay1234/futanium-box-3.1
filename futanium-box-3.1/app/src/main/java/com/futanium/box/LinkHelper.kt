package com.futanium.box

import android.content.Context
import android.content.Intent

object LinkHelper {

    private fun isDirectMedia(url: String): Boolean {
        val u = url.lowercase()
        // extensões comuns de mídia + HLS/DASH
        return u.contains(".m3u8") ||
               u.contains(".mpd")  ||
               u.endsWith(".mp4")  ||
               u.endsWith(".webm") ||
               u.endsWith(".mkv")  ||
               u.endsWith(".mov")  ||
               u.endsWith(".m4v")  ||
               u.endsWith(".ts")
    }

    fun openLinkSmart(
        context: Context,
        url: String,
        title: String? = null,
        referer: String? = null,
        ua: String? = null
    ) {
        if (isDirectMedia(url)) {
            // Abre ExoPlayer
            val i = Intent(context, PlayerActivity::class.java).apply {
                putExtra(PlayerActivity.EXTRA_URL, url)
                if (!referer.isNullOrBlank()) putExtra(PlayerActivity.EXTRA_REFERER, referer)
                if (!ua.isNullOrBlank()) putExtra(PlayerActivity.EXTRA_USER_AGENT, ua)
                if (!title.isNullOrBlank()) putExtra(PlayerActivity.EXTRA_TITLE, title)
            }
            context.startActivity(i)
        } else {
          // Abre WebView
            val i = Intent(context, WebViewActivity::class.java).apply {
                putExtra(WebViewActivity.EXTRA_URL, url)
            }
context.startActivity(i)
        }
    }
}