package com.futanium.box

import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import androidx.media3.datasource.HttpDataSource
import com.google.firebase.database.FirebaseDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import org.json.JSONObject
import java.util.Locale
import java.util.UUID
import java.util.concurrent.TimeUnit
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.drm.DefaultDrmSessionManager
import androidx.media3.exoplayer.drm.DefaultDrmSessionManagerProvider
import androidx.media3.exoplayer.drm.FrameworkMediaDrm
import androidx.media3.exoplayer.drm.LocalMediaDrmCallback
import android.util.Base64
import org.json.JSONArray



@UnstableApi
class PlayerActivity : AppCompatActivity() {


    companion object {
        const val EXTRA_URL = "url"
        const val EXTRA_TITLE = "title"
        const val EXTRA_REFERER = "referer"
        const val EXTRA_USER_AGENT = "ua"
        const val EXTRA_SUBTITLE = "subtitle"
        const val EXTRA_COOKIE = "cookie"
        const val EXTRA_ORIGIN = "origin"
        const val EXTRA_DRM_LICENSE_URL = "drm_license_url"

        private const val TAG = "FUTANIUM_PLAYER"

        private const val EXTRA_TV_CHANNEL_NAMES = "tv_channel_names"
        private const val EXTRA_TV_CHANNEL_URLS = "tv_channel_urls"
        private const val EXTRA_TV_CHANNEL_CAPTURE = "tv_channel_capture"
        private const val EXTRA_TV_CHANNEL_ORDERS = "tv_channel_orders"
        private const val EXTRA_SELECTED_CHANNEL = "selected_channel_index"
    }

    private lateinit var playerView: PlayerView
    private var bufferingIndicator: ProgressBar? = null

    private var player: ExoPlayer? = null
    private lateinit var insets: WindowInsetsControllerCompat

    // WebView invisível usado somente quando Buscar M3U8 = SIM.
    private lateinit var captureWebView: WebView

    @Volatile
    private var captureStarted = false

    @Volatile
    private var manifestReceived = false

    private val main = Handler(Looper.getMainLooper())

    private var bufferingStartAt: Long = 0L

// =========================================================
// RECONEXÃO AUTOMÁTICA M3U8
// =========================================================

private var currentMediaItem: MediaItem? = null
private var isHlsStream = false
private var reconnectAttempts = 0
private var reconnectText: TextView? = null
private var reconnectRunnable: Runnable? = null

    private val bufferingWatchdog = object : Runnable {
    override fun run() {

        val p = player ?: return

        if (p.playbackState == Player.STATE_BUFFERING) {

            val elapsed =
                System.currentTimeMillis() - bufferingStartAt

            if (elapsed >= 15_000) {

                Log.w(TAG, "M3U8 travado. Iniciando reconexão.")

                scheduleReconnect()

            } else {

                main.postDelayed(this, 2000)
            }
        }
    }
}

    private var m3u8RefId: String? = null
    private var isM3u8Active = false

    private val controllerHandler = Handler(Looper.getMainLooper())

    private val controllerAutoHide = Runnable {
        if (::playerView.isInitialized) {
            playerView.hideController()
        }
    }

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    // =========================================================
    // CAPTURA INVISÍVEL DO MANIFESTO
    // =========================================================

