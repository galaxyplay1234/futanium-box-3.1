package com.futanium.box

import android.annotation.SuppressLint
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

class WebViewActivity : AppCompatActivity() {

    private lateinit var web: WebView
    private lateinit var insets: WindowInsetsControllerCompat

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Deixe o conteúdo respeitar as barras do sistema (nav bar visível)
        WindowCompat.setDecorFitsSystemWindows(window, true)

        // NAV BAR preta visível, com ícones claros
        window.navigationBarColor = Color.BLACK
        insets = WindowInsetsControllerCompat(window, window.decorView).apply {
            isAppearanceLightNavigationBars = false // ícones brancos
        }

        // STATUS BAR: esconder
        hideStatusBar()

        setContentView(R.layout.activity_webview)

        val url = intent.getStringExtra(EXTRA_URL).orEmpty()

        web = findViewById(R.id.web)
        web.setBackgroundColor(Color.BLACK)
        web.keepScreenOn = true // mantém a tela ligada

        with(web.settings) {
            javaScriptEnabled = true
            domStorageEnabled = true
            mediaPlaybackRequiresUserGesture = false

            // Bloquear zoom
            builtInZoomControls = false
            displayZoomControls = false
            setSupportZoom(false)

            // Responsivo
            useWideViewPort = true
            loadWithOverviewMode = true

            mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
        }

        web.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(
                view: WebView,
                request: WebResourceRequest
            ): Boolean {
                val u = request.url.toString()
                return if (u.startsWith("http")) {
                    false // fica na WebView
                } else {
                    try {
                        startActivity(
                            android.content.Intent(
                                android.content.Intent.ACTION_VIEW,
                                Uri.parse(u)
                            )
                        )
                    } catch (_: Exception) { }
                    true
                }
            }
        }
        web.webChromeClient = WebChromeClient() // sem barra/progresso

        if (url.isNotBlank()) web.loadUrl(url)
    }

    private fun hideStatusBar() {
        // esconde apenas a status bar (nav bar continua visível)
        insets.hide(WindowInsetsCompat.Type.statusBars())
        // Garante ícones da nav bar claros
        insets.isAppearanceLightNavigationBars = false
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideStatusBar()
    }

    override fun onBackPressed() {
        if (this::web.isInitialized && web.canGoBack()) {
            web.goBack()
        } else {
            super.onBackPressed()
        }
    }

    companion object {
        const val EXTRA_URL = "url"
    }
}