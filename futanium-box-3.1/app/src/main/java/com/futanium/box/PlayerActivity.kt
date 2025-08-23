package com.futanium.box

import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView

class PlayerActivity : AppCompatActivity() {

    private lateinit var playerView: PlayerView
    private var player: ExoPlayer? = null
    private lateinit var insets: WindowInsetsControllerCompat

    private var retryCount = 0
    private val maxRetries = 3
    private val handler = Handler(Looper.getMainLooper())
    private var bufferingWatchdogPosted = false

    companion object {
        const val EXTRA_URL = "url"
        const val EXTRA_REFERER = "referer"
        const val EXTRA_USER_AGENT = "ua"
        const val EXTRA_TITLE = "title"
        const val EXTRA_SUBTITLE = "subtitle" // vtt/srt opcional
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Fullscreen imersivo + nav bar preta
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = Color.BLACK
        window.navigationBarColor = Color.BLACK
        insets = WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.statusBars() or WindowInsetsCompat.Type.navigationBars())
            systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            // Mantém ícones claros (brancos) sobre fundo preto
            isAppearanceLightNavigationBars = false
            isAppearanceLightStatusBars = false
        }

        setContentView(R.layout.activity_player)
        playerView = findViewById(R.id.playerView)

        initPlayer()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            insets.hide(WindowInsetsCompat.Type.statusBars() or WindowInsetsCompat.Type.navigationBars())
        }
    }

    private fun initPlayer() {
        val url = intent.getStringExtra(EXTRA_URL).orEmpty()
        val referer = intent.getStringExtra(EXTRA_REFERER)
        val ua = intent.getStringExtra(EXTRA_USER_AGENT)
        val title = intent.getStringExtra(EXTRA_TITLE)

        // DataSource com headers
        val dsFactory = DefaultHttpDataSource.Factory().apply {
            setAllowCrossProtocolRedirects(true)
            ua?.let { setUserAgent(it) }
            setDefaultRequestProperties(
                buildMap {
                    put("Accept", "*/*")
                    put("Connection", "keep-alive")
                    put("Accept-Language", "pt-BR,pt;q=0.9,en-US;q=0.8,en;q=0.7")
                    referer?.let { put("Referer", it) }
                }
            )
        }

        val mediaSourceFactory = DefaultMediaSourceFactory(dsFactory)
        val mediaItemBuilder = MediaItem.Builder().setUri(url)
        title?.let {
            mediaItemBuilder.setMediaMetadata(
                MediaMetadata.Builder().setTitle(it).build()
            )
        }
        val mediaItem = mediaItemBuilder.build()

        player = ExoPlayer.Builder(this)
            .setMediaSourceFactory(mediaSourceFactory)
            .build().also { p ->
                playerView.player = p
                p.setMediaItem(mediaItem)
                p.playWhenReady = true
                p.prepare()

                p.addListener(object : Player.Listener {
                    override fun onPlaybackStateChanged(state: Int) {
                        if (state == Player.STATE_BUFFERING) {
                            // watchdog: se ficar >12s em buffering, dá retry 1x
                            postBufferingWatchdog()
                        } else {
                            cancelBufferingWatchdog()
                        }
                    }

                    override fun onPlayerError(error: PlaybackException) {
                        tryRetry()
                    }
                })
            }
    }

    private fun tryRetry() {
        if (retryCount >= maxRetries) return
        retryCount++
        player?.let { p ->
            // Re-prepara e tenta novamente
            p.seekTo(0)
            p.prepare()
            p.playWhenReady = true
        }
    }

    private fun postBufferingWatchdog() {
        if (bufferingWatchdogPosted) return
        bufferingWatchdogPosted = true
        handler.postDelayed({
            bufferingWatchdogPosted = false
            if (player?.playbackState == Player.STATE_BUFFERING) {
                tryRetry()
            }
        }, 12_000)
    }

    private fun cancelBufferingWatchdog() {
        if (!bufferingWatchdogPosted) return
        bufferingWatchdogPosted = false
        handler.removeCallbacksAndMessages(null)
    }

    override fun onBackPressed() {
        finish() // fecha completamente o player
    }

    override fun onStop() {
        super.onStop()
        // se quiser que pare ao sair do app
        player?.playWhenReady = false
    }

    override fun onDestroy() {
        super.onDestroy()
        playerView.player = null
        player?.release()
        player = null
    }
}