    private fun isCaptureManifest(url: String): Boolean {
        return try {
            val uri = Uri.parse(url)
            val path = uri.path?.lowercase(Locale.ROOT).orEmpty()

            path.endsWith(".m3u8") || path.endsWith(".mpd")
        } catch (e: Exception) {
            false
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun startBackgroundCapture(pageUrl: String) {

        if (captureStarted || pageUrl.isBlank()) return

        captureStarted = true

        if (!::captureWebView.isInitialized) {
            Log.e(TAG, "WebView de captura não foi inicializado.")
            return
        }

        Log.i(TAG, "Iniciando captura invisível: $pageUrl")

        captureWebView.apply {

            visibility = View.VISIBLE
            alpha = 0f

            isClickable = false
            isFocusable = false
            isFocusableInTouchMode = false

            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.mediaPlaybackRequiresUserGesture = false
            settings.loadsImagesAutomatically = true
            settings.javaScriptCanOpenWindowsAutomatically = true
            settings.allowContentAccess = true
            settings.allowFileAccess = false

            settings.mixedContentMode =
                android.webkit.WebSettings.MIXED_CONTENT_ALWAYS_ALLOW

            currentUserAgent?.takeIf { it.isNotBlank() }?.let {
                settings.userAgentString = it
            }

            webChromeClient = WebChromeClient()

            webViewClient = object : WebViewClient() {

                override fun shouldOverrideUrlLoading(
                    view: WebView?,
                    request: WebResourceRequest?
                ): Boolean {
                    return false
                }

                override fun onPageFinished(
                    view: WebView?,
                    url: String?
                ) {
                    super.onPageFinished(view, url)

                    Log.i(TAG, "Página de captura carregada: $url")

                    // Tenta iniciar players HTML5 que exigem reprodução automática.
                    view?.evaluateJavascript(
                        """
                        (function() {
                            try {
                                var videos = document.querySelectorAll('video');
                                videos.forEach(function(video) {
                                    video.muted = true;
                                    video.setAttribute('muted', '');
                                    video.play().catch(function(){});
                                });
                            } catch(e) {}
                        })();
                        """.trimIndent(),
                        null
                    )
                }

                override fun shouldInterceptRequest(
                    view: WebView?,
                    request: WebResourceRequest?
                ): android.webkit.WebResourceResponse? {

                    if (request == null || manifestReceived) {
                        return null
                    }

                    val requestUrl = request.url.toString()

                    if (!isCaptureManifest(requestUrl)) {
                        return null
                    }

                    if (!request.method.equals("GET", true)) {
                        return null
                    }

                    val scheme = request.url.scheme.orEmpty()

                    if (scheme != "http" && scheme != "https") {
                        return null
                    }

                    val firstManifest = synchronized(this@PlayerActivity) {
                        if (manifestReceived) {
                            false
                        } else {
                            manifestReceived = true
                            true
                        }
                    }

                    if (!firstManifest) {
                        return null
                    }

                    val headers = request.requestHeaders

                    val capturedReferer =
                        headers.entries.firstOrNull {
                            it.key.equals("Referer", true)
                        }?.value
                            ?: currentReferer
                            ?: pageUrl

                    val capturedUserAgent =
                        headers.entries.firstOrNull {
                            it.key.equals("User-Agent", true)
                        }?.value
                            ?: currentUserAgent
                            ?: settings.userAgentString

                    val capturedOrigin =
                        headers.entries.firstOrNull {
                            it.key.equals("Origin", true)
                        }?.value
                            ?: currentOrigin

                    val capturedCookie =
                        headers.entries.firstOrNull {
                            it.key.equals("Cookie", true)
                        }?.value
                            ?: CookieManager.getInstance()
                                .getCookie(requestUrl)
                            ?: currentCookie

                    Log.i(TAG, "Manifesto encontrado: $requestUrl")

                    runOnUiThread {
                        if (!isFinishing && !isDestroyed) {
                            Log.i(TAG, "Entregando manifesto ao ExoPlayer.")

                            initPlayer(
                                requestUrl,
                                intent.getStringExtra(EXTRA_TITLE),
                                capturedReferer,
                                capturedUserAgent,
                                capturedCookie,
                                currentSubtitle,
                                capturedOrigin
                            )
                        }
                    }

                    // Não interrompe o carregamento original do WebView.
                    return null
                }
            }

            val pageHeaders = HashMap<String, String>()

            currentReferer?.takeIf { it.isNotBlank() }?.let {
                pageHeaders["Referer"] = it
            }

            currentUserAgent?.takeIf { it.isNotBlank() }?.let {
                pageHeaders["User-Agent"] = it
            }

            currentCookie?.takeIf { it.isNotBlank() }?.let {
                pageHeaders["Cookie"] = it
            }

            currentOrigin?.takeIf { it.isNotBlank() }?.let {
                pageHeaders["Origin"] = it
            }

            if (pageHeaders.isEmpty()) {
                loadUrl(pageUrl)
            } else {
                loadUrl(pageUrl, pageHeaders)
            }
        }
    }


// =========================================================
// CONFIGURAÇÃO DE CANAIS JSON
// =========================================================

private data class StreamConfig(
    val url: String,
    val type: String,
    val clearKey: String
)

private fun parseStreamConfig(value: String): StreamConfig? {
    return try {
        val json = JSONObject(value)

        val mediaUrl = json.optString("url", "").trim()
        val type = json.optString("type", "").trim()
        val clearKey = json.optString("clearkey", "").trim()

        if (mediaUrl.isBlank()) {
            null
        } else {
            StreamConfig(
                url = mediaUrl,
                type = type,
                clearKey = clearKey
            )
        }
    } catch (e: Exception) {
        null
    }
}


private fun buildClearKeyResponse(clearKey: String): ByteArray? {
    return try {
        val parts = clearKey.trim().split(":", limit = 2)

        if (parts.size != 2) return null

        val kid = parts[0].trim()
        val key = parts[1].trim()

        val hexRegex = Regex("^[0-9a-fA-F]{32}$")

        if (!hexRegex.matches(kid) || !hexRegex.matches(key)) {
            Log.e(TAG, "Formato ClearKey inválido.")
            return null
        }

        fun hexToBase64Url(hex: String): String {
            val bytes = hex.chunked(2)
                .map { it.toInt(16).toByte() }
                .toByteArray()

            return Base64.encodeToString(
                bytes,
                Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP
            )
        }

        val response = JSONObject().apply {
            put("keys", JSONArray().put(JSONObject().apply {
                put("kty", "oct")
                put("kid", hexToBase64Url(kid))
                put("k", hexToBase64Url(key))
            }))
            put("type", "temporary")
        }

        response.toString().toByteArray(Charsets.UTF_8)

    } catch (e: Exception) {
        Log.e(TAG, "Erro ao preparar ClearKey.", e)
        null
    }
}


    // =========================================================
    // CANAIS
    // =========================================================

    private data class TvChannel(
        val name: String,
        val url: String,
        val captureM3u8: Boolean
    )

    private val tvChannels = mutableListOf<TvChannel>()

    private var selectedChannelIndex = 0
    private var channelControlsVisible = false
    private var channelPanelVisible = false

    private var channelRoot: FrameLayout? = null
    private var channelGradient: View? = null
    private var channelBottom: LinearLayout? = null
    private var channelPanel: LinearLayout? = null
    private var channelListContainer: LinearLayout? = null

    private var gameTitleText: TextView? = null
    private var channelNameText: TextView? = null
    private var channelButton: TextView? = null

    private var currentReferer: String? = null
    private var currentUserAgent: String? = null
    private var currentCookie: String? = null
    private var currentOrigin: String? = null
    private var currentSubtitle: String? = null

    // =========================================================
    // FECHAMENTO AUTOMÁTICO DOS MENUS DA TV
    // =========================================================

    private val channelIdleHandler = Handler(Looper.getMainLooper())

    private val CHANNEL_IDLE_TIMEOUT = 5_000L

    private val channelControlsAutoHide = Runnable {
        if (channelControlsVisible && !channelPanelVisible) {
            hideChannelControls()
        }
    }

    private val channelPanelAutoHide = Runnable {
        if (channelPanelVisible) {
            closeChannelPanel()
        }
    }

    private fun scheduleChannelAutoHide() {

        channelIdleHandler.removeCallbacks(channelControlsAutoHide)
        channelIdleHandler.removeCallbacks(channelPanelAutoHide)

        when {
            channelPanelVisible -> {
                channelIdleHandler.postDelayed(
                    channelPanelAutoHide,
                    CHANNEL_IDLE_TIMEOUT
                )
            }

            channelControlsVisible -> {
                channelIdleHandler.postDelayed(
                    channelControlsAutoHide,
                    CHANNEL_IDLE_TIMEOUT
                )
            }
        }
    }

    private fun cancelChannelAutoHide() {
        channelIdleHandler.removeCallbacks(channelControlsAutoHide)
        channelIdleHandler.removeCallbacks(channelPanelAutoHide)
    }

    // =========================================================
    // FIREBASE
    // =========================================================

    private fun setM3u8Status(active: Boolean) {
        val db = FirebaseDatabase.getInstance(
            "https://futanium-web-default-rtdb.firebaseio.com/"
        )

        val m3u8Ref = db.getReference("m3u8")

        if (active) {
            if (isM3u8Active) return

            m3u8RefId = UUID.randomUUID().toString()

            m3u8Ref.child(m3u8RefId!!).setValue(true)
            m3u8Ref.child(m3u8RefId!!).onDisconnect().removeValue()

            isM3u8Active = true
        } else {
            m3u8RefId?.let {
                m3u8Ref.child(it).removeValue()
                m3u8RefId = null
            }

            isM3u8Active = false
        }
    }

    // =========================================================
    // CONTROLES
    // =========================================================

    private fun scheduleControllerAutoHide() {
        controllerHandler.removeCallbacks(controllerAutoHide)
        controllerHandler.postDelayed(controllerAutoHide, 3000)
    }

    // =========================================================
    // INTERFACE DOS CANAIS
    // =========================================================

    private fun createChannelBackground(
        color: Int,
        radius: Int
    ): GradientDrawable {
        return GradientDrawable().apply {
            setColor(color)
            cornerRadius = dp(radius).toFloat()
        }
    }

    private fun createChannelText(
        text: String,
        size: Float,
        color: Int,
        bold: Boolean = false
    ): TextView {
        return TextView(this).apply {
            this.text = text
            textSize = size
            setTextColor(color)
            gravity = Gravity.CENTER_VERTICAL

            if (bold) {
                setTypeface(null, android.graphics.Typeface.BOLD)
            }
        }
    }

    private fun readTvChannels() {
        tvChannels.clear()

        val names = intent.getStringArrayListExtra(EXTRA_TV_CHANNEL_NAMES)
        val urls = intent.getStringArrayListExtra(EXTRA_TV_CHANNEL_URLS)
        val captures = intent.getBooleanArrayExtra(EXTRA_TV_CHANNEL_CAPTURE)

        if (names == null || urls == null) return

        val total = minOf(names.size, urls.size)

        for (i in 0 until total) {
            val channelUrl = urls[i].trim()

            if (channelUrl.isNotBlank()) {
                tvChannels.add(
                    TvChannel(
                        name = names[i].ifBlank { "Canal ${i + 1}" },
                        url = channelUrl,
                        captureM3u8 = captures?.getOrNull(i) ?: false
                    )
                )
            }
        }

        selectedChannelIndex = intent.getIntExtra(
            EXTRA_SELECTED_CHANNEL,
            0
        ).coerceIn(
            0,
            (tvChannels.size - 1).coerceAtLeast(0)
        )
    }

    private fun setupChannelInterface() {
        if (tvChannels.isEmpty()) return

        channelRoot = playerView.parent as? FrameLayout ?: return

        channelGradient = View(this).apply {
            background = GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(
                    Color.TRANSPARENT,
                    Color.argb(245, 0, 0, 0)
                )
            )

            visibility = View.GONE
            isClickable = false
            isFocusable = false
        }

        channelRoot?.addView(
            channelGradient,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                dp(300),
                Gravity.BOTTOM
            )
        )

        channelBottom = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(8), 0, dp(8))
            setBackgroundColor(Color.TRANSPARENT)
            visibility = View.GONE
            elevation = dp(15).toFloat()
        }

        gameTitleText = createChannelText(
            intent.getStringExtra("game_title").orEmpty(),
            21f,
            Color.WHITE,
            true
        ).apply {
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        }

        channelNameText = createChannelText(
            "",
            17f,
            Color.LTGRAY,
            true
        )

        channelButton = createChannelText(
            "Canais",
            16f,
            Color.WHITE,
            true
        ).apply {
            gravity = Gravity.CENTER
            setPadding(dp(20), dp(10), dp(20), dp(10))

            background = createChannelBackground(
                Color.rgb(25, 111, 220),
                6
            )

            isFocusable = true
            isFocusableInTouchMode = true

            setOnClickListener {
                openChannelPanel()
            }
        }

        channelBottom?.addView(
            gameTitleText,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(34)
            )
        )

        channelBottom?.addView(
            channelNameText,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(32)
            )
        )

        channelBottom?.addView(
            channelButton,
            LinearLayout.LayoutParams(
                dp(150),
                dp(46)
            ).apply {
                topMargin = dp(8)
            }
        )

        channelRoot?.addView(
            channelBottom,
            FrameLayout.LayoutParams(
                dp(300),
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM or Gravity.START
            ).apply {
                leftMargin = dp(28)
                bottomMargin = dp(35)
            }
        )

        channelPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL

            setPadding(
                dp(18),
                dp(22),
                dp(18),
                dp(18)
            )

            background = createChannelBackground(
                Color.rgb(15, 17, 22),
                0
            )

            visibility = View.GONE
            elevation = dp(25).toFloat()
        }

        val panelTitle = createChannelText(
            "Canais",
            21f,
            Color.WHITE,
            true
        )

        channelPanel?.addView(
            panelTitle,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(48)
            )
        )

        val divider = View(this).apply {
            setBackgroundColor(Color.rgb(55, 59, 67))
        }

        channelPanel?.addView(
            divider,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(1)
            )
        )

        val scroll = ScrollView(this).apply {
            isFillViewport = true
            overScrollMode = View.OVER_SCROLL_NEVER
            descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
        }

        channelListContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            isFocusable = false
        }

        scroll.addView(channelListContainer)

        channelPanel?.addView(
            scroll,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
        )

        channelRoot?.addView(
            channelPanel,
            FrameLayout.LayoutParams(
                dp(330),
                FrameLayout.LayoutParams.MATCH_PARENT,
                Gravity.END
            )
        )

        updateChannelList()
    }

    private fun updateChannelList() {
        val container = channelListContainer ?: return

        container.removeAllViews()

        tvChannels.forEachIndexed { index, channel ->

            val item = TextView(this).apply {
                text = "Canal ${index + 1}"
                textSize = 15f
                setTextColor(Color.WHITE)

                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(14), 0, dp(10), 0)

                isFocusable = true
                isFocusableInTouchMode = true

                background = createChannelBackground(
                    if (index == selectedChannelIndex) {
                        Color.rgb(25, 111, 220)
                    } else {
                        Color.TRANSPARENT
                    },
                    6
                )

                setOnClickListener {
                    selectChannel(index)
                }
            }

            container.addView(
                item,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    dp(48)
                ).apply {
                    topMargin = dp(5)
                    bottomMargin = dp(5)
                }
            )
        }
    }

    private fun focusSelectedChannel() {
        val container = channelListContainer ?: return

        container.post {
            val item = container.getChildAt(selectedChannelIndex)

            item?.requestFocus()

            item?.let {
                val scroll = channelPanel?.getChildAt(2) as? ScrollView
                scroll?.smoothScrollTo(0, it.top)
            }
        }
    }

    // =========================================================
    // MOSTRAR CONTROLES
    // =========================================================

    private fun showChannelControls() {
        if (tvChannels.isEmpty()) return

        channelControlsVisible = true
        channelPanelVisible = false

        gameTitleText?.text =
            intent.getStringExtra("game_title").orEmpty()

        channelNameText?.text =
            "Canal ${selectedChannelIndex + 1}"

        channelGradient?.visibility = View.VISIBLE
        channelBottom?.visibility = View.VISIBLE

        channelButton?.requestFocus()

        controllerHandler.removeCallbacks(controllerAutoHide)

        scheduleChannelAutoHide()
    }

    // =========================================================
    // ABRIR PAINEL
    // =========================================================

    private fun openChannelPanel() {
        if (tvChannels.isEmpty()) return

        channelControlsVisible = true
        channelPanelVisible = true

        cancelChannelAutoHide()

        channelGradient?.visibility = View.GONE
        channelBottom?.visibility = View.GONE

        channelPanel?.visibility = View.VISIBLE

        updateChannelList()
        focusSelectedChannel()

        scheduleChannelAutoHide()
    }

    // =========================================================
    // FECHAR PAINEL
    // =========================================================

    private fun closeChannelPanel() {
        channelPanelVisible = false

        channelPanel?.visibility = View.GONE

        if (channelControlsVisible) {
            channelGradient?.visibility = View.VISIBLE
            channelBottom?.visibility = View.VISIBLE

            channelButton?.requestFocus()

            scheduleChannelAutoHide()
        } else {
            hideChannelControls()
        }
    }

    // =========================================================
    // ESCONDER CONTROLES
    // =========================================================

    private fun hideChannelControls() {
        cancelChannelAutoHide()

        channelControlsVisible = false
        channelPanelVisible = false

        channelGradient?.visibility = View.GONE
        channelBottom?.visibility = View.GONE
        channelPanel?.visibility = View.GONE

        playerView.requestFocus()
    }


    // =========================================================
    // TROCAR CANAL
    // =========================================================

    private fun selectChannel(index: Int) {
        if (index !in tvChannels.indices) return

        cancelChannelAutoHide()

        selectedChannelIndex = index

        val channel = tvChannels[index]

        // Link direto ou captura habilitada: abre o PlayerActivity.
        // Captura desabilitada: mantém o WebView normal.
        val directNative = isSupported(channel.url)
        val useCapture = channel.captureM3u8 && !directNative

        val destination = if (directNative || useCapture) {
            PlayerActivity::class.java
        } else {
            WebViewActivity::class.java
        }

        val nextIntent = Intent(this, destination).apply {

            putExtra(EXTRA_URL, channel.url)
            putExtra(EXTRA_TITLE, channel.name)
            putExtra("captureM3u8", channel.captureM3u8)

            putExtra(
                "game_title",
                intent.getStringExtra("game_title").orEmpty()
            )

            putStringArrayListExtra(
                EXTRA_TV_CHANNEL_NAMES,
                ArrayList(tvChannels.map { it.name })
            )

            putStringArrayListExtra(
                EXTRA_TV_CHANNEL_URLS,
                ArrayList(tvChannels.map { it.url })
            )

            putExtra(
                EXTRA_TV_CHANNEL_CAPTURE,
                BooleanArray(tvChannels.size) {
                    tvChannels[it].captureM3u8
                }
            )

            intent.getIntArrayExtra(EXTRA_TV_CHANNEL_ORDERS)?.let {
                putExtra(EXTRA_TV_CHANNEL_ORDERS, it)
            }

            putExtra(EXTRA_SELECTED_CHANNEL, index)

            if (directNative) {
                putExtra(EXTRA_REFERER, currentReferer)
                putExtra(EXTRA_USER_AGENT, currentUserAgent)
                putExtra(EXTRA_COOKIE, currentCookie)
                putExtra(EXTRA_ORIGIN, currentOrigin)
                putExtra(EXTRA_SUBTITLE, currentSubtitle)
            }
        }

        startActivity(nextIntent)
        finish()
    }

    // =========================================================
    // CONTROLE REMOTO
    // =========================================================

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action != KeyEvent.ACTION_DOWN) {
            return super.dispatchKeyEvent(event)
        }

        if (channelPanelVisible || channelControlsVisible) {
            scheduleChannelAutoHide()
        }

        when (event.keyCode) {

            KeyEvent.KEYCODE_BACK,
            KeyEvent.KEYCODE_ESCAPE -> {
                if (channelPanelVisible) {
                    closeChannelPanel()
                    return true
                }

                if (channelControlsVisible) {
                    hideChannelControls()
                    return true
                }
            }

            KeyEvent.KEYCODE_DPAD_UP -> {
                if (channelPanelVisible) {
                    selectedChannelIndex =
                        (selectedChannelIndex - 1).coerceAtLeast(0)

                    updateChannelList()
                    focusSelectedChannel()

                    return true
                }
            }

            KeyEvent.KEYCODE_DPAD_DOWN -> {
                if (channelPanelVisible) {
                    selectedChannelIndex =
                        (selectedChannelIndex + 1)
                            .coerceAtMost(tvChannels.lastIndex)

                    updateChannelList()
                    focusSelectedChannel()

                    return true
                }
            }

            KeyEvent.KEYCODE_DPAD_CENTER,
            KeyEvent.KEYCODE_ENTER,
            KeyEvent.KEYCODE_NUMPAD_ENTER -> {

                if (channelPanelVisible) {
                    selectChannel(selectedChannelIndex)
                    return true
                }

                if (channelControlsVisible) {
                    openChannelPanel()
                    return true
                }

                if (tvChannels.isNotEmpty()) {
                    showChannelControls()
                    return true
                }
            }
        }

        return super.dispatchKeyEvent(event)
    }

    // =========================================================
    // CRIAÇÃO DA TELA
    // =========================================================

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setM3u8Status(true)

        WindowCompat.setDecorFitsSystemWindows(window, false)

        window.statusBarColor = Color.BLACK
        window.navigationBarColor = Color.BLACK

        insets = WindowInsetsControllerCompat(
            window,
            window.decorView
        ).apply {
            systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE

            isAppearanceLightStatusBars = false
            isAppearanceLightNavigationBars = false
        }

        hideStatusBar()

        window.addFlags(
            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
        )

        setContentView(R.layout.activity_player)

        playerView = findViewById(R.id.playerView)
