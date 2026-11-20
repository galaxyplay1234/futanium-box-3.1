
package com.futanium.box

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.drawable.GradientDrawable
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.*
import android.webkit.SslErrorHandler
import android.net.http.SslError
import android.content.Intent
import android.provider.Settings
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.ByteArrayInputStream
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import com.google.firebase.database.FirebaseDatabase
import java.util.UUID

class WebViewActivity : AppCompatActivity() {

    private lateinit var web: WebView
    private lateinit var insets: WindowInsetsControllerCompat
    private lateinit var webLoader: android.widget.ProgressBar
    private lateinit var blackShield: View

    @Volatile
    private var lastMainUrl: String? = null

    private var webUserAgent: String = ""

    private val client = OkHttpClient()

    private val blocklistUrl =
        "https://raw.githubusercontent.com/galaxyplay1234/bloqueio-ads-futanium/refs/heads/main/blocklist.txt"

    private val domainRules = HashSet<String>()
    private val substringRules = ArrayList<String>()
    private val allowDomainRules = HashSet<String>()
    private val allowSubstringRules = ArrayList<String>()
    private val proxyDomainRules = HashSet<String>()
    private val proxySubstringRules = ArrayList<String>()

    private val PROXY_BASE =
        "https://controledeestoque.rf.gd/proxy.php?url="

    private var allowHost: String? = null
    private val blockReady = AtomicBoolean(false)

    private var shortenerActive: Boolean = false
    private var isYoutubeMode: Boolean = false
    private var playerOpened = false
    private var captureM3u8 = false

    private var operatorDialogOpen = false
    private var operatorDialog: AlertDialog? = null
    private var returningFromSettings = false

    private var webviewRefId: String? = null
    private var isWebviewActive = false

    // =====================================================
    // MOUSE VIRTUAL FUTANIUM
    // =====================================================

    private var virtualMouseActive = false
    private var virtualMouseView: View? = null

    private var mouseX = 0f
    private var mouseY = 0f

    // Velocidade do cursor. Aumente para deixar mais rápido.
    private val mouseSpeed = 48f

    private val mouseDiameter by lazy { dp(28) }

    // =====================================================
    // MENU DE CANAIS SINCRONIZADO
    // =====================================================

    private data class TvChannel(
        val name: String,
        val url: String,
        val captureM3u8: Boolean
    )

    private val tvChannels = mutableListOf<TvChannel>()

    private var selectedChannelIndex = 0
    private var channelControlsVisible = false
    private var channelPanelVisible = false

    private lateinit var channelRoot: FrameLayout

    private var channelGradient: View? = null
    private var channelBottom: LinearLayout? = null
    private var channelPanel: LinearLayout? = null
    private var channelListContainer: LinearLayout? = null
    private var channelNameText: TextView? = null
    private var channelButton: TextView? = null
    private var gameTitleText: TextView? = null

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

    private fun dp(value: Int): Int {
        return (value * resources.displayMetrics.density).toInt()
    }

    // =====================================================
    // MOUSE VIRTUAL
    // =====================================================

    private fun setupVirtualMouse() {

        val root = findViewById<FrameLayout>(android.R.id.content)

        val cursor = object : View(this) {

            private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.WHITE
                style = Paint.Style.STROKE
                strokeWidth = dp(3).toFloat()
            }

            override fun onDraw(canvas: Canvas) {
                super.onDraw(canvas)

                val radius = width / 2f - dp(3)

                // Bolinha transparente, somente com borda branca.
                // Não existe ponto branco no centro.
                canvas.drawCircle(
                    width / 2f,
                    height / 2f,
                    radius,
                    paint
                )
            }
        }

        cursor.visibility = View.GONE
        cursor.isClickable = false
        cursor.isFocusable = false
        cursor.elevation = dp(40).toFloat()

        virtualMouseView = cursor

