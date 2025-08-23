package com.futanium.box

import android.os.Bundle
import android.view.WindowManager
import androidx.appcompat.app.AppCompatActivity
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.Player
import androidx.media3.common.MediaMetadata
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView

class PlayerActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_URL = "url"
        const val EXTRA_TITLE = "title"
        const val EXTRA_REFERER = "referer"
        const val EXTRA_USER_AGENT = "ua"
        const val EXTRA_SUBTITLE = "subtitle"
    }

    private lateinit var playerView: PlayerView
    private var player: ExoPlayer? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(R.layout.activity_player)

        // >>> garante que o playerView existe antes de usar
        playerView = findViewById(R.id.playerView)

        initPlayer()
    }

    private fun initPlayer() {
        val url = intent.getStringExtra(EXTRA_URL) ?: return
        val title = intent.getStringExtra(EXTRA_TITLE)
        val referer = intent.getStringExtra(EXTRA_REFERER)
        val ua = intent.getStringExtra(EXTRA_USER_AGENT)
        val subtitle = intent.getStringExtra(EXTRA_SUBTITLE)

        // Headers (UA/Referer)
        val httpFactory = DefaultHttpDataSource.Factory().apply {
            if (!ua.isNullOrBlank()) setUserAgent(ua)
            val headers = HashMap<String, String>()
            if (!referer.isNullOrBlank()) headers["Referer"] = referer
            if (headers.isNotEmpty()) setDefaultRequestProperties(headers)
        }

        // >>> construtor correto da MediaSourceFactory
        val exo = ExoPlayer.Builder(this)
            .setMediaSourceFactory(DefaultMediaSourceFactory(httpFactory))
            .build().also { playerView.player = it }
        player = exo

        val itemBuilder = MediaItem.Builder().setUri(url)

        // ajuda na detecção do tipo
        val lower = url.lowercase()
        when {
            lower.contains(".m3u8") -> itemBuilder.setMimeType(MimeTypes.APPLICATION_M3U8)
            lower.contains(".mpd")  -> itemBuilder.setMimeType(MimeTypes.APPLICATION_MPD)
        }

        if (!title.isNullOrBlank()) {
            itemBuilder.setMediaMetadata(
                MediaMetadata.Builder().setTitle(title).build()
            )
        }

        val mediaItem = itemBuilder.build()
        exo.setMediaItem(mediaItem)

        // legenda externa opcional (VTT)
        if (!subtitle.isNullOrBlank()) {
            val subCfg = MediaItem.SubtitleConfiguration.Builder(android.net.Uri.parse(subtitle))
                .setMimeType(MimeTypes.TEXT_VTT) // troque para APPLICATION_SUBRIP se for .srt
                .setLanguage("pt-BR")
                .build()
            exo.setMediaItem(
                mediaItem.buildUpon()
                    .setSubtitleConfigurations(listOf(subCfg))
                    .build()
            )
        }

        exo.prepare()
        exo.playWhenReady = true
        exo.addListener(object : Player.Listener {})
    }

    override fun onStop() {
        super.onStop()
        playerView.player = null
        player?.release()
        player = null
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }
}