setupReconnectMessage()
captureWebView = findViewById(R.id.captureWebView)

bufferingIndicator = playerView.findViewById(
    androidx.media3.ui.R.id.exo_buffering
)

bufferingIndicator?.visibility = View.GONE

        readTvChannels()

        playerView.setBackgroundColor(Color.BLACK)
        playerView.clipToPadding = false

        playerView.isFocusable = true
        playerView.isFocusableInTouchMode = true

        playerView.setControllerShowTimeoutMs(0)
        playerView.setControllerHideOnTouch(true)
        playerView.setControllerAnimationEnabled(true)

        playerView.setOnTouchListener { _, ev ->
            if (
                ev.action == MotionEvent.ACTION_DOWN ||
                ev.action == MotionEvent.ACTION_UP
            ) {
                hideStatusBar()
                scheduleControllerAutoHide()
            }

            false
        }

        playerView.setControllerVisibilityListener(
            PlayerView.ControllerVisibilityListener { visibility ->
                if (visibility == View.VISIBLE) {
                    scheduleControllerAutoHide()
                } else {
                    controllerHandler.removeCallbacks(controllerAutoHide)
                }
            }
        )

        // =====================================================
        // CENTRALIZAÇÃO + RODAPÉ
        // =====================================================

        ViewCompat.setOnApplyWindowInsetsListener(playerView) { _, ins ->

            val bars = ins.getInsets(
                WindowInsetsCompat.Type.navigationBars() or
                    WindowInsetsCompat.Type.displayCutout()
            )

            val left = bars.left
            val right = bars.right
            val bottom = bars.bottom

            playerView.setPadding(left, 0, right, bottom)

            playerView.findViewById<View?>(
                androidx.media3.ui.R.id.exo_controller
            )?.apply {
                setPadding(
                    paddingLeft,
                    paddingTop,
                    paddingRight,
                    bottom
                )
            }

            playerView.findViewById<View?>(
                androidx.media3.ui.R.id.exo_position
            )?.apply {
                setPadding(
                    paddingLeft + dp(6),
                    paddingTop,
                    paddingRight,
                    paddingBottom
                )
            }

            playerView.findViewById<View?>(
                androidx.media3.ui.R.id.exo_duration
            )?.apply {
                setPadding(
                    paddingLeft,
                    paddingTop,
                    paddingRight + dp(6),
                    paddingBottom
                )
            }

            ins
        }

        // =====================================================
        // BUFFERING
        // =====================================================

        // =====================================================
