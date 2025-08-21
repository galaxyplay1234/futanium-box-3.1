package com.futanium.box

import android.annotation.SuppressLint
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.webkit.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import java.io.ByteArrayInputStream
import java.util.Locale

class WebViewActivity : AppCompatActivity() {

    private lateinit var web: WebView
    private lateinit var insets: WindowInsetsControllerCompat

    // Lista simples de hosts e palavras-chave de anúncios (você pode ampliar)
    private val adHosts = setOf(
        "doubleclick.net",
        "googlesyndication.com",
        "googletagservices.com",
        "adservice.google.com",
        "adservice.google.com.br",
        "adnxs.com",
        "taboola.com",
        "outbrain.com",
        "criteo.com",
        "bet365.com",
        "betway.com"
    )

    private val adUrlKeywords = listOf(
        "/ads?", "/ads/", "adserver", "advert", "banner", "popunder",
        "interstitial", "propeller", "push-notification", ".m3u8?ads",
        "preroll", "prebid", "vast", "vmap"
    )

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // conteúdo respeita as barras do sistema; nav bar visível
        WindowCompat.setDecorFitsSystemWindows(window, true)

        // NAV BAR preta visível, ícones claros
        window.navigationBarColor = Color.BLACK
        insets = WindowInsetsControllerCompat(window, window.decorView).apply {
            isAppearanceLightNavigationBars = false
        }
        hideStatusBar() // esconde apenas a status bar

        setContentView(R.layout.activity_webview)

        val url = intent.getStringExtra(EXTRA_URL).orEmpty()

        web = findViewById(R.id.web)
        web.setBackgroundColor(Color.BLACK)
        web.keepScreenOn = true

        with(web.settings) {
            javaScriptEnabled = true
            domStorageEnabled = true
            mediaPlaybackRequiresUserGesture = false

            // Bloquear zoom
            builtInZoomControls = false
            displayZoomControls = false
            setSupportZoom(false)

            useWideViewPort = true
            loadWithOverviewMode = true
            mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW

            // Evitar popups/janelas novas
            setSupportMultipleWindows(false)
            javaScriptCanOpenWindowsAutomatically = false
        }

        web.webViewClient = object : WebViewClient() {

            // 1) Mantém http/https na WebView; joga esquemas externos para fora
            override fun shouldOverrideUrlLoading(
                view: WebView, request: WebResourceRequest
            ): Boolean {
                val u = request.url.toString()
                return if (u.startsWith("http")) {
                    false
                } else {
                    try {
                        startActivity(
                            android.content.Intent(
                                android.content.Intent.ACTION_VIEW,
                                Uri.parse(u)
                            )
                        )
                    } catch (_: Exception) {}
                    true
                }
            }

            // 2) Ad-block: intercepta e retorna resposta vazia para anúncios
            override fun shouldInterceptRequest(
                view: WebView,
                request: WebResourceRequest
            ): WebResourceResponse? {
                val u = request.url.toString()
                if (isAdUrl(u)) {
                    // resposta vazia (text/plain) -> recurso “bloqueado”
                    return WebResourceResponse(
                        "text/plain", "utf-8",
                        ByteArrayInputStream(ByteArray(0))
                    )
                }
                return super.shouldInterceptRequest(view, request)
            }

            // 3) Injeta CSS simples para ocultar banners/overlays comuns
            override fun onPageFinished(view: WebView, url: String) {
                super.onPageFinished(view, url)
                injectAdHiderCss()
            }
        }

        web.webChromeClient = WebChromeClient() // sem barra de progresso

        if (url.isNotBlank()) web.loadUrl(url)
    }

    // Heurística simples: host na lista OU URL contendo palavras de anúncio
    private fun isAdUrl(url: String): Boolean {
        val u = try { Uri.parse(url) } catch (_: Exception) { null } ?: return false
        val host = (u.host ?: "").lowercase(Locale.ROOT)
        if (adHosts.any { host == it || host.endsWith(".$it") }) return true
        val full = url.lowercase(Locale.ROOT)
        if (adUrlKeywords.any { it in full }) return true
        return false
    }

    // CSS genérico para esconder elementos com id/class de anúncio e popups
    private fun injectAdHiderCss() {
        val css = """
            *[id*="ad"], *[class*="ad"],
            *[id*="banner"], *[class*="banner"],
            *[id*="advert"], *[class*="advert"],
            *[id*="popup"], *[class*="popup"],
            [class*="sticky"], [id*="sticky"]
            { display: none !important; }
            html, body { background: #000 !important; }
        """.trimIndent().replace("\n", " ")
            .replace("'", "\\'")
        val js = "javascript:(function(){var s=document.createElement('style');s.innerHTML='$css';document.head.appendChild(s);}())"
        try { web.post { web.loadUrl(js) } } catch (_: Exception) {}
    }

    private fun hideStatusBar() {
        insets.hide(WindowInsetsCompat.Type.statusBars())
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

    companion object { const val EXTRA_URL = "url" }
}