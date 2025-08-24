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

        // opcionais (quando o .m3u8 veio de uma página)
        // usados para revalidar token: reabrimos a página e recapturamos o .m3u8
        const val EXTRA_SOURCE_PAGE_URL = "source_page_url"
        const val EXTRA_COOKIE = "extra_cookie"
    }

    private lateinit var playerView: PlayerView
    private var player: ExoPlayer? = null
    private lateinit var insets: WindowInsetsControllerCompat

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

    private val controllerHandler = Handler(Looper.getMainLooper())
    private val controllerAutoHide = Runnable { playerView.hideController() }
    private fun scheduleControllerAutoHide() {
        controllerHandler.removeCallbacks(controllerAutoHide)
        controllerHandler.postDelayed(controllerAutoHide, 3000)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = Color.BLACK
        window.navigationBarColor = Color.BLACK
        insets = WindowInsetsControllerCompat(window, window.decorView).apply {
            systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            isAppearanceLightStatusBars = false
            isAppearanceLightNavigationBars = false
        }
        hideStatusBar()

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        setContentView(R.layout.activity_player)
        playerView = findViewById(R.id.playerView)
        playerView.setBackgroundColor(Color.BLACK)
        playerView.clipToPadding = false

        playerView.setControllerShowTimeoutMs(0)
        playerView.setControllerHideOnTouch(true)
        playerView.setControllerAnimationEnabled(true)

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

        // ====== CENTRALIZAÇÃO + RODAPÉ ======
        ViewCompat.setOnApplyWindowInsetsListener(playerView) { _, ins ->
            val sys = ins.getInsets(WindowInsetsCompat.Type.systemBars())
            val side = maxOf(sys.left, sys.right)

            // laterais iguais; SEM padding no bottom para o controller “colar” no rodapé
            playerView.setPadding(side, playerView.paddingTop, side, 0)

            // leve ajuste nos tempos para dentro (simétrico)
            playerView.findViewById<View?>(androidx.media3.ui.R.id.exo_position)?.let { v ->
                v.setPadding(v.paddingLeft + dp(6), v.paddingTop, v.paddingRight, v.paddingBottom)
            }
            playerView.findViewById<View?>(androidx.media3.ui.R.id.exo_duration)?.let { v ->
                v.setPadding(v.paddingLeft, v.paddingTop, v.paddingRight + dp(6), v.paddingBottom)
            }

            // garante que o container do controller não ganhe “folga” extra embaixo
            playerView.findViewById<View?>(androidx.media3.ui.R.id.exo_controller)?.let { c ->
                c.setPadding(c.paddingLeft, c.paddingTop, c.paddingRight, 0)
            }
            ins
        }
        // ====================================

        // Spinner (retry) branco
        findViewById<android.widget.ProgressBar>(androidx.media3.ui.R.id.exo_buffering)?.let { pb ->
            val white = android.content.res.ColorStateList.valueOf(Color.WHITE)
            pb.indeterminateTintList = white
            pb.indeterminateTintMode = android.graphics.PorterDuff.Mode.SRC_IN
        }

        // Oculta botões que não queremos
        listOf(
            androidx.media3.ui.R.id.exo_prev,
            androidx.media3.ui.R.id.exo_next,
            androidx.media3.ui.R.id.exo_settings
        ).forEach { id ->
            playerView.findViewById<View?>(id)?.visibility = View.GONE
        }

        // ====== INTENT EXTRAS ======
        val url = intent.getStringExtra(EXTRA_URL).orEmpty()
        val title = intent.getStringExtra(EXTRA_TITLE).orEmpty()
        val referer = intent.getStringExtra(EXTRA_REFERER)
        val ua = intent.getStringExtra(EXTRA_USER_AGENT)
        val subtitleUrl = intent.getStringExtra(EXTRA_SUBTITLE)

        // usados para revalidar token (se expirou) e para enviar Cookie quando capturado pela WebView
        val sourcePageUrl = intent.getStringExtra(EXTRA_SOURCE_PAGE_URL)
        val cookieHeader = intent.getStringExtra(EXTRA_COOKIE)
        // ===========================

        // Se não for m3u8/ts, abre WebView em modo “extrair m3u8” e encerra o Player
        if (!isSupported(url)) {
            startActivity(Intent(this, WebViewActivity::class.java).apply {
                putExtra(WebViewActivity.EXTRA_URL, url)
                // flags genéricas (evita dependência de constantes novas na WebView)
                putExtra("extract_m3u8", true)
                putExtra("player_title", title)
            })
            finish()
            return
        }

        initPlayer(url, title, referer, ua, subtitleUrl, cookieHeader, sourcePageUrl)
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
        subtitleUrl: String?,
        cookieHeader: String?,
        sourcePageUrl: String?
    ) {
        val dsFactory = DefaultHttpDataSource.Factory().apply {
            setAllowCrossProtocolRedirects(true)
            ua?.let { setUserAgent(it) }
            setDefaultRequestProperties(buildMap {
                // Preferimos usar Origin coerente com a referer; se não tiver, gera a partir da URL
                if (!referer.isNullOrBlank()) {
                    put("Referer", referer)
                    runCatching { Uri.parse(referer) }.getOrNull()?.let { r ->
                        if (!r.scheme.isNullOrEmpty() && !r.host.isNullOrEmpty()) {
                            put("Origin", "${r.scheme}://${r.host}")
                        }
                    }
                }
                if (!containsKey("Origin")) {
                    put("Origin", Uri.parse(url).scheme + "://" + (Uri.parse(url).host ?: ""))
                }
                if (!cookieHeader.isNullOrBlank()) {
                    put("Cookie", cookieHeader)
                }
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

            override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                // Se o m3u8 expirou e sabemos a página de origem, reabra a WebView no modo EXTRACT
                if (!sourcePageUrl.isNullOrBlank()) {
                    startActivity(Intent(this@PlayerActivity, WebViewActivity::class.java).apply {
                        putExtra(WebViewActivity.EXTRA_URL, sourcePageUrl)
                        putExtra("extract_m3u8", true)
                        putExtra("player_title", title ?: "Play")
                    })
                    finish()
                }
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