// BUFFERING
// =====================================================

bufferingIndicator?.let { pb ->
    val white = android.content.res.ColorStateList.valueOf(Color.WHITE)

    pb.indeterminateTintList = white
    pb.indeterminateTintMode =
        android.graphics.PorterDuff.Mode.SRC_IN
}

        // =====================================================
        // DADOS DO PLAYER
        // =====================================================

        val originalUrl = intent.getStringExtra(EXTRA_URL).orEmpty()

val streamConfig = parseStreamConfig(originalUrl)

val url = streamConfig?.url ?: originalUrl
val clearKey = streamConfig?.clearKey.orEmpty()
        val title = intent.getStringExtra(EXTRA_TITLE).orEmpty()
        val referer = intent.getStringExtra(EXTRA_REFERER)
        val ua = intent.getStringExtra(EXTRA_USER_AGENT)
        val subtitleUrl = intent.getStringExtra(EXTRA_SUBTITLE)
        val cookie = intent.getStringExtra(EXTRA_COOKIE)
        val origin = intent.getStringExtra(EXTRA_ORIGIN)

        val captureEnabled =
            intent.getBooleanExtra("captureM3u8", false)

        currentReferer = referer
        currentUserAgent = ua
        currentCookie = cookie
        currentOrigin = origin
        currentSubtitle = subtitleUrl

        setupChannelInterface()

        // =====================================================
        // PÁGINA WEB COM CAPTURA HABILITADA
        // =====================================================

        if (!isSupported(url) && captureEnabled) {

    Log.i(TAG, "Captura M3U8 habilitada para este canal.")

    playerView.player = null
    playerView.setBackgroundColor(Color.BLACK)

    playerView.post {
        bufferingIndicator?.visibility = View.VISIBLE
    }

    startBackgroundCapture(url)

    return
}

        // =====================================================
        // PÁGINA WEB SEM CAPTURA
        // =====================================================

        if (!isSupported(url)) {

            Log.i(TAG, "Captura desabilitada. Abrindo WebView normal.")

            startActivity(
                Intent(this, WebViewActivity::class.java).apply {

                    putExtra(WebViewActivity.EXTRA_URL, url)

                    putExtra(
                        "game_title",
                        intent.getStringExtra("game_title").orEmpty()
                    )

                    putExtra("captureM3u8", false)

                    putStringArrayListExtra(
                        EXTRA_TV_CHANNEL_NAMES,
                        intent.getStringArrayListExtra(EXTRA_TV_CHANNEL_NAMES)
                    )

                    putStringArrayListExtra(
                        EXTRA_TV_CHANNEL_URLS,
                        intent.getStringArrayListExtra(EXTRA_TV_CHANNEL_URLS)
                    )

                    intent.getBooleanArrayExtra(
                        EXTRA_TV_CHANNEL_CAPTURE
                    )?.let {
                        putExtra(EXTRA_TV_CHANNEL_CAPTURE, it)
                    }

                    intent.getIntArrayExtra(
                        EXTRA_TV_CHANNEL_ORDERS
                    )?.let {
                        putExtra(EXTRA_TV_CHANNEL_ORDERS, it)
                    }

                    putExtra(EXTRA_SELECTED_CHANNEL, selectedChannelIndex)
                }
            )

            finish()
            return
        }

        // =====================================================
