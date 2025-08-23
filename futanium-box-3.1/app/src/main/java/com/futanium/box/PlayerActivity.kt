package com.futanium.box

import android.os.Bundle
import android.view.WindowManager
import androidx.appcompat.app.AppCompatActivity
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
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
        // Mantém a tela ligada durante a reprodução
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(R.layout.activity_player)

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
            if (!ua.isNullOrBlank()) {
                setUserAgent(ua)
            }
            val headers = HashMap<String, String>()
            if (!referer.isNullOrBlank()) headers["Referer"] = referer
            if (headers.isNotEmpty()) setDefaultRequestProperties(headers)
        }

        // Cria o player
        val exo = ExoPlayer.Builder(this)
            .setMediaSourceFactory(
                // passa a factory de HTTP para todas as fontes (HLS/DASH/Progressive)
                androidx.media3.exoplayer.source.DefaultMediaSourceFactory(this, httpFactory)
            )
            .build().also { playerView.player = it }
        player = exo

        // Monta o MediaItem (Media3 detecta HLS/DASH/MP4 automaticamente)
        val itemBuilder = MediaItem.Builder()
            .setUri(url)

        // Se quiser, ajude o detector para HLS/DASH:
        val lower = url.lowercase()
        when {
            lower.contains(".m3u8") -> itemBuilder.setMimeType(MimeTypes.APPLICATION_M3U8)
            lower.contains(".mpd")  -> itemBuilder.setMimeType(MimeTypes.APPLICATION_MPD)
        }

        if (!title.isNullOrBlank()) {
            itemBuilder.setMediaMetadata(
                androidx.media3.common.MediaMetadata.Builder()
                    .setTitle(title)
                    .build()
            )
        }

        val mediaItem = itemBuilder.build()
        exo.setMediaItem(mediaItem)

        // Legenda externa opcional (VTT/SRT)
        if (!subtitle.isNullOrBlank()) {
            val subItem = MediaItem.SubtitleConfiguration.Builder(android.net.Uri.parse(subtitle))
                .setMimeType(MimeTypes.TEXT_VTT) // ajuste se for SRT: MimeTypes.APPLICATION_SUBRIP
                .setLanguage("pt-BR")
                .setSelectionFlags(0)
                .build()
            exo.addMediaItem(
                mediaItem.buildUpon().setSubtitleConfigurations(listOf(subItem)).build()
            )
        }

        exo.prepare()
        exo.playWhenReady = true

        exo.addListener(object : Player.Listener {})
    }

    override fun onStop() {
        super.onStop()
        // Libera o player ao sair da tela
        playerView.player = null
        player?.release()
        player = null
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }
}