package com.futanium.box

import android.annotation.SuppressLint
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.webkit.*
import androidx.appcompat.app.AppCompatActivity
import java.io.ByteArrayInputStream

class WebViewActivity : AppCompatActivity() {

    private lateinit var web: WebView

    // 1) WHITELIST: só esses hosts podem navegar/carregar recursos
    private val NAV_WHITELIST = setOf(
        "seu-player.com",
        "cdn.seu-player.com"
        // add aqui outros hosts que o player usa de verdade
    )
    private val RESOURCE_BLOCKLIST_PARTIAL = listOf(
        // pedaços comuns de domínios de anúncio
        "doubleclick.net", "googlesyndication.com", "adservice.google.com",
        "taboola", "outbrain", "popads", "propeller", "adcash", "adnxs.com",
        "revcontent", "exoclick", "trkn", "cpm", "clk", "bet", "aff"
    )

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // fullscreen imersivo
        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE
            or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
            or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
            or View.SYSTEM_UI_FLAG_FULLSCREEN
            or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
            or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        )

        setContentView(R.layout.activity_webview)

        val url = intent.getStringExtra(EXTRA_URL).orEmpty()

        web = findViewById(R.id.web)
        web.setBackgroundColor(Color.BLACK)

        with(web.settings) {
            javaScriptEnabled = true
            domStorageEnabled = true
            mediaPlaybackRequiresUserGesture = false

            // sem zoom
            setSupportZoom(false)
            builtInZoomControls = false
            displayZoomControls = false

            // responsivo
            loadWithOverviewMode = true
            useWideViewPort = true

            mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW

            // bloquear novas janelas/abas
            setSupportMultipleWindows(false)
        }

        web.webViewClient = object : WebViewClient() {

            // 2) BLOQUEIA NAVEGAÇÃO fora da whitelist
            override fun shouldOverrideUrlLoading(
                view: WebView, request: WebResourceRequest
            ): Boolean {
                val u = request.url
                // Permite apenas http/https dentro da própria WebView
                if (u.scheme == "http" || u.scheme == "https") {
                    val host = (u.host ?: "").lowercase()
                    return if (hostAllowed(host)) {
                        false // deixa carregar dentro da WebView
                    } else {
                        true  // bloqueia navegação externa
                    }
                } else {
                    // esquemas externos (whatsapp:, intent:, go:, market:, etc.) -> bloqueia
                    return true
                }
            }

            // 3) BLOQUEIA RECURSOS de domínios de anúncio (scripts/iframes)
            override fun shouldInterceptRequest(
                view: WebView, request: WebResourceRequest
            ): WebResourceResponse? {
                val u = request.url
                val host = (u.host ?: "").lowercase()

                // se o host não for permitido, bloqueia o recurso
                if (!hostAllowed(host)) {
                    return emptyResponse()
                }

                // se parecer domínio de anúncio, bloqueia
                val full = u.toString().lowercase()
                if (RESOURCE_BLOCKLIST_PARTIAL.any { full.contains(it) }) {
                    return emptyResponse()
                }

                return super.shouldInterceptRequest(view, request)
            }

            override fun onPageFinished(view: WebView, url: String) {
                super.onPageFinished(view, url)

                // 4) "Mata" window.open e links com target=_blank
                val js = """
                    (function(){
                      try {
                        window.open = function(){ return null; };
                        document.querySelectorAll('a[target="_blank"]').forEach(function(a){
                          a.removeAttribute('target');
                        });
                        // Evita onclick que abre popunder
                        document.body.addEventListener('click', function(e){
                          // bloqueia se tentar chamar window.open
                          if (typeof e.target.onclick === 'function') {
                            var s = e.target.onclick.toString();
                            if (s.indexOf('window.open(') !== -1) { e.preventDefault(); e.stopPropagation(); }
                          }
                        }, true);
                      } catch(e){}
                    })();
                """.trimIndent()
                view.evaluateJavascript(js, null)
            }
        }

        // 5) Bloqueia criação de novas janelas (popups)
        web.webChromeClient = object : WebChromeClient() {
            override fun onCreateWindow(
                view: WebView?, isDialog: Boolean, isUserGesture: Boolean, resultMsg: android.os.Message?
            ): Boolean {
                // negar popup sempre
                return false
            }
        }

        if (url.isNotBlank()) web.loadUrl(url)
    }

    private fun hostAllowed(host: String): Boolean {
        return NAV_WHITELIST.any { allowed ->
            host == allowed || host.endsWith(".$allowed")
        }
    }

    private fun emptyResponse(): WebResourceResponse {
        return WebResourceResponse(
            "text/plain", "utf-8",
            ByteArrayInputStream(ByteArray(0))
        ).apply {
            // opcional: 204 No Content “fake”
            setStatusCodeAndReasonPhrase(204, "No Content")
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE
            or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
            or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
            or View.SYSTEM_UI_FLAG_FULLSCREEN
            or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
            or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        )
    }

    override fun onBackPressed() {
        if (this::web.isInitialized && web.canGoBack()) {
            web.goBack()
        } else {
            super.onBackPressed()
        }
    }

    companion object { const val EXTRA_URL = "url" }
}