// LINK DIRETO M3U8 / MPD / JSON
// =====================================================

initPlayer(
    url,
    title,
    referer,
    ua,
    cookie,
    subtitleUrl,
    origin,
    clearKey
)
    }

    // =========================================================
    // IDENTIFICAÇÃO DO FORMATO
    // =========================================================

    private fun isSupported(u: String): Boolean {
    val config = parseStreamConfig(u)
    val mediaUrl = (config?.url ?: u).lowercase(Locale.ROOT)

    return mediaUrl.contains(".m3u8") ||
        mediaUrl.contains(".mpd") ||
        mediaUrl.contains(".ts") ||
        mediaUrl.contains(".txt") ||
        config?.type.equals("dash", ignoreCase = true) ||
        config?.type.equals("hls", ignoreCase = true)
}

private fun isMpd(u: String): Boolean {
    val config = parseStreamConfig(u)
    val mediaUrl = config?.url ?: u

    return mediaUrl.contains(".mpd", ignoreCase = true) ||
        config?.type.equals("dash", ignoreCase = true)
}

    private fun hideStatusBar() {
        insets.hide(WindowInsetsCompat.Type.statusBars())
        insets.isAppearanceLightNavigationBars = false
    }




    // =========================================================
    // MODELO DO DIAGNÓSTICO MPD
    // =========================================================

    private data class MpdAnalysis(
        val isDynamic: Boolean = false,
        val drmSystems: Set<String> = emptySet(),
        val hasCenc: Boolean = false,
        val hasPssh: Boolean = false,
        val defaultKids: Set<String> = emptySet(),
        val licenseUrl: String? = null,
        val mimeTypes: Set<String> = emptySet(),
        val codecs: Set<String> = emptySet(),
        val segmentUrls: Int = 0,
        val baseUrls: Int = 0
    )

    // =========================================================
    // ANALISADOR AUTOMÁTICO DO MANIFESTO MPD
    // =========================================================

    private suspend fun analyzeMpd(
        url: String,
        referer: String?,
        ua: String?,
        cookie: String?,
        origin: String?
    ): MpdAnalysis = withContext(Dispatchers.IO) {

        val requestBuilder = Request.Builder()
            .url(url)
            .header("Accept", "*/*")

        if (!referer.isNullOrBlank()) {
            requestBuilder.header("Referer", referer)
        }

        val effectiveOrigin = origin?.takeIf { it.isNotBlank() }
            ?: referer?.let {
                val refUri = Uri.parse(it)
                val scheme = refUri.scheme
                val host = refUri.host

                if (!scheme.isNullOrBlank() && !host.isNullOrBlank()) {
                    "$scheme://$host"
                } else {
                    null
                }
            }

        if (!effectiveOrigin.isNullOrBlank()) {
            requestBuilder.header("Origin", effectiveOrigin)
        }

        if (!ua.isNullOrBlank()) {
            requestBuilder.header("User-Agent", ua)
        }

        if (!cookie.isNullOrBlank()) {
            requestBuilder.header("Cookie", cookie)
        }

        val request = requestBuilder.build()

        httpClient.newCall(request).execute().use { response ->

            if (!response.isSuccessful) {
                throw IllegalStateException(
                    "HTTP ${response.code} ao consultar o MPD"
                )
            }

            val xml = response.body?.string()
                ?: throw IllegalStateException("Manifesto vazio")

            val factory = XmlPullParserFactory.newInstance()
            factory.isNamespaceAware = true

            val parser = factory.newPullParser()
            parser.setInput(xml.reader())

            var dynamic = false
            var cenc = false
            var pssh = false

            val drmSystems = mutableSetOf<String>()
            val kids = mutableSetOf<String>()
            val mimeTypes = mutableSetOf<String>()
            val codecs = mutableSetOf<String>()

            var licenseUrl: String? = null
            var segmentUrls = 0
            var baseUrls = 0

            var event = parser.eventType

            while (event != XmlPullParser.END_DOCUMENT) {

                if (event == XmlPullParser.START_TAG) {

                    val tag = parser.name.lowercase(Locale.ROOT)

                    when (tag) {

                        "mpd" -> {
                            dynamic = parser.getAttributeValue(
                                null,
                                "type"
                            )?.equals("dynamic", true) == true
                        }

                        "contentprotection" -> {

                            val scheme = parser.getAttributeValue(
                                null,
                                "schemeIdUri"
                            )?.lowercase(Locale.ROOT).orEmpty()

                            val value = parser.getAttributeValue(
                                null,
                                "value"
                            )

                            val kid = parser.getAttributeValue(
                                null,
                                "default_KID"
                            )

                            if (
                                scheme.contains(
                                    "urn:mpeg:dash:mp4protection"
                                )
                            ) {
                                cenc = true
                            }

                            if (scheme.contains("edef8ba9")) {
                                drmSystems.add("Widevine")
                            }

                            if (scheme.contains("9a04f079")) {
                                drmSystems.add("PlayReady")
                            }

                            if (scheme.contains("e2719d58")) {
                                drmSystems.add("ClearKey")
                            }

                            if (
                                scheme.contains("fairplay") ||
                                scheme.contains("fps")
                            ) {
                                drmSystems.add("FairPlay")
                            }

                            if (
                                !value.isNullOrBlank() &&
                                value.contains("cenc", true)
                            ) {
                                cenc = true
                            }

                            if (!kid.isNullOrBlank()) {
                                kids.add(kid)
                            }
                        }

                        "pssh" -> {
                            pssh = true
                        }

                        "laurl", "licenseurl", "license_url" -> {

                            val candidate =
                                parser.getAttributeValue(
                                    null,
                                    "licenseUrl"
                                ) ?: parser.getAttributeValue(
                                    null,
                                    "href"
                                )

                            if (!candidate.isNullOrBlank()) {
                                licenseUrl = candidate
                            } else {
                                val text = parser.nextText().trim()

                                if (
                                    text.startsWith("https://") ||
                                    text.startsWith("http://")
                                ) {
                                    licenseUrl = text
                                }
                            }
                        }

                        "representation" -> {

                            parser.getAttributeValue(
                                null,
                                "mimeType"
                            )?.let {
                                mimeTypes.add(it)
                            }

                            parser.getAttributeValue(
                                null,
                                "codecs"
                            )?.let {
                                codecs.add(it)
                            }
                        }

                        "adaptationset" -> {

                            parser.getAttributeValue(
                                null,
                                "mimeType"
                            )?.let {
                                mimeTypes.add(it)
                            }

                            parser.getAttributeValue(
                                null,
                                "codecs"
                            )?.let {
                                codecs.add(it)
                            }
                        }

                        "segmenturl", "segmenttemplate" -> {
                            segmentUrls++
                        }

                        "baseurl" -> {
                            baseUrls++
                        }
                    }
                }

                event = parser.next()
            }

            MpdAnalysis(
                isDynamic = dynamic,
                drmSystems = drmSystems,
                hasCenc = cenc,
                hasPssh = pssh,
                defaultKids = kids,
                licenseUrl = licenseUrl,
                mimeTypes = mimeTypes,
                codecs = codecs,
                segmentUrls = segmentUrls,
                baseUrls = baseUrls
            )
        }
    }

    // =========================================================
    // RELATÓRIO MPD
    // =========================================================

    private fun logMpdAnalysis(info: MpdAnalysis) {

        Log.i(TAG, "========== ANÁLISE MPD ==========")

        Log.i(
            TAG,
            "Tipo: ${if (info.isDynamic) "Dinâmico / Ao vivo" else "Estático / VOD"}"
        )

        Log.i(
            TAG,
            "DRM: ${
                if (info.drmSystems.isEmpty()) {
                    "Não identificado no manifesto"
                } else {
                    info.drmSystems.joinToString()
                }
            }"
        )

        Log.i(TAG, "CENC: ${info.hasCenc}")
        Log.i(TAG, "PSSH presente: ${info.hasPssh}")
        Log.i(TAG, "Default KID presente: ${info.defaultKids.isNotEmpty()}")

        Log.i(
            TAG,
            "Licença declarada: ${!info.licenseUrl.isNullOrBlank()}"
        )

        Log.i(TAG, "MIME types: ${info.mimeTypes}")
        Log.i(TAG, "Codecs: ${info.codecs}")
        Log.i(TAG, "Segment URLs/Templates: ${info.segmentUrls}")
        Log.i(TAG, "Base URLs: ${info.baseUrls}")

        Log.i(TAG, "================================")
    }


		// =========================================================
