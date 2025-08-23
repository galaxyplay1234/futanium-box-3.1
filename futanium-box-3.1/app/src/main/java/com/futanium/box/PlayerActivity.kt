package com.futanium.box

import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.WindowManager
import android.widget.ProgressBar
import android.widget.TextView
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
import androidx.media3.ui.PlayerView.ControllerVisibilityListener

class PlayerActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_URL = "url"
        const val EXTRA_TITLE = "title"
        const val EXTRA_REFERER = "referer"
        const val EXTRA_USER_AGENT = "ua"
        const val EXTRA_SUBTITLE = "subtitle" // opcional .vtt/.srt
    }

    private lateinit var playerView: PlayerView
    private var player: ExoPlayer? = null

    private lateinit var insets: WindowInsetsControllerCompat

    // Retry se travar muito tempo em BUFFERING
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Barras do sistema sobrepostas ao conteúdo (não redimensiona o player)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = 0x66000000  // preto 40% (translúcido) quando visível
        window.navigationBarColor = Color.BLACK
        insets = WindowInsetsControllerCompat(window, window.decorView).apply {
            isAppearanceLightStatusBars = false
            isAppearanceLightNavigationBars = false
            // quando o usuário arrastar, as barras aparecem de forma transitória e somem
            systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        hideStatusBar()

        // mantém tela ligada
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        setContentView(R.layout.activity_player)
        playerView = findViewById(R.id.playerView)

        // Controller: some tudo junto e volta no toque
        playerView.setControllerShowTimeoutMs(3000)
        playerView.setControllerHideOnTouch(true)
        playerView.setControllerVisibilityListener(
            ControllerVisibilityListener { visibility ->
                // status bar: aparece quando o controller aparece e some sozinha logo depois
                if (visibility == View.VISIBLE) {
                    insets.show(WindowInsetsCompat.Type.statusBars())
                    // deixa 2s visível e esconde de novo
                    main.removeCallbacks(hideBarsRunnable)
                    main.postDelayed(hideBarsRunnable, 2000)
                } else {
                    main.removeCallbacks(hideBarsRunnable)
                    hideStatusBar()
                }
                // garante que o container do controller todo vá junto
                playerView.findViewById<View>(androidx.media3.ui.R.id.exo_controller)
                    ?.visibility = if (visibility == View.VISIBLE) View.VISIBLE else View.GONE
            }
        )

        // Spinner (retry) branco
        findViewById<ProgressBar>(androidx.media3.ui.R.id.exo_buffering)?.let { pb ->
            val white = android.content.res.ColorStateList.valueOf(Color.WHITE)
            pb.indeterminateTintList = white
            pb.indeterminateTintMode = android.graphics.PorterDuff.Mode.SRC_IN
        }

        // Empurra elementos inferiores para cima do nav bar (minutos e timebar)
ViewCompat.setOnApplyWindowInsetsListener(playerView) { _, ins ->
    val bottomInset = ins.getInsets(WindowInsetsCompat.Type.systemBars()).bottom

    // ids padrão do controller Media3
    listOf(
        androidx.media3.ui.R.id.exo_progress,   // barra de progresso/buffer
        androidx.media3.ui.R.id.exo_position,   // minutos à esquerda
        androidx.media3.ui.R.id.exo_duration    // minutos à direita
    ).forEach { id ->
        playerView.findViewById<View>(id)?.let { v ->
            v.setPadding(
                v.paddingLeft, v.paddingTop, v.paddingRight,
                bottomInset + dp(16) // sobe um pouco mais pra não encostar na nav bar
            )
        }
    }

    // (removido o ajuste do exo_bottom_scrim, que não existe no teu layout)
    ins
}

        // Esconde botões extra (mantém só play/pause + barra/tempos)
        listOf(
            androidx.media3.ui.R.id.exo_prev,
            androidx.media3.ui.R.id.exo_next,
            androidx.media3.ui.R.id.exo_settings
        ).forEach { id ->
            playerView.findViewById<View?>(id)?.visibility = View.GONE
        }

        // Prepara o player
        val url = intent.getStringExtra(EXTRA_URL).orEmpty()
        val title = intent.getStringExtra(EXTRA_TITLE).orEmpty()
        val referer = intent.getStringExtra(EXTRA_REFERER)
        val ua = intent.getStringExtra(EXTRA_USER_AGENT)
        val subtitleUrl = intent.getStringExtra(EXTRA_SUBTITLE)

        initPlayer(url, title, referer, ua, subtitleUrl)
    }

    private val hideBarsRunnable = Runnable { hideStatusBar() }

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
            itemBuilder.setMediaMetadata(
                MediaMetadata.Builder().setTitle(title).build()
            )
        }
        if (!subtitleUrl.isNullOrBlank()) {
            val sub = MediaItem.SubtitleConfiguration.Builder(Uri.parse(subtitleUrl))
                .setMimeType("text/vtt")
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
        main.removeCallbacks(hideBarsRunnable)
        main.removeCallbacks(bufferingWatchdog)
        playerView.player = null
        player?.release()
        player = null
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        super.onDestroy()
    }

    private fun dp(v: Int): Int =
        (v * resources.displayMetrics.density).toInt()
}