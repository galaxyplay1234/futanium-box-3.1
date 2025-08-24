package com.futanium.box

import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
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
    private lateinit var insets: WindowInsetsControllerCompat

    // watchdog de buffering
    private val main = Handler(Looper.getMainLooper())
    private var bufferingStartAt: Long = 0L
    private val bufferingWatchdog = object : Runnable {
        override fun run() {
            val p = player ?: return
            if (p.playbackState == Player.STATE_BUFFERING) {
                val elapsed = System.currentTimeMillis() - bufferingStartAt
                if (elapsed > 10_000) {
                    val pos = p.currentPosition
                    p.seekTo(pos.coerceAtLeast(0))
                    p.prepare()
                    p.playWhenReady = true
                    bufferingStartAt = System.currentTimeMillis()
                }
                main.postDelayed(this, 2000)
            }
        }
    }

    // Auto-hide manual (timebar some junto com todo mundo)
    private val controllerHandler = Handler(Looper.getMainLooper())
    private val controllerAutoHide = Runnable { playerView.hideController() }
    private fun scheduleControllerAutoHide() {
        controllerHandler.removeCallbacks(controllerAutoHide)
        controllerHandler.postDelayed(controllerAutoHide, 3000)
    }

    // ------- Centralização horizontal do controller (pillarbox) -------
    private var videoAspect: Float = 0f          // largura/altura do vídeo efetivo
    private var bottomInsetPx: Int = 0
    private var sidePadPx: Int = 0

    private fun computeSidePadding() {
        val w = playerView.width
        val h = playerView.height
        val aspect = videoAspect
        if (w <= 0 || h <= 0 || aspect <= 0f) {
            sidePadPx = 0
            applyControllerPadding()
            return
        }
        // conteúdo “fit”: conteúdo em largura = h * aspect (se menor que w -> faixas laterais)
        val contentWidth = (h * aspect)
        val pad = ((w - contentWidth) / 2f).coerceAtLeast(0f)
        sidePadPx = pad.toInt()
        applyControllerPadding()
    }

    private fun applyControllerPadding() {
        // aplica padding lateral ao container do controller
        val controller = playerView.findViewById<View?>(androidx.media3.ui.R.id.exo_controller)
        controller?.setPadding(
            sidePadPx,
            controller.paddingTop,
            sidePadPx,
            bottomInsetPx + dp(16)
        )
        // mantemos a barra e tempos sem paddings próprios extras (ficam dentro do container).
        val progress = playerView.findViewById<View?>(androidx.media3.ui.R.id.exo_progress)
        progress?.setPadding(progress.paddingLeft, progress.paddingTop, progress.paddingRight, 0)

        val pos = playerView.findViewById<View?>(androidx.media3.ui.R.id.exo_position)
        pos?.setPadding(pos.paddingLeft, pos.paddingTop, pos.paddingRight, 0)

        val dur = playerView.findViewById<View?>(androidx.media3.ui.R.id.exo_duration)
        dur?.setPadding(dur.paddingLeft, dur.paddingTop, dur.paddingRight, 0)
    }
    // ------------------------------------------------------------------

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = Color.BLACK
        window.navigationBarColor = Color.BLACK
        insets = WindowInsetsControllerCompat(window, window.decorView).apply {
            // barras só aparecem ao “puxar”
            systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            isAppearanceLightStatusBars = false
            isAppearanceLightNavigationBars = false
        }
        hideStatusBar()

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        setContentView(R.layout.activity_player)
        playerView = findViewById(R.id.playerView)

        // Desliga o timeout interno e controlamos manualmente
        playerView.setControllerShowTimeoutMs(0)
        playerView.setControllerHideOnTouch(true)
        playerView.setControllerAnimationEnabled(true)

        // qualquer toque esconde status bar e reagenda o auto-hide do controller
        playerView.setOnTouchListener { _, ev ->
            if (ev.action == MotionEvent.ACTION_DOWN || ev.action == MotionEvent.ACTION_UP) {
                hideStatusBar()
                scheduleControllerAutoHide()
            }
            false
        }
        playerView.setControllerVisibilityListener(
            PlayerView.ControllerVisibilityListener { visibility ->
                if (visibility == View.VISIBLE) scheduleControllerAutoHide()
                else controllerHandler.removeCallbacks(controllerAutoHide)
            }
        )

        // Inset inferior (não deixar nada por baixo dos botões)
        ViewCompat.setOnApplyWindowInsetsListener(playerView) { _, ins ->
            val bars = ins.getInsets(WindowInsetsCompat.Type.systemBars())
            bottomInsetPx = bars.bottom
            applyControllerPadding() // re-aplica com o novo inset
            ins
        }

        // Recalcula padding quando o PlayerView muda de tamanho (rotação, etc.)
        playerView.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> computeSidePadding() }

        // Spinner (retry) branco
        findViewById<android.widget.ProgressBar>(androidx.media3.ui.R.id.exo_buffering)?.let { pb ->
            val white = android.content.res.ColorStateList.valueOf(Color.WHITE)
            pb.indeterminateTintList = white
            pb.indeterminateTintMode = android.graphics.PorterDuff.Mode.SRC_IN
        }

        // Deixa só o play/pause (remove botões laterais)
        listOf(
            androidx.media3.ui.R.id.exo_rew,
            androidx.media3.ui.R.id.exo_ffwd,
            androidx.media3.ui.R.id.exo_prev,
            androidx.media3.ui.R.id.exo_next,
            androidx.media3.ui.R.id.exo_repeat_toggle,
            androidx.media3.ui.R.id.exo_shuffle,
            androidx.media3.ui.R.id.exo_fullscreen,
            androidx.media3.ui.R.id.exo_settings
        ).forEach { id ->
            playerView.findViewById<View?>(id)?.visibility = View.GONE
        }

        // Prepara o player (só m3u8 e .ts)
        val url = intent.getStringExtra(EXTRA_URL).orEmpty()
        val title = intent.getStringExtra(EXTRA_TITLE).orEmpty()
        val referer = intent.getStringExtra(EXTRA_REFERER)
        val ua = intent.getStringExtra(EXTRA_USER_AGENT)
        val subtitleUrl = intent.getStringExtra(EXTRA_SUBTITLE)

        // Se não for m3u8/ts, abre WebView e encerra o Player
        if (!isSupported(url)) {
            startActivity(Intent(this, WebViewActivity::class.java).apply {
                putExtra(WebViewActivity.EXTRA_URL, url)
            })
            finish()
            return
        }

        initPlayer(url, title, referer, ua, subtitleUrl)
    }

    private fun isSupported(u: String): Boolean {
        val s = u.lowercase()
        return s.contains(".m3u8") || s.endsWith(".ts")
    }

    private fun hideStatusBar() {
        insets.hide(WindowInsetsCompat.Type.statusBars())
        insets.isAppearanceLightNavigationBars = false
    }

    private fun initPlayer(
        url: String,
        title: String?,
        referer: String?,
        ua: String?,
        subtitleUrl: String?
    ) {
        val dsFactory = DefaultHttpDataSource.Factory().apply {
            setAllowCrossProtocolRedirects(true)
            ua?.let { setUserAgent(it) }
            setDefaultRequestProperties(buildMap {
                if (!referer.isNullOrBlank()) put("Referer", referer)
                put("Origin", Uri.parse(url).scheme + "://" + (Uri.parse(url).host ?: ""))
            })
        }

        val mediaSourceFactory = DefaultMediaSourceFactory(dsFactory)

        val p = ExoPlayer.Builder(this)
            .setMediaSourceFactory(mediaSourceFactory)
            .build()
            .also { playerView.player = it }

        val itemBuilder = MediaItem.Builder().setUri(url)
        if (!title.isNullOrBlank()) {
            itemBuilder.setMediaMetadata(MediaMetadata.Builder().setTitle(title).build())
        }
        if (!subtitleUrl.isNullOrBlank()) {
            val sub = MediaItem.SubtitleConfiguration.Builder(Uri.parse(subtitleUrl))
                .setMimeType("text/vtt") // para .srt use "application/x-subrip"
                .setLanguage("pt")
                .setSelectionFlags(0)
                .build()
            itemBuilder.setSubtitleConfigurations(listOf(sub))
        }

        p.setMediaItem(itemBuilder.build())
        p.prepare()
        p.playWhenReady = true

        p.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                when (state) {
                    Player.STATE_BUFFERING -> {
                        bufferingStartAt = System.currentTimeMillis()
                        main.removeCallbacks(bufferingWatchdog)
                        main.postDelayed(bufferingWatchdog, 2000)
                    }
                    Player.STATE_READY, Player.STATE_ENDED -> {
                        main.removeCallbacks(bufferingWatchdog)
                    }
                    Player.STATE_IDLE -> {
                        p.prepare()
                        p.playWhenReady = true
                    }
                }
            }

            override fun onVideoSizeChanged(videoSize: VideoSize) {
                // calcula aspect ratio efetivo (considerando pixelRatio)
                val ratio = if (videoSize.height == 0) 0f
                else (videoSize.width * (videoSize.pixelWidthHeightRatio.takeIf { it > 0f } ?: 1f)) /
                        videoSize.height.toFloat()
                videoAspect = ratio
                computeSidePadding()
            }
        })

        player = p
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideStatusBar()
    }

    override fun onBackPressed() {
        finish()
    }

    override fun onDestroy() {
        main.removeCallbacks(bufferingWatchdog)
        controllerHandler.removeCallbacks(controllerAutoHide)
        playerView.player = null
        player?.release()
        player = null
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        super.onDestroy()
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
}