// MENSAGEM DE RECONEXÃO
// =========================================================

private fun setupReconnectMessage() {

    reconnectText = TextView(this).apply {

        text = "Reconectando…"
        textSize = 18f
        setTextColor(Color.WHITE)

        setPadding(
            dp(22),
            dp(12),
            dp(22),
            dp(12)
        )

        gravity = Gravity.CENTER

        // Sem fundo
        setBackgroundColor(Color.TRANSPARENT)

        visibility = View.GONE
        elevation = dp(40).toFloat()
    }

    playerView.addView(
        reconnectText,
        FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.CENTER
        ).apply {
            topMargin = dp(30)
        }
    )
}

// =========================================================
// ESCONDER MENSAGEM
// =========================================================

private fun hideReconnectMessage() {
    reconnectText?.visibility = View.GONE
}

// =========================================================
// AGENDAR RECONEXÃO
// =========================================================

private fun scheduleReconnect() {

    if (!isHlsStream || isFinishing || isDestroyed) return

    // Evita várias tentativas simultâneas.
    if (reconnectRunnable != null) return

    reconnectText?.visibility = View.VISIBLE

    reconnectAttempts++

    val delay = if (reconnectAttempts == 1) {
        3000L
    } else {
        5000L
    }

    Log.w(
        TAG,
        "Reconexão M3U8 #$reconnectAttempts em ${delay}ms"
    )

    val task = Runnable {

        reconnectRunnable = null

        if (isFinishing || isDestroyed) return@Runnable

        val p = player ?: return@Runnable
        val item = currentMediaItem ?: return@Runnable

        try {

            main.removeCallbacks(bufferingWatchdog)

            p.stop()
            p.clearMediaItems()

            p.setMediaItem(item)
            p.prepare()
            p.playWhenReady = true

            bufferingStartAt = System.currentTimeMillis()

            main.postDelayed(
                bufferingWatchdog,
                2000
            )

            Log.i(TAG, "Nova tentativa de reprodução iniciada.")

        } catch (e: Exception) {

            Log.e(TAG, "Erro ao tentar reconectar M3U8.", e)

            scheduleReconnect()
        }
    }

    reconnectRunnable = task
    main.postDelayed(task, delay)
}


    // =========================================================
    // CRIAÇÃO DO PLAYER
    // =========================================================

		


    private fun initPlayer(
    url: String,
    title: String?,
    referer: String?,
    ua: String?,
    cookie: String?,
    subtitleUrl: String?,
    origin: String?,
    clearKey: String = ""
) {



val normalizedStreamUrl = url.lowercase(Locale.ROOT)

isHlsStream =
    !isMpd(url) &&
    (
        normalizedStreamUrl.contains(".m3u8") ||
        normalizedStreamUrl.contains(".txt") ||
        parseStreamConfig(url)?.type.equals("hls", true)
    )

reconnectAttempts = 0

Log.i(TAG, "LINK ESCOLHIDO PARA REPRODUÇÃO: $url")

        // Evita criar dois ExoPlayers caso a captura seja chamada mais de uma vez.
        if (player != null) {
            Log.w(TAG, "Player já inicializado. Ignorando nova inicialização.")
            return
        }
					
				

        val requestHeaders = mutableMapOf<String, String>()

        if (!referer.isNullOrBlank()) {
            requestHeaders["Referer"] = referer
        }

        val effectiveOrigin = origin?.takeIf { it.isNotBlank() }
            ?: referer?.let {
                val refUri = Uri.parse(it)
                val scheme = refUri.scheme
                val host = refUri.host

                if (!scheme.isNullOrBlank() && !host.isNullOrBlank()) {
                    "$scheme://$host"
                } else {
                    null
                }
            }

        if (!effectiveOrigin.isNullOrBlank()) {
            requestHeaders["Origin"] = effectiveOrigin
        }

        if (!cookie.isNullOrBlank()) {
            requestHeaders["Cookie"] = cookie
        }

        if (!ua.isNullOrBlank()) {
            requestHeaders["User-Agent"] = ua
        }

        val dsFactory = DefaultHttpDataSource.Factory().apply {

    setAllowCrossProtocolRedirects(true)

    setConnectTimeoutMs(15_000)
    setReadTimeoutMs(20_000)

    if (!ua.isNullOrBlank()) {
        setUserAgent(ua)
    }

    setDefaultRequestProperties(requestHeaders)
}

        val clearKeyResponse = buildClearKeyResponse(clearKey)

val defaultDrmProvider = DefaultDrmSessionManagerProvider()

val localClearKeyManager = clearKeyResponse?.let { response ->

    DefaultDrmSessionManager.Builder()
        .setUuidAndExoMediaDrmProvider(
            C.CLEARKEY_UUID,
            FrameworkMediaDrm.DEFAULT_PROVIDER
        )
        .setMultiSession(false)
        .build(
            LocalMediaDrmCallback(response)
        )
}

val mediaSourceFactory = DefaultMediaSourceFactory(dsFactory)
    .setDrmSessionManagerProvider { mediaItem ->

        if (localClearKeyManager != null && isMpd(url)) {
            Log.i(TAG, "Utilizando ClearKey local.")
            localClearKeyManager
        } else {
            defaultDrmProvider.get(mediaItem)
        }
    }

val p = ExoPlayer.Builder(this)
            .setMediaSourceFactory(mediaSourceFactory)
            .build()
            .also {
                playerView.player = it
            }

        player = p

        p.addListener(object : Player.Listener {

            override fun onPlaybackStateChanged(state: Int) {

    when (state) {

        Player.STATE_BUFFERING -> {

            bufferingStartAt = System.currentTimeMillis()

            main.removeCallbacks(bufferingWatchdog)
            main.postDelayed(bufferingWatchdog, 2000)
        }

        Player.STATE_READY -> {

            bufferingIndicator?.visibility = View.GONE

            main.removeCallbacks(bufferingWatchdog)

            // Transmissão recuperada.
            main.removeCallbacks(
                reconnectRunnable ?: Runnable {}
            )

            reconnectRunnable = null
            reconnectAttempts = 0

            hideReconnectMessage()

            Log.i(TAG, "Transmissão recuperada.")
        }

        Player.STATE_ENDED -> {

            bufferingIndicator?.visibility = View.GONE
            main.removeCallbacks(bufferingWatchdog)
        }

        Player.STATE_IDLE -> {

            bufferingIndicator?.visibility = View.GONE
        }
    }
}

 
            override fun onPlayerError(error: PlaybackException) {

    main.removeCallbacks(bufferingWatchdog)
    bufferingIndicator?.visibility = View.GONE

    Log.e(
        TAG,
        "Erro de reprodução: ${error.errorCodeName}",
        error
    )

    // Procura o código HTTP real na cadeia de exceções.
    var cause: Throwable? = error
    var httpError: HttpDataSource.InvalidResponseCodeException? = null

    while (cause != null) {

        if (cause is HttpDataSource.InvalidResponseCodeException) {
            httpError = cause
            break
        }

        cause = cause.cause
    }

    if (httpError != null) {

        Log.e(TAG, "========== ERRO HTTP ==========")
        Log.e(TAG, "Status HTTP: ${httpError.responseCode}")
        Log.e(TAG, "Mensagem: ${httpError.responseMessage}")
        Log.e(TAG, "URL: ${httpError.dataSpec.uri}")
        Log.e(TAG, "Cabeçalhos: ${httpError.headerFields}")
        Log.e(TAG, "================================")

    } else {

        Log.e(TAG, "Nenhum código HTTP específico encontrado.")
    }

    
if (isHlsStream) {

    Log.w(TAG, "Erro no Player. Tentando reconectar.")

    scheduleReconnect()

} else {

    Log.w(TAG, "Falha na reprodução. Código: ${error.errorCodeName}")
}

            } // Fecha onPlayerError()
        }) // Fecha o addListener()

        // =====================================================
        // IDENTIFICAÇÃO AUTOMÁTICA + MPD
        // =====================================================

        lifecycleScope.launch {

            val normalizedUrl = url.lowercase(Locale.ROOT)

            val itemBuilder = MediaItem.Builder()
                .setUri(Uri.parse(url))

            when {
    isMpd(url) -> {
        itemBuilder.setMimeType(MimeTypes.APPLICATION_MPD)
    }

    normalizedUrl.contains(".m3u8") ||
        normalizedUrl.contains(".txt") -> {

        itemBuilder.setMimeType(MimeTypes.APPLICATION_M3U8)

        Log.i(TAG, "Playlist HLS/M3U8 identificada na URL")
    }
}
         

            if (!title.isNullOrBlank()) {
                itemBuilder.setMediaMetadata(
                    MediaMetadata.Builder()
                        .setTitle(title)
                        .build()
                )
            }

            if (!subtitleUrl.isNullOrBlank()) {
                val sub = MediaItem.SubtitleConfiguration.Builder(
                    Uri.parse(subtitleUrl)
                )
                    .setMimeType("text/vtt")
                    .setLanguage("pt")
                    .setSelectionFlags(0)
                    .build()

                itemBuilder.setSubtitleConfigurations(listOf(sub))
            }

            // =================================================
            // MPD: MANTIDO
            // =================================================

            if (isMpd(url)) {

                Log.i(TAG, "Iniciando análise automática do MPD")

                try {

                    val analysis = analyzeMpd(
                        url,
                        referer,
                        ua,
                        cookie,
                        origin
                    )

                    logMpdAnalysis(analysis)

                    // ClearKey recebido no JSON: KID:KEY
if (clearKeyResponse != null) {

    Log.i(TAG, "ClearKey local preparado com sucesso.")

    val drmConfiguration =
        MediaItem.DrmConfiguration.Builder(C.CLEARKEY_UUID)
            .build()

    itemBuilder.setDrmConfiguration(drmConfiguration)

} else {

    // Mantém o funcionamento anterior para licenças externas.
    val externalLicense =
        intent.getStringExtra(EXTRA_DRM_LICENSE_URL)

    val license = externalLicense ?: analysis.licenseUrl

    val drmUuid = when {
        analysis.drmSystems.contains("Widevine") ->
            C.WIDEVINE_UUID

        analysis.drmSystems.contains("ClearKey") ->
            C.CLEARKEY_UUID

        else -> null
    }

    if (drmUuid != null && !license.isNullOrBlank()) {

        val drmConfiguration =
            MediaItem.DrmConfiguration.Builder(drmUuid)
                .setLicenseUri(Uri.parse(license))
                .setLicenseRequestHeaders(requestHeaders.toMutableMap())
                .build()

        itemBuilder.setDrmConfiguration(drmConfiguration)

        Log.i(TAG, "DRM com licença externa configurado.")

    } else if (analysis.drmSystems.isNotEmpty()) {

        Log.w(TAG, "DRM identificado, mas sem chave local ou licença disponível.")

    } else {

        Log.i(TAG, "Nenhum sistema DRM identificado no MPD.")
    }
}

                } catch (e: Exception) {

                    Log.e(
                        TAG,
                        "Não foi possível analisar o MPD. Tentando reprodução normal.",
                        e
                    )
                }
            }

            if (isFinishing || isDestroyed) return@launch

            currentMediaItem = itemBuilder.build()

p.setMediaItem(currentMediaItem!!)
p.prepare()
p.playWhenReady = true
        }
    }

    


    // =========================================================
    // CICLO DE VIDA
    // =========================================================

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)

        if (hasFocus && ::insets.isInitialized) {
            hideStatusBar()
        }
    }

    
