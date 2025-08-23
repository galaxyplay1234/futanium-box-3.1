package com.futanium.box

import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import com.google.android.exoplayer2.ExoPlayer
import com.google.android.exoplayer2.MediaItem
import com.google.android.exoplayer2.MediaMetadata
import com.google.android.exoplayer2.ui.PlayerView
import com.google.android.exoplayer2.util.Util
import com.google.android.exoplayer2.source.MediaSource
import com.google.android.exoplayer2.source.ProgressiveMediaSource
import com.google.android.exoplayer2.source.hls.HlsMediaSource
import com.google.android.exoplayer2.source.dash.DashMediaSource
import com.google.android.exoplayer2.upstream.DefaultHttpDataSource
import com.google.android.exoplayer2.upstream.HttpDataSource

class PlayerActivity : AppCompatActivity() {

    private var player: ExoPlayer? = null
    private lateinit var playerView: PlayerView

    companion object {
        const val EXTRA_URL = "url"           // obrigatória
        const val EXTRA_REFERER = "referer"   // opcional
        const val EXTRA_USER_AGENT = "ua"     // opcional
        const val EXTRA_SUBTITLE = "subtitle" // opcional (vtt/srt)
        const val EXTRA_TITLE = "title"       // opcional
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // barras pretas / immersive
        window.navigationBarColor = Color.BLACK
        window.statusBarColor = Color.BLACK
        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE
          or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
          or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
          or View.SYSTEM_UI_FLAG_FULLSCREEN
          or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
          or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        )

        setContentView(R.layout.activity_player)
        playerView = findViewById(R.id.player_view)
        playerView.setShutterBackgroundColor(Color.BLACK)
        playerView.setControllerAutoShow(true)
    }

    private fun buildDataSourceFactory(
        referer: String?,
        ua: String?
    ): HttpDataSource.Factory {
        val defaultUA = ua ?: "Mozilla/5.0 (Linux; Android 13; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"
        val props = HashMap<String, String>().apply {
            put("User-Agent", defaultUA)
            if (!referer.isNullOrBlank()) put("Referer", referer)
            // alguns players pedem cabeçalhos extras:
            put("Accept", "*/*")
            put("Connection", "keep-alive")
        }
        return DefaultHttpDataSource.Factory()
            .setConnectTimeoutMs(15000)
            .setReadTimeoutMs(30000)
            .setAllowCrossProtocolRedirects(true)
            .setDefaultRequestProperties(props)
    }

    private fun buildMediaSource(
        url: String,
        referer: String?,
        ua: String?,
        subtitleUrl: String?
    ): MediaSource {
        val uri = Uri.parse(url)
        val last = uri.lastPathSegment?.lowercase() ?: ""
        val isHls = last.endsWith(".m3u8") || url.contains(".m3u8", true)
        val isDash = last.endsWith(".mpd") || url.contains(".mpd", true)

        val dsFactory = buildDataSourceFactory(referer, ua)

        val baseItem = MediaItem.Builder()
            .setUri(uri)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(intent.getStringExtra(EXTRA_TITLE) ?: "")
                    .build()
            )
            .build()

        // Legenda opcional
        val withSubtitle = if (!subtitleUrl.isNullOrBlank()) {
            val subItem = MediaItem.SubtitleConfiguration.Builder(Uri.parse(subtitleUrl))
                .setMimeType(
                    when {
                        subtitleUrl.endsWith(".vtt", true) -> "text/vtt"
                        subtitleUrl.endsWith(".srt", true) -> "application/x-subrip"
                        else -> "text/vtt"
                    }
                )
                .setSelectionFlags(0)
                .setLanguage("pt-BR")
                .build()
            baseItem.buildUpon().setSubtitleConfigurations(listOf(subItem)).build()
        } else baseItem

        return when {
            isHls  -> HlsMediaSource.Factory(dsFactory).createMediaSource(withSubtitle)
            isDash -> DashMediaSource.Factory(dsFactory).createMediaSource(withSubtitle)
            else   -> ProgressiveMediaSource.Factory(dsFactory).createMediaSource(withSubtitle)
        }
    }

    private fun initializePlayer() {
        val url = intent.getStringExtra(EXTRA_URL).orEmpty()
        if (url.isBlank()) {
            finish(); return
        }
        val referer = intent.getStringExtra(EXTRA_REFERER)
        val ua = intent.getStringExtra(EXTRA_USER_AGENT)
        val subtitle = intent.getStringExtra(EXTRA_SUBTITLE)

        player = ExoPlayer.Builder(this).build().also { exo ->
            playerView.player = exo
            val mediaSource = buildMediaSource(url, referer, ua, subtitle)
            exo.setMediaSource(mediaSource)
            exo.prepare()
            exo.playWhenReady = true
        }
    }

    public override fun onStart() {
        super.onStart()
        if (Util.SDK_INT >= 24) initializePlayer()
    }

    public override fun onResume() {
        super.onResume()
        hideSystemBars()
        if (Util.SDK_INT < 24 || player == null) initializePlayer()
    }

    public override fun onPause() {
        super.onPause()
        if (Util.SDK_INT < 24) releasePlayer()
    }

    public override fun onStop() {
        super.onStop()
        if (Util.SDK_INT >= 24) releasePlayer()
    }

    private fun releasePlayer() {
        playerView.player = null
        player?.release()
        player = null
    }

    private fun hideSystemBars() {
        if (Build.VERSION.SDK_INT >= 19) {
            window.decorView.systemUiVisibility = (
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE
              or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
              or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
              or View.SYSTEM_UI_FLAG_FULLSCREEN
              or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
              or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            )
        }
    }

    override fun onBackPressed() {
        // fecha o player imediatamente
        finish()
    }
}