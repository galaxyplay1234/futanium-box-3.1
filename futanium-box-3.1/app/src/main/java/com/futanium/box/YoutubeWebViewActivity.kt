
package com.futanium.box

import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.net.http.SslError
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.webkit.CookieManager
import android.webkit.SslErrorHandler
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import java.util.Locale

class YoutubeWebViewActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_URL = "url"
    }

    private lateinit var web: WebView
    private lateinit var root: FrameLayout

    private data class TvChannel(
        val name: String,
        val url: String,
        val captureM3u8: Boolean
    )

    private val tvChannels = mutableListOf<TvChannel>()

    private var selectedChannelIndex = 0
    private var channelControlsVisible = false
    private var channelPanelVisible = false

    private var channelGradient: View? = null
    private var channelBottom: LinearLayout? = null
    private var channelPanel: LinearLayout? = null
    private var channelListContainer: LinearLayout? = null
    private var channelNameText: TextView? = null
    private var channelButton: TextView? = null

    private val channelHandler = Handler(Looper.getMainLooper())
    private val CHANNEL_IDLE_TIMEOUT = 5000L

    private val controlsAutoHide = Runnable {
        if (channelControlsVisible && !channelPanelVisible) {
            hideChannelControls()
        }
    }

    private val panelAutoHide = Runnable {
        if (channelPanelVisible) {
            closeChannelPanel()
        }
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

    private fun createBackground(
        color: Int,
        radius: Int
    ): GradientDrawable {
        return GradientDrawable().apply {
            setColor(color)
            cornerRadius = dp(radius).toFloat()
        }
    }

    private fun createText(
        value: String,
        size: Float,
        color: Int,
        bold: Boolean = false
    ): TextView {
        return TextView(this).apply {
            text = value
            textSize = size
            setTextColor(color)
            gravity = Gravity.CENTER_VERTICAL

            if (bold) {
                setTypeface(
                    null,
                    android.graphics.Typeface.BOLD
                )
            }
        }
    }

    private fun readTvChannels() {

        tvChannels.clear()

        val names =
            intent.getStringArrayListExtra("tv_channel_names")

        val urls =
            intent.getStringArrayListExtra("tv_channel_urls")

        val captures =
            intent.getBooleanArrayExtra("tv_channel_capture")

        if (names == null || urls == null) return

        val total = minOf(names.size, urls.size)

        for (i in 0 until total) {

            val url = urls[i].trim()

            if (url.isNotBlank()) {
                tvChannels.add(
                    TvChannel(
                        name = names[i].ifBlank {
                            "Canal ${i + 1}"
                        },
                        url = url,
                        captureM3u8 =
                            captures?.getOrNull(i) ?: false
                    )
                )
            }
        }

        selectedChannelIndex = intent.getIntExtra(
            "selected_channel_index",
            0
        ).coerceIn(
            0,
            (tvChannels.size - 1).coerceAtLeast(0)
        )
    }

    private fun scheduleAutoHide() {

        channelHandler.removeCallbacks(controlsAutoHide)
        channelHandler.removeCallbacks(panelAutoHide)

        when {
            channelPanelVisible -> {
                channelHandler.postDelayed(
                    panelAutoHide,
                    CHANNEL_IDLE_TIMEOUT
                )
            }

            channelControlsVisible -> {
                channelHandler.postDelayed(
                    controlsAutoHide,
                    CHANNEL_IDLE_TIMEOUT
                )
            }
        }
    }

    private fun cancelAutoHide() {
        channelHandler.removeCallbacks(controlsAutoHide)
        channelHandler.removeCallbacks(panelAutoHide)
    }

    // =====================================================
    // MENU DE CANAIS
    // =====================================================

    private fun setupChannelInterface() {

        if (tvChannels.isEmpty()) return

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

        root.addView(
            channelGradient!!,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                dp(300),
                Gravity.BOTTOM
            )
        )

        channelBottom = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(8), 0, dp(8))
            visibility = View.GONE
            elevation = dp(15).toFloat()
        }

        channelNameText = createText(
            "",
            17f,
            Color.WHITE,
            true
        )

        channelButton = createText(
            "Canais",
            16f,
            Color.WHITE,
            true
        ).apply {

            gravity = Gravity.CENTER

            setPadding(
                dp(20),
                dp(10),
                dp(20),
                dp(10)
            )

            background = createBackground(
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

        root.addView(
            channelBottom!!,
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

            background = createBackground(
                Color.rgb(15, 17, 22),
                0
            )

            visibility = View.GONE
            elevation = dp(25).toFloat()
        }

        channelPanel?.addView(
            createText(
                "Canais",
                21f,
                Color.WHITE,
                true
            ),
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(48)
            )
        )

        channelPanel?.addView(
            View(this).apply {
                setBackgroundColor(Color.rgb(55, 59, 67))
            },
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(1)
            )
        )

        val scroll = ScrollView(this).apply {
            isFillViewport = true
            overScrollMode = View.OVER_SCROLL_NEVER
            descendantFocusability =
                ViewGroup.FOCUS_AFTER_DESCENDANTS
        }

        channelListContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
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

        root.addView(
            channelPanel!!,
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

        tvChannels.forEachIndexed { index, _ ->

            val item = TextView(this).apply {

                text = "Canal ${index + 1}"
                textSize = 15f
                setTextColor(Color.WHITE)

                gravity = Gravity.CENTER_VERTICAL

                setPadding(
                    dp(14),
                    0,
                    dp(10),
                    0
                )

                isFocusable = true
                isFocusableInTouchMode = true

                background = createBackground(
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

            val item =
                container.getChildAt(selectedChannelIndex)

            item?.requestFocus()

            item?.let {
                val scroll =
                    channelPanel?.getChildAt(2) as? ScrollView

                scroll?.smoothScrollTo(0, it.top)
            }
        }
    }

    private fun showChannelControls() {

        if (tvChannels.isEmpty()) return

        channelControlsVisible = true
        channelPanelVisible = false

        channelNameText?.text =
            "Canal ${selectedChannelIndex + 1}"

        channelGradient?.visibility = View.VISIBLE
        channelBottom?.visibility = View.VISIBLE

        channelButton?.requestFocus()

        scheduleAutoHide()
    }

    private fun openChannelPanel() {

        if (tvChannels.isEmpty()) return

        channelControlsVisible = true
        channelPanelVisible = true

        cancelAutoHide()

        channelGradient?.visibility = View.GONE
        channelBottom?.visibility = View.GONE
        channelPanel?.visibility = View.VISIBLE

        updateChannelList()
        focusSelectedChannel()

        scheduleAutoHide()
    }

    private fun closeChannelPanel() {

        channelPanelVisible = false
        channelPanel?.visibility = View.GONE

        if (channelControlsVisible) {

            channelGradient?.visibility = View.VISIBLE
            channelBottom?.visibility = View.VISIBLE

            channelButton?.requestFocus()

            scheduleAutoHide()

        } else {
            hideChannelControls()
        }
    }

    private fun hideChannelControls() {

        cancelAutoHide()

        channelControlsVisible = false
        channelPanelVisible = false

        channelGradient?.visibility = View.GONE
        channelBottom?.visibility = View.GONE
        channelPanel?.visibility = View.GONE

        web.requestFocus()
    }

    // =====================================================
    // TROCA DE CANAL E SINCRONIZAÇÃO
    // =====================================================

    private fun selectChannel(index: Int) {

        if (index !in tvChannels.indices) return

        cancelAutoHide()

        selectedChannelIndex = index

        val channel = tvChannels[index]

        val path = Uri.parse(channel.url)
            .path
            ?.lowercase(Locale.ROOT)
            .orEmpty()

        val isDirectManifest =
            path.endsWith(".m3u8") ||
            path.endsWith(".mpd")

        if (isDirectManifest) {

            val playerIntent = Intent(
                this,
                PlayerActivity::class.java
            ).apply {

                putExtra(
                    PlayerActivity.EXTRA_URL,
                    channel.url
                )

                putExtra(
                    PlayerActivity.EXTRA_TITLE,
                    "Canal ${index + 1}"
                )

                addChannelExtras(this, index)
            }

            startActivity(playerIntent)
            finish()
            return
        }

        val isYoutube =
            channel.url.contains("youtube.com", true) ||
            channel.url.contains("youtu.be", true)

        
if (isYoutube) {

    val youtubeIntent = Intent(
        this,
        YoutubeWebViewActivity::class.java
    ).apply {

        putExtra(
            YoutubeWebViewActivity.EXTRA_URL,
            channel.url
        )

        addChannelExtras(this, index)
    }

    startActivity(youtubeIntent)
    finish()
    return
}


        val webIntent = Intent(
            this,
            WebViewActivity::class.java
        ).apply {

            putExtra(
                WebViewActivity.EXTRA_URL,
                channel.url
            )

            putExtra(
                "captureM3u8",
                channel.captureM3u8
            )

            addChannelExtras(this, index)
        }

        startActivity(webIntent)
        finish()
    }

    private fun addChannelExtras(
        target: Intent,
        selectedIndex: Int
    ) {

        target.putStringArrayListExtra(
            "tv_channel_names",
            ArrayList(tvChannels.map { it.name })
        )

        target.putStringArrayListExtra(
            "tv_channel_urls",
            ArrayList(tvChannels.map { it.url })
        )

        target.putExtra(
            "tv_channel_capture",
            BooleanArray(tvChannels.size) {
                tvChannels[it].captureM3u8
            }
        )

        intent.getIntArrayExtra("tv_channel_orders")?.let {
            target.putExtra(
                "tv_channel_orders",
                it
            )
        }

        target.putExtra(
            "selected_channel_index",
            selectedIndex
        )
    }

    // =====================================================
    // CONTROLE REMOTO
    // =====================================================

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {

        if (event.action != KeyEvent.ACTION_DOWN) {
            return super.dispatchKeyEvent(event)
        }

        if (channelPanelVisible || channelControlsVisible) {
            scheduleAutoHide()
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
                        (selectedChannelIndex - 1)
                            .coerceAtLeast(0)

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

    // =====================================================
    // ON CREATE
    // =====================================================

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {

        super.onCreate(savedInstanceState)

        window.statusBarColor = Color.BLACK
        window.navigationBarColor = Color.BLACK

        WindowCompat.setDecorFitsSystemWindows(
            window,
            true
        )

        hideSystemBars()

        readTvChannels()

        root = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
        }

        web = WebView(this).apply {

            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )

            setBackgroundColor(Color.BLACK)

            isFocusable = true
            isFocusableInTouchMode = true
        }

        root.addView(web)

        setContentView(root)

        CookieManager.getInstance().setAcceptCookie(true)

        CookieManager.getInstance()
            .setAcceptThirdPartyCookies(web, true)

        with(web.settings) {

            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true

            allowFileAccess = true
            allowContentAccess = true

            mediaPlaybackRequiresUserGesture = false
            loadsImagesAutomatically = true

            mixedContentMode =
                WebSettings.MIXED_CONTENT_ALWAYS_ALLOW

            userAgentString =
                "Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/136.0.0.0 Mobile Safari/537.36"
        }

        web.webViewClient = object : WebViewClient() {

            override fun onReceivedSslError(
                view: WebView?,
                handler: SslErrorHandler?,
                error: SslError?
            ) {
                handler?.proceed()
            }

            override fun shouldOverrideUrlLoading(
                view: WebView,
                request: android.webkit.WebResourceRequest
            ): Boolean {
                return false
            }
        }

        web.webChromeClient = object : WebChromeClient() {

            private var customView: View? = null
            private var callback: CustomViewCallback? = null

            override fun onShowCustomView(
                view: View?,
                callback: CustomViewCallback?
            ) {

                if (view == null) return

                this.customView = view
                this.callback = callback

                addContentView(
                    view,
                    ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    )
                )

                hideSystemBars()
            }

            override fun onHideCustomView() {

                (customView?.parent as? ViewGroup)
                    ?.removeView(customView)

                customView = null

                callback?.onCustomViewHidden()
                callback = null
            }
        }

        setupChannelInterface()

        val url = intent.getStringExtra(EXTRA_URL).orEmpty()

        if (url.isNotBlank()) {
            web.loadUrl(url)
        }
    }

    private fun hideSystemBars() {

        WindowInsetsControllerCompat(
            window,
            window.decorView
        ).apply {

            hide(
                WindowInsetsCompat.Type.statusBars()
            )

            systemBarsBehavior =
                WindowInsetsControllerCompat
                    .BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE

            isAppearanceLightStatusBars = false
            isAppearanceLightNavigationBars = false
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {

        super.onWindowFocusChanged(hasFocus)

        if (hasFocus) {
            hideSystemBars()
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

        if (web.canGoBack()) {
            web.goBack()
        } else {
            super.onBackPressed()
        }
    }

    override fun onDestroy() {

        cancelAutoHide()

        web.stopLoading()
        web.destroy()

        super.onDestroy()
    }
}