@Deprecated("Deprecated in Android")
override fun onBackPressed() {

    if (channelPanelVisible) {
        closeChannelPanel()
        return
    }

    if (channelControlsVisible) {
        hideChannelControls()
        return
    }

    closePlayerCompletely()
}

private fun closePlayerCompletely() {

    // Cancela reconexões e tarefas pendentes.
    reconnectRunnable?.let {
        main.removeCallbacks(it)
    }

    reconnectRunnable = null

    main.removeCallbacks(bufferingWatchdog)
    controllerHandler.removeCallbacks(controllerAutoHide)
    cancelChannelAutoHide()

    // Para o vídeo e o áudio imediatamente.
    // Mantém a imagem durante a transição de saída.
    player?.apply {
        playWhenReady = false
        pause()
    }

    // Interrompe a captura invisível.
    if (::captureWebView.isInitialized) {
        captureWebView.onPause()
        captureWebView.stopLoading()
    }

    // Atualiza o contador no Firebase.
    setM3u8Status(false)

    window.clearFlags(
        WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
    )

    finish()
}


    override fun onDestroy() {

    reconnectRunnable?.let {
        main.removeCallbacks(it)
    }

    reconnectRunnable = null

    main.removeCallbacks(bufferingWatchdog)
    controllerHandler.removeCallbacks(controllerAutoHide)

    reconnectText = null
    currentMediaItem = null

    cancelChannelAutoHide()

    if (::captureWebView.isInitialized) {
        captureWebView.stopLoading()
        captureWebView.removeAllViews()
        captureWebView.destroy()
    }

    if (::playerView.isInitialized) {
        playerView.player = null
    }

    player?.release()
    player = null

    window.clearFlags(
        WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
    )

    super.onDestroy()
}

override fun onPause() {
    super.onPause()
    setM3u8Status(false)
}

override fun onResume() {
    super.onResume()

    if (!isM3u8Active) {
        setM3u8Status(true)
    }
}

private fun dp(v: Int): Int {
    return (v * resources.displayMetrics.density).toInt()
}

}