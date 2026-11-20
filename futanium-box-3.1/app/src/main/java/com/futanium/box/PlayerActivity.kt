
package com.futanium.box

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
import android.view.WindowManager
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
import com.google.firebase.database.FirebaseDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.util.UUID
import java.util.concurrent.TimeUnit

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

    private var m3u8RefId: String? = null
    private var isM3u8Active = false

    private val controllerHandler = Handler(Looper.getMainLooper())

    private val controllerAutoHide = Runnable {
        playerView.hideController()
    }

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

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
    private var channelNameText: TextView? = null
    private var channelButton: TextView? = null

    private var currentReferer: String? = null
    private var currentUserAgent: String? = null
    private var currentCookie: String? = null
    private var currentOrigin: String? = null
    private var currentSubtitle: String? = null

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

        // -----------------------------------------------------
        // GRADIENTE INFERIOR
        // -----------------------------------------------------

        channelGradient = View(this).apply {
            background = GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(
                    Color.TRANSPARENT,
                    Color.argb(245, 0, 0, 0)
                )
            )

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

        // -----------------------------------------------------
        // RODAPÉ DOS CANAIS
        // -----------------------------------------------------

        channelBottom = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(8), 0, dp(8))

            setBackgroundColor(Color.TRANSPARENT)

            visibility = View.GONE
            elevation = dp(15).toFloat()
        }

        channelNameText = createChannelText(
            "",
            17f,
            Color.WHITE,
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

        // -----------------------------------------------------
        // PAINEL LATERAL
        // -----------------------------------------------------

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
            descendantFocusability = ViewGroupFocusability.AFTER_DESCENDANTS
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
                text = channel.name
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

    private fun showChannelControls() {
        if (tvChannels.isEmpty()) return

        channelControlsVisible = true

        channelNameText?.text =
            tvChannels.getOrNull(selectedChannelIndex)?.name ?: "Canal"

        channelBottom?.visibility = View.VISIBLE
        channelButton?.requestFocus()

        controllerHandler.removeCallbacks(controllerAutoHide)
    }

    private fun openChannelPanel() {
        if (tvChannels.isEmpty()) return

        channelControlsVisible = true
        channelPanelVisible = true

        channelBottom?.visibility = View.GONE
        channelPanel?.visibility = View.VISIBLE

        updateChannelList()
        focusSelectedChannel()
    }

    private fun closeChannelPanel() {
        channelPanelVisible = false

        channelPanel?.visibility = View.GONE

        if (channelControlsVisible) {
            channelBottom?.visibility = View.VISIBLE
            channelButton?.requestFocus()
        }
    }

    private fun hideChannelControls() {
        channelControlsVisible = false
        channelPanelVisible = false

        channelBottom?.visibility = View.GONE
        channelPanel?.visibility = View.GONE

        playerView.requestFocus()
    }

    // =========================================================
    // TROCAR CANAL
    // =========================================================

    private fun selectChannel(index: Int) {
        if (index !in tvChannels.indices) return

        selectedChannelIndex = index

        val channel = tvChannels[index]

        // Canal que precisa passar pelo WebView para captura
        if (channel.captureM3u8) {
            val webIntent = Intent(
                this,
                WebViewActivity::class.java
            ).apply {
                putExtra(
                    WebViewActivity.EXTRA_URL,
                    channel.url
                )

                putExtra(EXTRA_TITLE, channel.name)

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
            }

            startActivity(webIntent)
            finish()
            return
        }

        hideChannelControls()

        main.removeCallbacks(bufferingWatchdog)

        playerView.player = null

        player?.release()
        player = null

        currentSubtitle = null

        initPlayer(
            channel.url,
            channel.name,
            currentReferer,
            currentUserAgent,
            currentCookie,
            currentSubtitle,
            currentOrigin
        )
    }

    // =========================================================
    // CONTROLE REMOTO
    // =========================================================

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action != KeyEvent.ACTION_DOWN) {
            return super.dispatchKeyEvent(event)
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

        findViewById<ProgressBar>(
            androidx.media3.ui.R.id.exo_buffering
        )?.let { pb ->
            val white =
                android.content.res.ColorStateList.valueOf(Color.WHITE)

            pb.indeterminateTintList = white
            pb.indeterminateTintMode =
                android.graphics.PorterDuff.Mode.SRC_IN
        }

        listOf(
            androidx.media3.ui.R.id.exo_prev,
            androidx.media3.ui.R.id.exo_next,
            androidx.media3.ui.R.id.exo_settings
        ).forEach { id ->
            playerView.findViewById<View?>(id)?.visibility = View.GONE
        }

        // =====================================================
        // DADOS DO PLAYER
        // =====================================================

        val url = intent.getStringExtra(EXTRA_URL).orEmpty()
        val title = intent.getStringExtra(EXTRA_TITLE).orEmpty()
        val referer = intent.getStringExtra(EXTRA_REFERER)
        val ua = intent.getStringExtra(EXTRA_USER_AGENT)
        val subtitleUrl = intent.getStringExtra(EXTRA_SUBTITLE)
        val cookie = intent.getStringExtra(EXTRA_COOKIE)
        val origin = intent.getStringExtra(EXTRA_ORIGIN)

        currentReferer = referer
        currentUserAgent = ua
        currentCookie = cookie
        currentOrigin = origin
        currentSubtitle = subtitleUrl

        setupChannelInterface()

        if (!isSupported(url)) {
            startActivity(
                Intent(this, WebViewActivity::class.java).apply {
                    putExtra(WebViewActivity.EXTRA_URL, url)
                }
            )

            finish()
            return
        }

        initPlayer(
            url,
            title,
            referer,
            ua,
            cookie,
            subtitleUrl,
            origin
        )
    }

    // =========================================================
    // IDENTIFICAÇÃO DO FORMATO
    // =========================================================

    private fun isSupported(u: String): Boolean {
        val path = Uri.parse(u).path?.lowercase().orEmpty()

        return path.endsWith(".m3u8") ||
            path.endsWith(".mpd") ||
            path.endsWith(".ts") ||
            path.endsWith(".txt")
    }

    private fun isMpd(u: String): Boolean {
        return Uri.parse(u).path
            ?.lowercase()
            ?.endsWith(".mpd") == true
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

                    val tag = parser.name.lowercase()

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
                            )?.lowercase().orEmpty()

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
    // CRIAÇÃO DO PLAYER
    // =========================================================

    private fun initPlayer(
        url: String,
        title: String?,
        referer: String?,
        ua: String?,
        cookie: String?,
        subtitleUrl: String?,
        origin: String?
    ) {

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

            if (!ua.isNullOrBlank()) {
                setUserAgent(ua)
            }

            setDefaultRequestProperties(requestHeaders)
        }

        val mediaSourceFactory = DefaultMediaSourceFactory(dsFactory)

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

                    Player.STATE_READY,
                    Player.STATE_ENDED -> {
                        main.removeCallbacks(bufferingWatchdog)
                    }

                    Player.STATE_IDLE -> {
                        // Não repetir prepare automaticamente aqui.
                    }
                }
            }

            override fun onPlayerError(error: PlaybackException) {

                main.removeCallbacks(bufferingWatchdog)

                Log.e(
                    TAG,
                    "Erro de reprodução: ${error.errorCodeName}",
                    error
                )

                val message = when (error.errorCode) {

                    PlaybackException.ERROR_CODE_DRM_CONTENT_ERROR,
                    PlaybackException.ERROR_CODE_DRM_LICENSE_ACQUISITION_FAILED,
                    PlaybackException.ERROR_CODE_DRM_PROVISIONING_FAILED ->
                        "Falha na proteção DRM ou na licença."

                    PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED,
                    PlaybackException.ERROR_CODE_PARSING_MANIFEST_MALFORMED ->
                        "Manifesto ou conteúdo inválido."

                    PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS ->
                        "Erro HTTP ao acessar o conteúdo."

                    PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
                    PlaybackException.ERROR_CODE_DECODING_FAILED ->
                        "Codec de áudio ou vídeo incompatível."

                    else ->
                        "Falha na reprodução: ${error.errorCodeName}"
                }

                Toast.makeText(
                    this@PlayerActivity,
                    message,
                    Toast.LENGTH_LONG
                ).show()
            }
        })

        // =====================================================
        // IDENTIFICAÇÃO AUTOMÁTICA + MPD
        // =====================================================

        lifecycleScope.launch {

            val path = Uri.parse(url).path?.lowercase().orEmpty()

            val itemBuilder = MediaItem.Builder()
                .setUri(Uri.parse(url))

            when {

                path.endsWith(".mpd") -> {
                    itemBuilder.setMimeType(MimeTypes.APPLICATION_MPD)
                }

                path.endsWith(".m3u8") || path.endsWith(".txt") -> {
                    itemBuilder.setMimeType(MimeTypes.APPLICATION_M3U8)

                    Log.i(
                        TAG,
                        if (path.endsWith(".txt")) {
                            "Arquivo TXT tratado diretamente como HLS/M3U8"
                        } else {
                            "M3U8 tradicional identificado"
                        }
                    )
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

                    val externalLicense =
                        intent.getStringExtra(EXTRA_DRM_LICENSE_URL)

                    val license = externalLicense
                        ?: analysis.licenseUrl

                    val drmUuid = when {

                        analysis.drmSystems.contains("Widevine") ->
                            C.WIDEVINE_UUID

                        analysis.drmSystems.contains("ClearKey") ->
                            C.CLEARKEY_UUID

                        else -> null
                    }

                    if (drmUuid != null && !license.isNullOrBlank()) {

                        val licenseHeaders =
                            requestHeaders.toMutableMap()

                        Log.i(
                            TAG,
                            "DRM identificado: ${analysis.drmSystems}"
                        )

                        Log.i(
                            TAG,
                            "Configuração de licença disponível"
                        )

                        val drmConfiguration =
                            MediaItem.DrmConfiguration.Builder(drmUuid)
                                .setLicenseUri(Uri.parse(license))
                                .setLicenseRequestHeaders(licenseHeaders)
                                .build()

                        itemBuilder.setDrmConfiguration(
                            drmConfiguration
                        )

                    } else if (analysis.drmSystems.isNotEmpty()) {

                        Log.w(
                            TAG,
                            "DRM identificado, mas não há URL de licença disponível."
                        )

                        Log.w(
                            TAG,
                            "É necessária a configuração autorizada do provedor."
                        )

                    } else {

                        Log.i(
                            TAG,
                            "Nenhum sistema DRM identificado no MPD."
                        )
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

            p.setMediaItem(itemBuilder.build())
            p.prepare()
            p.playWhenReady = true
        }
    }

    // =========================================================
    // CICLO DE VIDA
    // =========================================================

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)

        if (hasFocus) {
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

        finish()
    }

    override fun onDestroy() {

        main.removeCallbacks(bufferingWatchdog)
        controllerHandler.removeCallbacks(controllerAutoHide)

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

    // Constante para evitar depender de imports adicionais
    private object ViewGroupFocusability {
        const val AFTER_DESCENDANTS = 0x40000
    }
}
