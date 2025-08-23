package com.futanium.box

import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
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

    // insets / barras
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // fullscreen: conteúdo por trás das barras
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = Color.BLACK
        window.navigationBarColor = Color.BLACK
        insets = WindowInsetsControllerCompat(window, window.decorView).apply {
            // ícones claros sobre fundo preto
            isAppearanceLightStatusBars = false
            isAppearanceLightNavigationBars = false
        }
        hideStatusBar()

        // manter tela ligada
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        setContentView(R.layout.activity_player)
        playerView = findViewById(R.id.playerView)

        // controller: mostrar 3s e esconder com toque; esconder tudo junto
        playerView.setControllerShowTimeoutMs(3000)
        playerView.setControllerHideOnTouch(true)
        playerView.setControllerVisibilityListener(
            PlayerView.ControllerVisibilityListener { visibility ->
                val controllerRoot =
                    playerView.findViewById<View>(androidx.media3.ui.R.id.exo_controller)
                controllerRoot?.visibility = if (visibility == View.VISIBLE) View.VISIBLE else View.GONE
            }
        )

        // spinner branco (retry/loading)
        (findViewById<ProgressBar>(androidx.media3.ui.R.id.exo_buffering))?.let { pb ->
            val white = android.content.res.ColorStateList.valueOf(Color.WHITE)
            pb.indeterminateTintList = white
            pb.indeterminateTintMode = android.graphics.PorterDuff.Mode.SRC_IN
        }

        // esconder botões indesejados
        listOf(
            androidx.media3.ui.R.id.exo_prev,
            androidx.media3.ui.R.id.exo_next,
            androidx.media3.ui.R.id.exo_settings
        ).forEach { id ->
            playerView.findViewById<View?>(id)?.visibility = View.GONE
        }

        // afastar levemente UI do canto direito (evita sobrepor a nav bar em aparelhos com gestos/botões)
        val controllerRoot =
            playerView.findViewById<FrameLayout?>(androidx.media3.ui.R.id.exo_controller)
        controllerRoot?.let { root ->
            // padding extra à direita
            root.setPadding(
                root.paddingLeft,
                root.paddingTop,
                root.paddingRight + 24.dp(),
                root.paddingBottom
            )
        }
        // também garante margem nos textos de tempo
        listOf(
            androidx.media3.ui.R.id.exo_position,
            androidx.media3.ui.R.id.exo_duration
        ).forEach { id ->
            (playerView.findViewById<TextView?>(id))?.let { tv ->
                tv.setPadding(tv.paddingLeft, tv.paddingTop, tv.paddingRight + 16.dp(), tv.paddingBottom)
            }
        }

        // ler extras
        val url = intent.getStringExtra(EXTRA_URL).orEmpty()
        val title = intent.getStringExtra(EXTRA_TITLE).orEmpty()
        val referer = intent.getStringExtra(EXTRA_REFERER)
        val ua = intent.getStringExtra(EXTRA_USER_AGENT)
        val subtitleUrl = intent.getStringExtra(EXTRA_SUBTITLE)

        // só aceita m3u8 e .ts; caso contrário, abre WebView sem toast
        if (!isSupported(url)) {
            startActivity(Intent(this, WebViewActivity::class.java).apply {
                putExtra(WebViewActivity.EXTRA_URL, url)
            })
            finish()
            return
        }

        initPlayer(url, title, referer, ua, subtitleUrl)
    }

    // Esconde status bar (só aparece se o usuário puxar)
    private fun hideStatusBar() {
        insets.hide(WindowInsetsCompat.Type.statusBars())
        // garante ícones claros nas barras
        insets.isAppearanceLightStatusBars = false
        insets.isAppearanceLightNavigationBars = false
    }

    private fun initPlayer(
        url: String,
        title: String?,
        referer: String?,
        ua: String?,
        subtitleUrl: String?
    ) {
        // DataSource com headers opcionais
        val dsFactory = DefaultHttpDataSource.Factory().apply {
            setAllowCrossProtocolRedirects(true)
            ua?.let { setUserAgent(it) }
            setDefaultRequestProperties(buildMap {
                if (!referer.isNullOrBlank()) put("Referer", referer)
                val u = Uri.parse(url)
                val origin = "${u.scheme}://${u.host ?: ""}"
                put("Origin", origin)
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
                .setMimeType("text/vtt") // para .srt: "application/x-subrip"
                .setLanguage("pt")
                .setSelectionFlags(0)
                .build()
            itemBuilder.setSubtitleConfigurations(listOf(sub))
        }

        p.setMediaItem(itemBuilder.build())
        p.prepare()
        p.playWhenReady = true

        // watchdog de buffering
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
        main.removeCallbacks(bufferingWatchdog)
        playerView.player = null
        player?.release()
        player = null
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        super.onDestroy()
    }

    // ---- helpers ----
    private fun Int.dp(): Int = (this * resources.displayMetrics.density).toInt()

    private fun isSupported(url: String): Boolean {
        val u = url.lowercase()
        return u.contains(".m3u8") || u.endsWith(".ts")
    }
}