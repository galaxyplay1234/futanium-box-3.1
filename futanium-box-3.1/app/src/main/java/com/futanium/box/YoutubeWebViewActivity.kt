
package com.futanium.box

import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.net.http.SslError
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
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
    private lateinit var contentRoot: FrameLayout

		private var youtubeClosing = false

    private var customView: View? = null
    private var customViewCallback: WebChromeClient.CustomViewCallback? = null

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

    private var gameTitleText: TextView? = null
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

    // =====================================================
    // MOUSE VIRTUAL FUTANIUM
    // =====================================================

    private var virtualMouseActive = false
    private var virtualMouseView: View? = null

    private var mouseX = 0f
    private var mouseY = 0f
    private var mouseMoveToken = 0
    private var mousePositionInitialized = false

    private val mouseSpeed = 48f
    private val mouseDiameter by lazy { dp(28) }

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

    // =====================================================
    // LEITURA DOS CANAIS
    // =====================================================

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

    // =====================================================
    // AUTO HIDE DO MENU
    // =====================================================

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
    // MOUSE VIRTUAL
    // =====================================================

    private fun setupVirtualMouse() {

    val cursor = object : View(this) {

        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {

            color = Color.WHITE

            style = Paint.Style.STROKE

            strokeWidth = dp(3).toFloat()

        }

        override fun onDraw(canvas: Canvas) {

            super.onDraw(canvas)

            val radius = width / 2f - dp(3)

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

    cursor.setWillNotDraw(false)

    // Mantém o cursor acima das outras camadas.

    cursor.elevation = dp(1000).toFloat()

    cursor.translationZ = dp(1000).toFloat()

    virtualMouseView = cursor

    contentRoot.addView(

        cursor,

        FrameLayout.LayoutParams(

            mouseDiameter,

            mouseDiameter

        )

    )

    contentRoot.post {

        initializeMousePosition()

        cursor.bringToFront()

        cursor.invalidate()
}
}

    private fun initializeMousePosition() {

        if (mousePositionInitialized) return

        val location = IntArray(2)
        val contentLocation = IntArray(2)

        web.getLocationOnScreen(location)
        contentRoot.getLocationOnScreen(contentLocation)

        mouseX =
            location[0] - contentLocation[0] + web.width / 2f

        mouseY =
            location[1] - contentLocation[1] + web.height / 2f

        mousePositionInitialized = true
    }

    private fun activateVirtualMouse(keyCode: Int) {

    val cursor = virtualMouseView ?: return

    if (contentRoot.width <= 0 || contentRoot.height <= 0 ||

        web.width <= 0 || web.height <= 0

    ) {

        contentRoot.post {

            activateVirtualMouse(keyCode)

        }

        return

    }

    if (!virtualMouseActive) {

        virtualMouseActive = true

        if (!mousePositionInitialized) {

            initializeMousePosition()

        }

    }

    cursor.visibility = View.VISIBLE

    cursor.bringToFront()

    cursor.elevation = dp(1000).toFloat()

    cursor.translationZ = dp(1000).toFloat()

    updateVirtualMousePosition()

    moveVirtualMouse(keyCode)
    }

    private fun moveVirtualMouse(keyCode: Int) {

        mouseMoveToken++

        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_UP -> mouseY -= mouseSpeed
            KeyEvent.KEYCODE_DPAD_DOWN -> mouseY += mouseSpeed
            KeyEvent.KEYCODE_DPAD_LEFT -> mouseX -= mouseSpeed
            KeyEvent.KEYCODE_DPAD_RIGHT -> mouseX += mouseSpeed
        }

        updateVirtualMousePosition()

        // No vídeo em tela cheia não existe a WebView
        // visível para fazer a atração por JavaScript.
        if (customView == null) {
            snapVirtualMouseToElement()
        }
    }

    
private fun updateVirtualMousePosition() {

    val cursor = virtualMouseView ?: return

    if (contentRoot.width <= 0 || web.width <= 0 || web.height <= 0) return

    val half = mouseDiameter / 2f

    val webLocation = IntArray(2)
    val contentLocation = IntArray(2)

    web.getLocationOnScreen(webLocation)
    contentRoot.getLocationOnScreen(contentLocation)

    val webLeft =
        webLocation[0] - contentLocation[0]

    val webTop =
        webLocation[1] - contentLocation[1]

    val minX = webLeft + half
    val minY = webTop + half

    val maxX = webLeft + web.width - half
    val maxY = webTop + web.height - half

    mouseX = mouseX.coerceIn(minX, maxX)
    mouseY = mouseY.coerceIn(minY, maxY)

    cursor.x = mouseX - half
    cursor.y = mouseY - half

    cursor.visibility = View.VISIBLE
    cursor.bringToFront()
    cursor.invalidate()
}


    private fun snapVirtualMouseToElement() {

        if (!virtualMouseActive || !web.isAttachedToWindow) return

        val token = ++mouseMoveToken

        val webLocation = IntArray(2)
        val contentLocation = IntArray(2)

        web.getLocationOnScreen(webLocation)
        contentRoot.getLocationOnScreen(contentLocation)

        val localX =
            mouseX - (webLocation[0] - contentLocation[0])

        val localY =
            mouseY - (webLocation[1] - contentLocation[1])

        val cssX = localX / web.scale
        val cssY = localY / web.scale

        val js = """
            (function() {

                const x = $cssX;
                const y = $cssY;
                const radius = 45;

                const previous = window.__futaniumLastSnapElement;

                let skipPrevious = false;

                if (previous) {

                    const old = previous.__futaniumOldStyle;

                    if (old) {
                        previous.style.setProperty(
                            'outline',
                            old.outline,
                            old.outlinePriority
                        );

                        previous.style.setProperty(
                            'outline-offset',
                            old.offset,
                            old.offsetPriority
                        );

                        delete previous.__futaniumOldStyle;
                    }

                    const r = previous.getBoundingClientRect();

                    skipPrevious =
                        x >= r.left - radius &&
                        x <= r.right + radius &&
                        y >= r.top - radius &&
                        y <= r.bottom + radius;

                    if (!skipPrevious) {
                        window.__futaniumLastSnapElement = null;
                    }
                }

                const selectors = [
                    'button',
                    'a[href]',
                    'input',
                    'select',
                    'textarea',
                    '[role="button"]',
                    '[role="link"]',
                    '[onclick]',
                    '[tabindex]',
                    '[aria-label]',
                    '.ytp-button',
                    '.ytp-menuitem',
                    '.ytp-progress-bar',
                    '.ytp-volume-panel',
                    '.ytp-settings-button',
                    '.ytp-fullscreen-button',
                    '.ytp-play-button'
                ];

                const elements = document.querySelectorAll(
                    selectors.join(',')
                );

                let best = null;
                let bestElement = null;
                let bestDistance = radius;

                elements.forEach(function(el) {

                    if (skipPrevious && el === previous) {
                        return;
                    }

                    const rect = el.getBoundingClientRect();
                    const style = getComputedStyle(el);

                    if (
                        rect.width <= 0 ||
                        rect.height <= 0 ||
                        style.display === 'none' ||
                        style.visibility === 'hidden' ||
                        style.pointerEvents === 'none'
                    ) {
                        return;
                    }

                    const dx = Math.max(
                        rect.left - x,
                        0,
                        x - rect.right
                    );

                    const dy = Math.max(
                        rect.top - y,
                        0,
                        y - rect.bottom
                    );

                    const distance = Math.sqrt(dx * dx + dy * dy);

                    if (distance < bestDistance) {

                        bestDistance = distance;
                        bestElement = el;

                        best = {
                            x: rect.left + rect.width / 2,
                            y: rect.top + rect.height / 2
                        };
                    }
                });

                if (bestElement) {

                    bestElement.__futaniumOldStyle = {
                        outline: bestElement.style.getPropertyValue('outline'),
                        outlinePriority: bestElement.style.getPropertyPriority('outline'),
                        offset: bestElement.style.getPropertyValue('outline-offset'),
                        offsetPriority: bestElement.style.getPropertyPriority('outline-offset')
                    };

                    bestElement.style.setProperty(
                        'outline',
                        '3px solid #1685FF',
                        'important'
                    );

                    bestElement.style.setProperty(
                        'outline-offset',
                        '3px',
                        'important'
                    );

                    window.__futaniumLastSnapElement = bestElement;
                }

                return best;
            })();
        """.trimIndent()

        web.evaluateJavascript(js) { result ->

            if (
                result == null ||
                result == "null" ||
                token != mouseMoveToken ||
                !virtualMouseActive ||
                customView != null
            ) {
                return@evaluateJavascript
            }

            try {

                val point = org.json.JSONObject(result)

                val currentWebLocation = IntArray(2)
                val currentContentLocation = IntArray(2)

                web.getLocationOnScreen(currentWebLocation)
                contentRoot.getLocationOnScreen(currentContentLocation)

                val webLeft =
                    currentWebLocation[0] - currentContentLocation[0]

                val webTop =
                    currentWebLocation[1] - currentContentLocation[1]

                mouseX =
                    webLeft +
                    point.getDouble("x").toFloat() * web.scale

                mouseY =
                    webTop +
                    point.getDouble("y").toFloat() * web.scale

                updateVirtualMousePosition()

            } catch (_: Exception) {
                // Mantém o cursor livre.
            }
        }
    }

    private fun clickVirtualMouse() {

        val target = customView ?: web

        val targetLocation = IntArray(2)
        val contentLocation = IntArray(2)

        target.getLocationOnScreen(targetLocation)
        contentRoot.getLocationOnScreen(contentLocation)

        val localX =
            mouseX -
            (targetLocation[0] - contentLocation[0])

        val localY =
            mouseY -
            (targetLocation[1] - contentLocation[1])

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

        down.source = InputDevice.SOURCE_TOUCHSCREEN
        up.source = InputDevice.SOURCE_TOUCHSCREEN

        target.dispatchTouchEvent(down)
        target.dispatchTouchEvent(up)

        down.recycle()
        up.recycle()
    }

    private fun deactivateVirtualMouse() {

        virtualMouseActive = false
        mouseMoveToken++

        if (::web.isInitialized) {
            web.evaluateJavascript("""
                (function() {

                    const el = window.__futaniumLastSnapElement;

                    if (el && el.__futaniumOldStyle) {

                        const old = el.__futaniumOldStyle;

                        el.style.setProperty(
                            'outline',
                            old.outline,
                            old.outlinePriority
                        );

                        el.style.setProperty(
                            'outline-offset',
                            old.offset,
                            old.offsetPriority
                        );

                        delete el.__futaniumOldStyle;
                    }

                    window.__futaniumLastSnapElement = null;
                })();
            """.trimIndent(), null)
        }

        virtualMouseView?.visibility = View.GONE
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

        gameTitleText = createText(
            intent.getStringExtra("game_title").orEmpty(),
            21f,
            Color.WHITE,
            true
        ).apply {
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        }

        channelNameText = createText(
            "",
            17f,
            Color.LTGRAY,
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

        gameTitleText?.text =
            intent.getStringExtra("game_title").orEmpty()

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
        deactivateVirtualMouse()

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

                putExtra(
                    "game_title",
                    intent.getStringExtra("game_title").orEmpty()
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

        target.putExtra(
            "game_title",
            intent.getStringExtra("game_title").orEmpty()
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

                if (virtualMouseActive) {
    deactivateVirtualMouse()
    return true
}

                if (channelPanelVisible) {
                    closeChannelPanel()
                    return true
                }

                if (channelControlsVisible) {
                    hideChannelControls()
                    return true
                }

                if (customView != null) {
                    (web.webChromeClient as? WebChromeClient)
                    return super.dispatchKeyEvent(event)
                }
            }

            KeyEvent.KEYCODE_DPAD_UP,
            KeyEvent.KEYCODE_DPAD_DOWN -> {

                if (channelPanelVisible) {

                    if (event.keyCode == KeyEvent.KEYCODE_DPAD_UP) {
                        selectedChannelIndex =
                            (selectedChannelIndex - 1).coerceAtLeast(0)
                    } else {
                        selectedChannelIndex =
                            (selectedChannelIndex + 1)
                                .coerceAtMost(tvChannels.lastIndex)
                    }

                    updateChannelList()
                    focusSelectedChannel()

                    return true
                }

                if (channelControlsVisible) {
                    return super.dispatchKeyEvent(event)
                }

                activateVirtualMouse(event.keyCode)
                return true
            }

            KeyEvent.KEYCODE_DPAD_LEFT,
            KeyEvent.KEYCODE_DPAD_RIGHT -> {

                if (channelPanelVisible || channelControlsVisible) {
                    return super.dispatchKeyEvent(event)
                }

                activateVirtualMouse(event.keyCode)
                return true
            }

            KeyEvent.KEYCODE_DPAD_CENTER,
            KeyEvent.KEYCODE_ENTER,
            KeyEvent.KEYCODE_NUMPAD_ENTER -> {

                if (virtualMouseActive) {
    clickVirtualMouse()
    deactivateVirtualMouse()
    return true
}

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

        contentRoot = findViewById(android.R.id.content)

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

            override fun onShowCustomView(
                view: View?,
                callback: CustomViewCallback?
            ) {

                if (view == null) return

                if (customView != null) {
                    callback?.onCustomViewHidden()
                    return
                }

                customView = view
                customViewCallback = callback

                (view.parent as? ViewGroup)?.removeView(view)

                contentRoot.addView(
                    view,
                    FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT,
                        FrameLayout.LayoutParams.MATCH_PARENT
                    )
                )

                virtualMouseView?.bringToFront()

                hideSystemBars()
            }

            override fun onHideCustomView() {

                val view = customView

                if (view != null) {
                    (view.parent as? ViewGroup)?.removeView(view)
                }

                customView = null

                customViewCallback?.onCustomViewHidden()
                customViewCallback = null

                virtualMouseView?.bringToFront()

                hideSystemBars()
            }
        }

        setupChannelInterface()
        setupVirtualMouse()

        val url = intent.getStringExtra(EXTRA_URL).orEmpty()

        if (url.isNotBlank()) {
            web.loadUrl(url)
        }
    }

    // =====================================================
    // BARRAS DO SISTEMA
    // =====================================================

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

    // =====================================================
    // VOLTAR
    // =====================================================

private fun closeYoutubeCompletely() {

    if (youtubeClosing || isFinishing) return

    youtubeClosing = true

    cancelAutoHide()
    deactivateVirtualMouse()

    channelControlsVisible = false
    channelPanelVisible = false

    if (::web.isInitialized) {

        web.onPause()

        web.evaluateJavascript(
            """
            (function() {
                document.querySelectorAll('video, audio').forEach(function(media) {
                    media.pause();
                    media.removeAttribute('src');
                    media.load();
                });
            })();
            """.trimIndent()
        ) {
            web.stopLoading()
            finish()
        }

    } else {
        finish()
    }
}



    @Deprecated("Deprecated in Android")
override fun onBackPressed() {

    if (youtubeClosing) return

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

    if (customView != null) {
        super.onBackPressed()
        return
    }

    if (web.canGoBack()) {
        web.goBack()
    } else {
        closeYoutubeCompletely()
    }
}

    // =====================================================
    // DESTRUIÇÃO
    // =====================================================

    override fun onDestroy() {

    cancelAutoHide()

    virtualMouseActive = false
    mouseMoveToken++

    customView?.let {
        (it.parent as? ViewGroup)?.removeView(it)
    }

    customViewCallback?.onCustomViewHidden()

    customView = null
    customViewCallback = null

    if (::web.isInitialized) {
        web.stopLoading()
        web.onPause()

        (web.parent as? ViewGroup)?.removeView(web)
        web.removeAllViews()
        web.destroy()
    }

    super.onDestroy()
}