        root.addView(
            cursor,
            FrameLayout.LayoutParams(
                mouseDiameter,
                mouseDiameter
            )
        )
    }

    private fun activateVirtualMouse(keyCode: Int) {

    val cursor = virtualMouseView ?: return
    val root = findViewById<FrameLayout>(android.R.id.content)

    if (!virtualMouseActive) {

        virtualMouseActive = true

        val webLocation = IntArray(2)
        val rootLocation = IntArray(2)

        web.getLocationOnScreen(webLocation)
        root.getLocationOnScreen(rootLocation)

        // Começa no centro da área do WebView.
        mouseX =
            webLocation[0] - rootLocation[0] + web.width / 2f

        mouseY =
            webLocation[1] - rootLocation[1] + web.height / 2f
    }

    // Garante que o cursor apareça em qualquer seta.
    cursor.visibility = View.VISIBLE
    cursor.bringToFront()

    when (keyCode) {

        KeyEvent.KEYCODE_DPAD_UP ->
            mouseY -= mouseSpeed

        KeyEvent.KEYCODE_DPAD_DOWN ->
            mouseY += mouseSpeed

        KeyEvent.KEYCODE_DPAD_LEFT ->
            mouseX -= mouseSpeed

        KeyEvent.KEYCODE_DPAD_RIGHT ->
            mouseX += mouseSpeed
    }

    updateVirtualMousePosition()
}

    private fun updateVirtualMousePosition() {

        val cursor = virtualMouseView ?: return

        val webLocation = IntArray(2)
        val rootLocation = IntArray(2)

        web.getLocationOnScreen(webLocation)

        val root = findViewById<FrameLayout>(android.R.id.content)
        root.getLocationOnScreen(rootLocation)

        val minX = webLocation[0] - rootLocation[0]
        val minY = webLocation[1] - rootLocation[1]

        val maxX = minX + web.width
        val maxY = minY + web.height

        mouseX = mouseX.coerceIn(minX, maxX)
        mouseY = mouseY.coerceIn(minY, maxY)

        cursor.x = mouseX - cursor.width / 2f
        cursor.y = mouseY - cursor.height / 2f

        cursor.invalidate()
    }

    private fun clickVirtualMouse() {

        val cursor = virtualMouseView ?: return

        val webLocation = IntArray(2)
        web.getLocationOnScreen(webLocation)

        val localX = mouseX - webLocation[0]
        val localY = mouseY - webLocation[1]

        val downTime = SystemClock.uptimeMillis()

        val down = MotionEvent.obtain(
            downTime,
            downTime,
            MotionEvent.ACTION_DOWN,
            localX,
            localY,
            0
        )

        val up = MotionEvent.obtain(
            downTime,
            downTime + 70,
            MotionEvent.ACTION_UP,
            localX,
            localY,
            0
        )

        down.source = android.view.InputDevice.SOURCE_TOUCHSCREEN
        up.source = android.view.InputDevice.SOURCE_TOUCHSCREEN

        web.dispatchTouchEvent(down)
        web.dispatchTouchEvent(up)

        down.recycle()
        up.recycle()

        // Depois do clique, desativa e esconde o cursor.
        virtualMouseActive = false
        cursor.visibility = View.GONE

        web.requestFocus()
    }

    private fun deactivateVirtualMouse() {

        virtualMouseActive = false
        virtualMouseView?.visibility = View.GONE
    }

    // =====================================================
    // CONTROLES DOS CANAIS
    // =====================================================

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

    private fun readTvChannels() {
        tvChannels.clear()

        val names = intent.getStringArrayListExtra("tv_channel_names")
        val urls = intent.getStringArrayListExtra("tv_channel_urls")
        val captures = intent.getBooleanArrayExtra("tv_channel_capture")

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
            "selected_channel_index",
            0
        ).coerceIn(
            0,
            (tvChannels.size - 1).coerceAtLeast(0)
        )
    }

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
                setTypeface(
                    null,
                    android.graphics.Typeface.BOLD
                )
            }
        }
    }

    private fun setupChannelInterface() {
        if (tvChannels.isEmpty()) return

        channelRoot = findViewById(android.R.id.content)

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

        channelRoot.addView(
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
            15f,
            Color.LTGRAY,
            false
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
                dp(27)
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

        channelRoot.addView(
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

        channelRoot.addView(
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
                    selectWebChannel(index)
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
        channelPanelVisible = false

        gameTitleText?.text =
            intent.getStringExtra("game_title").orEmpty()

        channelNameText?.text =
            "Canal ${selectedChannelIndex + 1}"

        channelGradient?.visibility = View.VISIBLE
        channelBottom?.visibility = View.VISIBLE

        channelButton?.requestFocus()

        scheduleChannelAutoHide()
    }

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

    private fun hideChannelControls() {
        cancelChannelAutoHide()

        channelControlsVisible = false
        channelPanelVisible = false

        channelGradient?.visibility = View.GONE
        channelBottom?.visibility = View.GONE
        channelPanel?.visibility = View.GONE

        web.requestFocus()
    }

    // =====================================================
    // TROCA DE CANAL
    // =====================================================

    private fun selectWebChannel(index: Int) {
        if (index !in tvChannels.indices) return

        cancelChannelAutoHide()

        selectedChannelIndex = index

        val channel = tvChannels[index]

        if (isDirectNativeManifest(channel.url)) {

            val cookies = CookieManager.getInstance()
                .getCookie(channel.url)
                .orEmpty()

            val nativeIntent = Intent(
                this,
                PlayerActivity::class.java
            ).apply {

                putExtra(PlayerActivity.EXTRA_URL, channel.url)
                putExtra(PlayerActivity.EXTRA_TITLE, channel.name)

                putExtra(
                    "game_title",
                    intent.getStringExtra("game_title").orEmpty()
                )

                putExtra(
                    PlayerActivity.EXTRA_REFERER,
                    lastMainUrl.orEmpty()
                )

                putExtra(
                    PlayerActivity.EXTRA_USER_AGENT,
                    webUserAgent
                )

                putExtra(
                    PlayerActivity.EXTRA_COOKIE,
                    cookies
                )

                putExtra(
                    PlayerActivity.EXTRA_ORIGIN,
                    ""
                )

                putStringArrayListExtra(
                    "tv_channel_names",
                    ArrayList(tvChannels.map { it.name })
                )

                putStringArrayListExtra(
                    "tv_channel_urls",
                    ArrayList(tvChannels.map { it.url })
                )

                putExtra(
                    "tv_channel_capture",
                    BooleanArray(tvChannels.size) {
                        tvChannels[it].captureM3u8
                    }
                )

                intent.getIntArrayExtra("tv_channel_orders")?.let {
                    putExtra("tv_channel_orders", it)
                }

                putExtra("selected_channel_index", index)
            }

            startActivity(nativeIntent)
            finish()
            return
        }

        // YOUTUBE: ABRIR NA WEBVIEW EXCLUSIVA

        val isYoutube =
            channel.url.contains("youtube.com", true) ||
                channel.url.contains("youtu.be", true) ||
                channel.url.contains("youtube-nocookie.com", true)

        if (isYoutube) {

            val youtubeIntent = Intent(
                this,
                YoutubeWebViewActivity::class.java
            ).apply {

                putExtra(
                    YoutubeWebViewActivity.EXTRA_URL,
                    channel.url
                )

                putExtra(
                    "game_title",
                    intent.getStringExtra("game_title").orEmpty()
                )

                putStringArrayListExtra(
                    "tv_channel_names",
                    ArrayList(tvChannels.map { it.name })
                )

                putStringArrayListExtra(
                    "tv_channel_urls",
                    ArrayList(tvChannels.map { it.url })
                )

                putExtra(
                    "tv_channel_capture",
                    BooleanArray(tvChannels.size) {
                        tvChannels[it].captureM3u8
                    }
                )

                intent.getIntArrayExtra("tv_channel_orders")?.let {
                    putExtra("tv_channel_orders", it)
                }

                putExtra("selected_channel_index", index)
            }

            startActivity(youtubeIntent)
            finish()
            return
        }

        // Página web: mantém o comportamento original.

        intent.putExtra("selected_channel_index", index)

        captureM3u8 = channel.captureM3u8
        playerOpened = false

        val host = runCatching {
            Uri.parse(channel.url)
                .host
                ?.lowercase(Locale.ROOT)
        }.getOrNull()

        allowHost = host

        shortenerActive = isShortener(
            channel.url,
            host
        )

        isYoutubeMode =
            channel.url.contains("youtube.com/embed", true) ||
                channel.url.contains("youtube-nocookie.com/embed", true)

        channelNameText?.text =
            "Canal ${selectedChannelIndex + 1}"

        deactivateVirtualMouse()
        hideChannelControls()

        blackShield.visibility = View.GONE
        webLoader.visibility = View.VISIBLE

        lastMainUrl = channel.url

        web.stopLoading()
        web.loadUrl(channel.url)
    }

    // =====================================================
    // CONTROLE REMOTO
    // =====================================================

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {

    if (event.action != KeyEvent.ACTION_DOWN) {
        return super.dispatchKeyEvent(event)
    }

    // QUALQUER SETA ATIVA O MOUSE IMEDIATAMENTE.
    if (
        event.keyCode == KeyEvent.KEYCODE_DPAD_UP ||
        event.keyCode == KeyEvent.KEYCODE_DPAD_DOWN ||
        event.keyCode == KeyEvent.KEYCODE_DPAD_LEFT ||
        event.keyCode == KeyEvent.KEYCODE_DPAD_RIGHT
    ) {

        // Fecha os controles de canais para liberar o mouse.
        if (channelPanelVisible || channelControlsVisible) {
            hideChannelControls()
        }

        activateVirtualMouse(event.keyCode)

        return true
    }

    // MOUSE ATIVO: OK CLICA.
    if (virtualMouseActive) {

        when (event.keyCode) {

            KeyEvent.KEYCODE_DPAD_CENTER,
            KeyEvent.KEYCODE_ENTER,
            KeyEvent.KEYCODE_NUMPAD_ENTER -> {

                clickVirtualMouse()
                return true
            }

            KeyEvent.KEYCODE_BACK,
            KeyEvent.KEYCODE_ESCAPE -> {

                deactivateVirtualMouse()
                return true
            }
        }

        return super.dispatchKeyEvent(event)
    }

    // MENU DE CANAIS.
    if (channelPanelVisible || channelControlsVisible) {

        scheduleChannelAutoHide()

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

            KeyEvent.KEYCODE_DPAD_CENTER,
            KeyEvent.KEYCODE_ENTER,
            KeyEvent.KEYCODE_NUMPAD_ENTER -> {

                if (channelPanelVisible) {
                    selectWebChannel(selectedChannelIndex)
                    return true
                }

                if (channelControlsVisible) {
                    openChannelPanel()
                    return true
                }
            }
        }

        return super.dispatchKeyEvent(event)
    }

    // OK sem mouse: mostra os controles dos canais.
    when (event.keyCode) {

        KeyEvent.KEYCODE_DPAD_CENTER,
        KeyEvent.KEYCODE_ENTER,
        KeyEvent.KEYCODE_NUMPAD_ENTER -> {

            if (tvChannels.isNotEmpty()) {
                showChannelControls()
                return true
            }
        }

        KeyEvent.KEYCODE_BACK,
        KeyEvent.KEYCODE_ESCAPE -> {
            finish()
            return true
        }
    }

    return super.dispatchKeyEvent(event)
}

        // Mouse ativo: setas movimentam e OK clica.

        if (virtualMouseActive) {

            when (event.keyCode) {

                KeyEvent.KEYCODE_DPAD_UP,
                KeyEvent.KEYCODE_DPAD_DOWN,
                KeyEvent.KEYCODE_DPAD_LEFT,
                KeyEvent.KEYCODE_DPAD_RIGHT -> {

                    activateVirtualMouse(event.keyCode)
                    return true
                }

                KeyEvent.KEYCODE_DPAD_CENTER,
                KeyEvent.KEYCODE_ENTER,
                KeyEvent.KEYCODE_NUMPAD_ENTER -> {

                    clickVirtualMouse()
                    return true
                }

                KeyEvent.KEYCODE_BACK,
                KeyEvent.KEYCODE_ESCAPE -> {

                    deactivateVirtualMouse()
                    return true
                }
            }

            return super.dispatchKeyEvent(event)
        }

        // Mouse desativado: qualquer seta ativa o cursor.

        when (event.keyCode) {

            KeyEvent.KEYCODE_DPAD_UP,
            KeyEvent.KEYCODE_DPAD_DOWN,
            KeyEvent.KEYCODE_DPAD_LEFT,
            KeyEvent.KEYCODE_DPAD_RIGHT -> {

                activateVirtualMouse(event.keyCode)
                return true
            }

            KeyEvent.KEYCODE_DPAD_CENTER,
            KeyEvent.KEYCODE_ENTER,
            KeyEvent.KEYCODE_NUMPAD_ENTER -> {

                if (tvChannels.isNotEmpty()) {
                    showChannelControls()
                    return true
                }
            }

            KeyEvent.KEYCODE_BACK,
            KeyEvent.KEYCODE_ESCAPE -> {
                finish()
                return true
            }
        }

        return super.dispatchKeyEvent(event)
    }

    // =====================================================
    // UTILITÁRIO DE CABEÇALHOS
    // =====================================================

    private fun getHeader(
        headers: Map<String, String>,
        name: String
    ): String? {
        return headers.entries.firstOrNull {
            it.key.equals(name, ignoreCase = true)
        }?.value
    }

    // =====================================================
    // SHORTENER
    // =====================================================

    private fun isShortener(
        url: String,
        host: String?
    ): Boolean {

        val u = url.lowercase(Locale.ROOT)
        val h = (host ?: "").lowercase(Locale.ROOT)

        return h == "bit.ly" ||
            u.contains("/bit.ly/") ||
            u.contains("://bit.ly/")
    }

    // =====================================================
    // FIREBASE
    // =====================================================

    private fun setWebviewStatus(active: Boolean) {

        val db = FirebaseDatabase.getInstance(
            "https://futanium-web-default-rtdb.firebaseio.com/"
        )

        val webviewRef = db.getReference("webview")

        if (active) {

            if (isWebviewActive) return

            webviewRefId = UUID.randomUUID().toString()

            webviewRef.child(webviewRefId!!).setValue(true)

            webviewRef.child(webviewRefId!!)
                .onDisconnect()
                .removeValue()

            isWebviewActive = true

        } else {

            webviewRefId?.let {
                webviewRef.child(it).removeValue()
                webviewRefId = null
            }

            isWebviewActive = false
        }
    }

    // =====================================================
    // ON CREATE
    // =====================================================

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {

        super.onCreate(savedInstanceState)

        setWebviewStatus(true)

        WindowCompat.setDecorFitsSystemWindows(window, true)

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

        setContentView(R.layout.activity_webview)

        webLoader = findViewById(R.id.webLoader)

        window.addFlags(
            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
        )

        val initialUrl =
            intent.getStringExtra(EXTRA_URL).orEmpty()

        captureM3u8 =
            intent.getBooleanExtra("captureM3u8", false)

        // Manifesto direto: encaminha para o player nativo.

        if (isDirectNativeManifest(initialUrl)) {

            val names =
                intent.getStringArrayListExtra("tv_channel_names")

            val urls =
                intent.getStringArrayListExtra("tv_channel_urls")

            val nativeIntent = Intent(
                this,
                PlayerActivity::class.java
            ).apply {

                putExtra(PlayerActivity.EXTRA_URL, initialUrl)

                putExtra(
                    "game_title",
                    intent.getStringExtra("game_title").orEmpty()
                )

                putExtra(
                    PlayerActivity.EXTRA_TITLE,
                    intent.getStringExtra(PlayerActivity.EXTRA_TITLE).orEmpty()
                )

                names?.let {
                    putStringArrayListExtra(
                        "tv_channel_names",
                        ArrayList(it)
                    )
                }

                urls?.let {
                    putStringArrayListExtra(
                        "tv_channel_urls",
                        ArrayList(it)
                    )
                }

                intent.getBooleanArrayExtra("tv_channel_capture")?.let {
                    putExtra("tv_channel_capture", it)
                }

                intent.getIntArrayExtra("tv_channel_orders")?.let {
                    putExtra("tv_channel_orders", it)
                }

                putExtra(
                    "selected_channel_index",
                    intent.getIntExtra("selected_channel_index", 0)
                )
            }

            startActivity(nativeIntent)
            finish()
            return
        }

        // Links do YouTube vão para a Activity exclusiva.

        if (
            initialUrl.contains("youtube.com", true) ||
            initialUrl.contains("youtu.be", true)
        ) {

            startActivity(
                Intent(
                    this,
                    YoutubeWebViewActivity::class.java
                ).apply {

                    putExtra("url", initialUrl)

                    putExtra(
                        "game_title",
                        intent.getStringExtra("game_title").orEmpty()
                    )

                    intent.getStringArrayListExtra("tv_channel_names")?.let {
                        putStringArrayListExtra(
                            "tv_channel_names",
                            ArrayList(it)
                        )
                    }

                    intent.getStringArrayListExtra("tv_channel_urls")?.let {
                        putStringArrayListExtra(
                            "tv_channel_urls",
                            ArrayList(it)
                        )
                    }

                    intent.getBooleanArrayExtra("tv_channel_capture")?.let {
                        putExtra("tv_channel_capture", it)
                    }

                    intent.getIntArrayExtra("tv_channel_orders")?.let {
                        putExtra("tv_channel_orders", it)
                    }

                    putExtra(
                        "selected_channel_index",
                        intent.getIntExtra("selected_channel_index", 0)
                    )
                }
            )

            finish()
            return
        }

        val initHost = runCatching {
            Uri.parse(initialUrl)
                .host
                ?.lowercase(Locale.ROOT)
        }.getOrNull()

        allowHost = initHost

        isYoutubeMode =
            initialUrl.contains("youtube.com/embed") ||
                initialUrl.contains("youtube-nocookie.com/embed")

        if (isShortener(initialUrl, initHost)) {
            shortenerActive = true
        }

        Thread {
            loadBlocklist()
        }.start()

        web = findViewById(R.id.web)

        readTvChannels()

        web.setBackgroundColor(Color.BLACK)
        web.keepScreenOn = true
        web.setLayerType(View.LAYER_TYPE_HARDWARE, null)

        web.isLongClickable = false
        web.setOnLongClickListener { true }
        web.isHapticFeedbackEnabled = false

        // INSETS

        val contentRoot =
            findViewById<ViewGroup>(android.R.id.content)
                .getChildAt(0) ?: web

        ViewCompat.setOnApplyWindowInsetsListener(
            contentRoot
        ) { v, ins ->

            val nav = ins.getInsets(
                WindowInsetsCompat.Type.navigationBars()
            )

            v.setPadding(
                v.paddingLeft,
                v.paddingTop,
                v.paddingRight,
                nav.bottom
            )

            ins
        }

        ViewCompat.setOnApplyWindowInsetsListener(web) { v, ins ->

            val nav = ins.getInsets(
                WindowInsetsCompat.Type.navigationBars()
            )

            v.setPadding(
                v.paddingLeft,
                v.paddingTop,
                v.paddingRight,
                nav.bottom
            )

            ins
        }

        ViewCompat.requestApplyInsets(contentRoot)
        ViewCompat.requestApplyInsets(web)

        // BLACK SHIELD

        blackShield = View(this).apply {

            setBackgroundColor(Color.BLACK)

            visibility = View.GONE

            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }

        (contentRoot as ViewGroup).addView(blackShield)

        // WEBVIEW SETTINGS

        with(web.settings) {

            javaScriptEnabled = true
            domStorageEnabled = true

            mediaPlaybackRequiresUserGesture = false

            setSupportMultipleWindows(false)
            javaScriptCanOpenWindowsAutomatically = false

            builtInZoomControls = false
            displayZoomControls = false
            setSupportZoom(false)

            useWideViewPort = false
            loadWithOverviewMode = false

            userAgentString =
                "Mozilla/5.0 (Linux; Android 13; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"

            mixedContentMode =
                WebSettings.MIXED_CONTENT_ALWAYS_ALLOW

            loadsImagesAutomatically = true
        }

        webUserAgent = web.settings.userAgentString.orEmpty()

        setupChannelInterface()

        // Mouse fica por cima do WebView e dos controles.
        setupVirtualMouse()

        // WEBVIEW CLIENT

        web.webViewClient = object : WebViewClient() {

            override fun shouldOverrideUrlLoading(
                view: WebView,
                request: WebResourceRequest
            ): Boolean {

                val uri = request.url
                val u = uri.toString()

                lastMainUrl = u

                if (isYoutubeMode) return false

                if (
                    isMediaUrl(u) ||
                    u.startsWith("blob:") ||
                    u.startsWith("data:")
                ) {
                    return false
                }

                if (u == "about:blank") return false

                if (
                    u.startsWith("intent://") ||
                    u.startsWith("market://") ||
                    u.startsWith("mailto:") ||
                    u.startsWith("tel:") ||
                    u.startsWith("sms:")
                ) {

                    return try {

                        startActivity(
                            Intent(
                                Intent.ACTION_VIEW,
                                Uri.parse(u)
                            )
                        )

                        true

                    } catch (_: Exception) {
                        true
                    }
                }

                if (!u.startsWith("http")) return false

                if (!isOnline()) {

                    blackShield.visibility = View.VISIBLE

                    showOfflineDialog {

                        blackShield.visibility = View.GONE

                        val retry = lastMainUrl ?: u

                        if (isOnline()) {
                            view.loadUrl(retry)
                        }
                    }

                    return true
                }

                val host =
                    uri.host?.lowercase(Locale.ROOT)
                        ?: return true

                if (
                    mustProxy(host, u.lowercase(Locale.ROOT)) &&
                    !u.startsWith(PROXY_BASE)
                ) {

                    view.loadUrl(
                        PROXY_BASE + Uri.encode(u)
                    )

                    return true
                }

                if (isShortener(u, host)) {

                    shortenerActive = true
                    return false
                }

                if (matchesAllowlist(host, u.lowercase(Locale.ROOT))) {

                    allowHost = host
                    shortenerActive = false

                    return false
                }

                if (shortenerActive) {

                    allowHost = host
                    shortenerActive = false

                    return false
                }

                val allow = allowHost

                if (
                    allow != null &&
                    (host == allow || host.endsWith(".$allow"))
                ) {
                    return false
                }

                if (request.isForMainFrame) {
                    return true
                }

                return false
            }

            override fun onPageStarted(
                view: WebView?,
                url: String?,
                favicon: Bitmap?
            ) {

                super.onPageStarted(view, url, favicon)

                webLoader.visibility = View.VISIBLE

                if (!url.isNullOrBlank()) {
                    lastMainUrl = url
                }
            }

            override fun onPageFinished(
                view: WebView,
                url: String
            ) {

                super.onPageFinished(view, url)

                webLoader.visibility = View.GONE

                if (!isYoutubeMode) {

                    if (blockReady.get()) {
                        injectAdShieldJS()
                    } else {
                        injectCoreShieldJS(emptyList())
                    }

                    // Mantém a tentativa de autoplay original.
                    view.postDelayed({
                        if (!isFinishing && !isDestroyed) {
                            injectAutoPlayJS()
                        }
                    }, 1000)
                }
            }

            override fun onReceivedHttpError(
                view: WebView?,
                request: WebResourceRequest?,
                errorResponse: WebResourceResponse?
            ) {

                super.onReceivedHttpError(
                    view,
                    request,
                    errorResponse
                )

                if (request?.isForMainFrame != true) return

                val code = errorResponse?.statusCode ?: return

                if (code == 403 || code == 451) {

                    runOnUiThread {
                        showOperatorBlockedDialog()
                    }
                }
            }

            override fun onReceivedError(
                view: WebView?,
                request: WebResourceRequest,
                error: WebResourceError
            ) {

                super.onReceivedError(view, request, error)

                if (!request.isForMainFrame) return

                if (!isOnline()) {

                    showBlackShieldAndDialog(view)
                    return
                }

                if (
                    error.errorCode == WebViewClient.ERROR_HOST_LOOKUP ||
                    error.errorCode == WebViewClient.ERROR_CONNECT ||
                    error.errorCode == WebViewClient.ERROR_TIMEOUT ||
                    error.errorCode == WebViewClient.ERROR_FAILED_SSL_HANDSHAKE
                ) {

                    showOperatorBlockedDialog()
                }
            }

            @Suppress("deprecation")
            override fun onReceivedError(
                view: WebView?,
                errorCode: Int,
                description: String?,
                failingUrl: String?
            ) {

                super.onReceivedError(
                    view,
                    errorCode,
                    description,
                    failingUrl
                )

                if (!isOnline()) {

                    showBlackShieldAndDialog(view)
                    return
                }

                if (
                    errorCode == WebViewClient.ERROR_HOST_LOOKUP ||
                    errorCode == WebViewClient.ERROR_CONNECT ||
                    errorCode == WebViewClient.ERROR_TIMEOUT ||
                    errorCode == WebViewClient.ERROR_FAILED_SSL_HANDSHAKE
                ) {

                    showOperatorBlockedDialog()
                }
            }

            override fun onReceivedSslError(
                view: WebView?,
                handler: SslErrorHandler?,
                error: SslError?
            ) {
                handler?.proceed()
            }

            // CAPTURA AUTOMÁTICA MPD / M3U8

            override fun shouldInterceptRequest(
                view: WebView,
                request: WebResourceRequest
            ): WebResourceResponse? {

                if (isYoutubeMode) return null

                val url = request.url.toString()

                val host =
                    request.url.host?.lowercase(Locale.ROOT)
                        ?: return null

                if (
                    captureM3u8 &&
                    isStreamManifestUrl(url)
                ) {

                    var shouldOpenPlayer = false

                    synchronized(this@WebViewActivity) {

                        if (!playerOpened) {
                            playerOpened = true
                            shouldOpenPlayer = true
                        }
                    }

                    if (shouldOpenPlayer) {

                        val headers = request.requestHeaders

                        val cookies =
                            CookieManager.getInstance()
                                .getCookie(url)
                                .orEmpty()

                        val referer =
                            getHeader(headers, "Referer")
                                ?: lastMainUrl
                                ?: ""

                        val userAgent =
                            getHeader(headers, "User-Agent")
                                ?: webUserAgent

                        val origin =
                            getHeader(headers, "Origin")
                                ?: ""

                        val method = request.method

                        runOnUiThread {

                            if (isFinishing || isDestroyed) {
                                return@runOnUiThread
                            }

                            val channelName =
                                tvChannels.getOrNull(selectedChannelIndex)?.name
                                    ?: intent.getStringExtra(PlayerActivity.EXTRA_TITLE).orEmpty()

                            val i = Intent(
                                this@WebViewActivity,
                                PlayerActivity::class.java
                            ).apply {

                                putExtra(PlayerActivity.EXTRA_URL, url)

                                putExtra(
                                    "game_title",
                                    intent.getStringExtra("game_title").orEmpty()
                                )

                                putExtra(PlayerActivity.EXTRA_COOKIE, cookies)
                                putExtra(PlayerActivity.EXTRA_REFERER, referer)
                                putExtra(PlayerActivity.EXTRA_USER_AGENT, userAgent)
                                putExtra(PlayerActivity.EXTRA_ORIGIN, origin)
                                putExtra("manifest_method", method)

                                intent.getStringArrayListExtra("tv_channel_names")?.let {
                                    putStringArrayListExtra(
                                        "tv_channel_names",
                                        ArrayList(it)
                                    )
                                }

                                intent.getStringArrayListExtra("tv_channel_urls")?.let {
                                    putStringArrayListExtra(
                                        "tv_channel_urls",
                                        ArrayList(it)
                                    )
                                }

                                intent.getBooleanArrayExtra("tv_channel_capture")?.let {
                                    putExtra("tv_channel_capture", it)
                                }

                                intent.getIntArrayExtra("tv_channel_orders")?.let {
                                    putExtra("tv_channel_orders", it)
                                }

                                putExtra(
                                    "selected_channel_index",
                                    selectedChannelIndex
                                )

                                putExtra(
                                    PlayerActivity.EXTRA_TITLE,
                                    channelName
                                )
                            }

                            startActivity(i)
                            finish()
                        }
                    }

                    return null
                }

                // BLOQUEADOR ORIGINAL

                if (!blockReady.get()) return null

                if (isMediaUrl(url)) return null

                val allow = allowHost

                if (
                    allow != null &&
                    (host == allow || host.endsWith(".$allow"))
                ) {
                    return null
                }

                return if (
                    isBlocked(
                        host,
                        url.lowercase(Locale.ROOT)
                    )
                ) {
                    empty204()
                } else {
                    null
                }
            }

            private fun showBlackShieldAndDialog(view: WebView?) {

                webLoader.visibility = View.GONE

                showBlank(view)

                blackShield.visibility = View.VISIBLE

                showOfflineDialog {

                    blackShield.visibility = View.GONE

                    val retry = lastMainUrl

                    if (isOnline()) {

                        if (retry.isNullOrBlank()) {
                            view?.reload()
                        } else {
                            view?.loadUrl(retry)
                        }
                    }
                }
            }
        }

        web.webChromeClient = object : WebChromeClient() {}

        if (initialUrl.isNotBlank()) {

            lastMainUrl = initialUrl
            web.loadUrl(initialUrl)
        }
    }

    // =====================================================
    // STATUS BAR
    // =====================================================

    private fun hideStatusBar() {

        insets.hide(WindowInsetsCompat.Type.statusBars())

        insets.isAppearanceLightNavigationBars = false
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {

        super.onWindowFocusChanged(hasFocus)

        if (hasFocus) hideStatusBar()
    }

    @Deprecated("Deprecated in Android")
    override fun onBackPressed() {

        if (virtualMouseActive) {
            deactivateVirtualMouse()
            return
        }

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

        cancelChannelAutoHide()
        deactivateVirtualMouse()

        window.clearFlags(
            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
        )

        super.onDestroy()
    }

    override fun onPause() {

        super.onPause()

        deactivateVirtualMouse()
        setWebviewStatus(false)
    }

    override fun onResume() {

        super.onResume()

        if (!isWebviewActive) {
            setWebviewStatus(true)
        }

        if (returningFromSettings) {

            returningFromSettings = false

            operatorDialogOpen = false

            operatorDialog?.dismiss()
            operatorDialog = null

            blackShield.visibility = View.GONE

            if (!lastMainUrl.isNullOrBlank()) {
                web.loadUrl(lastMainUrl!!)
            } else {
                web.reload()
            }
        }
    }

    companion object {
        const val EXTRA_URL = "url"
    }

    // =====================================================
    // BLOCKLIST
    // =====================================================

    private fun loadBlocklist() {

        try {

            val req = Request.Builder()
                .url(blocklistUrl)
                .build()

            client.newCall(req).execute().use { res ->

                val body = res.body?.string().orEmpty()

                parseBlocklist(body)

                blockReady.set(true)
            }

        } catch (_: Exception) {
            // Mantém o comportamento original.
        }
    }

    private fun parseBlocklist(text: String) {

        domainRules.clear()
        substringRules.clear()

        allowDomainRules.clear()
        allowSubstringRules.clear()

        proxyDomainRules.clear()
        proxySubstringRules.clear()

        text.lineSequence().forEach { raw ->

            val line = raw.trim()

            if (line.isEmpty() || line.startsWith("#")) {
                return@forEach
            }

            val isAllow = line.startsWith("per:", true)
            val isProxy = line.startsWith("proxy:", true)

            val ruleText = when {

                isAllow ->
                    line.substringAfter("per:", "").trim()

                isProxy ->
                    line.substringAfter("proxy:", "").trim()

                else -> line
            }

            if (ruleText.isEmpty()) return@forEach

            val rule = ruleText.lowercase(Locale.ROOT)

            val isDomain =
                rule.contains('.') &&
                    !rule.contains(' ') &&
                    !rule.contains('/')

            when {

                isAllow -> {
                    if (isDomain) {
                        allowDomainRules += rule
                    } else {
                        allowSubstringRules += rule
                    }
                }

                isProxy -> {
                    if (isDomain) {
                        proxyDomainRules += rule
                    } else {
                        proxySubstringRules += rule
                    }
                }

                else -> {
                    if (isDomain) {
                        domainRules += rule
                    } else {
                        substringRules += rule
                    }
                }
            }
        }
    }

    private fun isBlocked(
        host: String,
        fullUrlLower: String
    ): Boolean {

        for (d in domainRules) {
            if (host == d || host.endsWith(".$d")) return true
        }

        for (p in substringRules) {
            if (p.isNotEmpty() && fullUrlLower.contains(p)) return true
        }

        return false
    }

    private fun mustProxy(
        host: String,
        fullUrlLower: String
    ): Boolean {

        for (d in proxyDomainRules) {
            if (host == d || host.endsWith(".$d")) return true
        }

        for (p in proxySubstringRules) {
            if (p.isNotEmpty() && fullUrlLower.contains(p)) return true
        }

        return false
    }

    private fun matchesAllowlist(
        host: String,
        fullUrlLower: String
    ): Boolean {

        for (d in allowDomainRules) {
            if (host == d || host.endsWith(".$d")) return true
        }

        for (p in allowSubstringRules) {
            if (p.isNotEmpty() && fullUrlLower.contains(p)) return true
        }

        return false
    }

    // =====================================================
    // IDENTIFICAÇÃO DE MANIFESTOS
    // =====================================================

    private fun isDirectNativeManifest(url: String): Boolean {
        val path = Uri.parse(url)
            .path
            ?.lowercase(Locale.ROOT)
            .orEmpty()

        return path.endsWith(".m3u8") ||
            path.endsWith(".mpd")
    }

    private fun isStreamManifestUrl(url: String): Boolean {
        return isDirectNativeManifest(url)
    }

    private fun isMediaUrl(u: String): Boolean {

        val path = Uri.parse(u)
            .path
            ?.lowercase(Locale.ROOT)
            .orEmpty()

        return path.endsWith(".m3u8") ||
            path.endsWith(".mpd") ||
            path.endsWith(".mp4") ||
            path.endsWith(".webm") ||
            path.endsWith(".ts") ||
            path.endsWith(".m4s") ||
            path.endsWith(".aac") ||
            path.endsWith(".mp3") ||
            path.endsWith(".oga") ||
            path.endsWith(".vtt") ||
            path.endsWith(".srt")
    }

    private fun empty204(): WebResourceResponse {

        return WebResourceResponse(
            "text/plain",
            "utf-8",
            204,
            "No Content",
            emptyMap(),
            ByteArrayInputStream(ByteArray(0))
        )
    }

    // =====================================================
    // JS INJECTIONS
    // =====================================================

    private fun injectAdShieldJS() {

        val tokens =
            (substringRules + domainRules.map { ".$it" })
                .filter { it.isNotBlank() }
                .take(2000)
                .joinToString(
                    separator = "\",\"",
                    prefix = "[\"",
                    postfix = "\"]"
                ) {
                    it.replace("\"", "")
                }

        val js = """
            (function(){
              const LIST = $tokens;

              function isBad(u){
                if(!u) return false;

                u = (""+u).toLowerCase();

                for (let i=0;i<LIST.length;i++){
                  const t = LIST[i];

                  if(!t) continue;

                  if(t.startsWith(".")) {
                    try {
                      const h = new URL(u, location.href).host.toLowerCase();
                      if (h===t.slice(1) || h.endsWith(t)) return true;
                    } catch(e){}
                  } else {
                    if(u.indexOf(t) !== -1) return true;
                  }
                }

                return false;
              }

              window.open = function(){ return null; };

              ['assign','replace'].forEach(k=>{
                const orig = location[k].bind(location);

                location[k] = function(u){
                  if (isBad(u)) return;
                  try{orig(u);}catch(e){}
                };
              });

              Object.defineProperty(window, 'onbeforeunload', {
                get:()=>null,
                set:()=>true
              });

              window.addEventListener('click', function(e){
                let el = e.target;

                while (el && el !== document && !('href' in el)) {
                  el = el.parentElement;
                }

                if (el && el.href && isBad(el.href)) {
                  e.preventDefault();
                }
              }, true);

              const css = `
                [id*="ad"], [class*="ad"], .ads, .adsbox, .advert, .adunit,
                .ad-container, .ad-banner, .ad-overlay {
                  display:none !important;
                  pointer-events:none !important;
                }

                body { overscroll-behavior: contain; }
              `;

              const style = document.createElement('style');

              style.type = 'text/css';

              style.appendChild(document.createTextNode(css));

              document.documentElement.appendChild(style);

              const _setInterval = window.setInterval;

              window.setInterval = function(fn, t){
                if (typeof fn === 'string' && isBad(fn)) return 0;
                return _setInterval(fn, t);
              };
            })();
        """.trimIndent()

        web.evaluateJavascript(js, null)
    }

    private fun injectCoreShieldJS(extraTokens: List<String>) {

        val js = """
            (function(){
              window.open = function(){ return null; };

              ['assign','replace'].forEach(k=>{
                const orig = location[k].bind(location);

                location[k] = function(u){
                  if(!u) return;
                  try{orig(u);}catch(e){}
                };
              });

              const css = `
                .ad, .ads, .ad-overlay {
                  display:none !important;
                  pointer-events:none !important;
                }
              `;

              const style = document.createElement('style');

              style.type = 'text/css';

              style.appendChild(document.createTextNode(css));

              document.documentElement.appendChild(style);
            })();
        """.trimIndent()

        web.evaluateJavascript(js, null)
    }

    // =====================================================
    // AUTOPLAY ORIGINAL
    // =====================================================

    private fun injectAutoPlayJS() {

        val js = """
            (function() {
                if (window.__futaniumAutoPlayStarted) return;
                window.__futaniumAutoPlayStarted = true;

                function tryPlay() {
                    const videos = document.querySelectorAll('video');

                    videos.forEach(function(video) {
                        video.setAttribute('autoplay', '');
                        video.setAttribute('playsinline', '');

                        if (video.paused) {
                            const result = video.play();

                            if (result && result.catch) {
                                result.catch(function(error) {});
                            }
                        }
                    });
                }

                tryPlay();

                document.addEventListener('loadedmetadata', tryPlay, true);
                document.addEventListener('canplay', tryPlay, true);

                const observer = new MutationObserver(function() {
                    tryPlay();
                });

                observer.observe(document.documentElement, {
                    childList: true,
                    subtree: true
                });

                let attempts = 0;

                const timer = setInterval(function() {
                    tryPlay();
                    attempts++;

                    if (attempts >= 20) {
                        clearInterval(timer);
                        observer.disconnect();
                    }
                }, 1000);

            })();
        """.trimIndent()

        web.evaluateJavascript(js, null)
    }

    // =====================================================
    // UTILIDADES
    // =====================================================

    private fun showBlank(view: WebView?) {

        try {
            view?.stopLoading()
        } catch (_: Exception) {}

        try {
            view?.loadDataWithBaseURL(
                "about:blank",
                "",
                "text/html",
                "utf-8",
                null
            )
        } catch (_: Exception) {}
    }

    private fun isOnline(): Boolean {

        val cm =
            getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager

        val net = cm.activeNetwork ?: return false

        val caps = cm.getNetworkCapabilities(net) ?: return false

        return caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) ||
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
    }

    // =====================================================
    // OPERATOR DIALOG
    // =====================================================

    private fun showOperatorBlockedDialog() {

        if (operatorDialogOpen) return

        operatorDialogOpen = true

        operatorDialog = AlertDialog.Builder(this)

            .setTitle("📡 Possível bloqueio da operadora")

            .setMessage(
                "Este player pode estar bloqueado pela sua operadora móvel.\n\n" +
                    "Tente abrir usando Wi-Fi ou uma VPN como o 1.1.1.1 WARP."
            )

            .setNegativeButton("CONFIGURAR WI-FI") { _, _ ->

                returningFromSettings = true

                startActivity(
                    Intent(Settings.ACTION_WIFI_SETTINGS)
                )
            }

            .setNeutralButton("VPN") { _, _ ->

                returningFromSettings = true

                try {

                    val launchIntent =
                        packageManager.getLaunchIntentForPackage(
                            "com.cloudflare.onedotonedotonedotone"
                        )

                    if (launchIntent != null) {

                        startActivity(launchIntent)

                    } else {

                        startActivity(
                            Intent(
                                Intent.ACTION_VIEW,
                                Uri.parse(
                                    "market://details?id=com.cloudflare.onedotonedotonedotone"
                                )
                            )
                        )
                    }

                } catch (_: Exception) {

                    startActivity(
                        Intent(
                            Intent.ACTION_VIEW,
                            Uri.parse(
                                "https://play.google.com/store/apps/details?id=com.cloudflare.onedotonedotonedotone"
                            )
                        )
                    )
                }
            }

            .setPositiveButton("FECHAR") { _, _ ->

                operatorDialogOpen = false
                finish()
            }

            .create()

        operatorDialog?.setCanceledOnTouchOutside(false)

        operatorDialog?.setOnDismissListener {

            operatorDialogOpen = false
            operatorDialog = null
        }

        operatorDialog?.setOnShowListener {

            val c = getColor(R.color.menuColor)

            operatorDialog?.getButton(AlertDialog.BUTTON_POSITIVE)
                ?.setTextColor(c)

            operatorDialog?.getButton(AlertDialog.BUTTON_NEGATIVE)
                ?.setTextColor(c)

            operatorDialog?.getButton(AlertDialog.BUTTON_NEUTRAL)
                ?.setTextColor(c)
        }

        operatorDialog?.show()
    }

    // =====================================================
    // OFFLINE DIALOG
    // =====================================================

    private fun showOfflineDialog(
        onRetry: (() -> Unit)? = null
    ) {

        val d = AlertDialog.Builder(this)

            .setTitle("⚠️ Sem conexão")

            .setMessage(
                "Verifique sua internet e tente novamente."
            )

            .setNegativeButton("CONFIGURAR WI-FI") { _, _ ->

                returningFromSettings = true

                startActivity(
                    Intent(Settings.ACTION_WIFI_SETTINGS)
                )
            }

            .setPositiveButton("TENTAR NOVAMENTE") { _, _ ->

                if (isOnline()) {

                    blackShield.visibility = View.GONE

                    if (!lastMainUrl.isNullOrBlank()) {
                        web.loadUrl(lastMainUrl!!)
                    } else {
                        web.reload()
                    }

                } else {

                    showOfflineDialog(onRetry)
                }
            }

            .create()

        d.setOnShowListener {

            val c = getColor(R.color.menuColor)

            d.getButton(AlertDialog.BUTTON_POSITIVE)
                ?.setTextColor(c)

            d.getButton(AlertDialog.BUTTON_NEGATIVE)
                ?.setTextColor(c)
        }

        d.show()
    }
}
