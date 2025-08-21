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
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.ByteArrayInputStream
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

class WebViewActivity : AppCompatActivity() {

    private lateinit var web: WebView
    private lateinit var insets: WindowInsetsControllerCompat

    // --- BLOQUEIO (somente o necessário) ---
    private val client = OkHttpClient()
    private val blocklistUrl =
        "https://raw.githubusercontent.com/galaxyplay1234/bloqueio-ads-futanium/refs/heads/main/blocklist.txt"

    private val domainRules = HashSet<String>()      // domínios exatos
    private val substringRules = ArrayList<String>() // trechos na URL

    // host permitido (da página principal) para nunca bloquear
    private var allowHost: String? = null

    // evita bloquear antes da lista carregar
    private val blockReady = AtomicBoolean(false)
    // -------------------------------------------------------------

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Conteúdo respeita as barras do sistema (nav bar visível)
        WindowCompat.setDecorFitsSystemWindows(window, true)

        // NAV BAR preta visível, com ícones claros
        window.navigationBarColor = Color.BLACK
        insets = WindowInsetsControllerCompat(window, window.decorView).apply {
            isAppearanceLightNavigationBars = false // ícones brancos
        }

        // STATUS BAR: esconder
        hideStatusBar()

        setContentView(R.layout.activity_webview)

        val initialUrl = intent.getStringExtra(EXTRA_URL).orEmpty()
        allowHost = runCatching { Uri.parse(initialUrl).host?.lowercase(Locale.ROOT) }.getOrNull()

        // carrega blocklist em background (se falhar, segue sem bloquear)
        Thread { loadBlocklist() }.start()

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

            // Forçar layout MOBILE (controles maiores)
            useWideViewPort = false
            loadWithOverviewMode = false

            // User-Agent de celular (Chrome Android)
            userAgentString =
                "Mozilla/5.0 (Linux; Android 13; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"

            mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW

            // 🔒 Bloqueio de pop-ups / _blank_
            setSupportMultipleWindows(false)
            javaScriptCanOpenWindowsAutomatically = false
        }

        web.webViewClient = object : WebViewClient() {

            override fun shouldOverrideUrlLoading(
                view: WebView,
                request: WebResourceRequest
            ): Boolean {
                val u = request.url.toString()
                return if (u.startsWith("http")) {
                    false // abre dentro da WebView
                } else {
                    // esquemas externos (intent:, whatsapp:, go:, etc.)
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

            // Atualiza o host "permitido" quando a página principal muda
            override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                super.onPageStarted(view, url, favicon)
                url?.let {
                    allowHost = runCatching { Uri.parse(it).host?.lowercase(Locale.ROOT) }.getOrNull()
                }
            }

            // Bloqueio fino: só em recursos secundários (nunca no frame principal)
            override fun shouldInterceptRequest(
                view: WebView,
                request: WebResourceRequest
            ): WebResourceResponse? {
                // não bloquear navegação principal
                if (request.isForMainFrame) return null

                // se blocklist ainda não carregou, não bloqueia
                if (!blockReady.get()) return null

                val url = request.url.toString()
                val host = request.url.host?.lowercase(Locale.ROOT) ?: return null

                // ✅ NÃO bloquear mídia do player (whitelist por extensão)
                val lower = url.lowercase(Locale.ROOT)
                if (isMediaUrl(lower)) return null

                // nunca bloquear o host da página principal
                val allow = allowHost
                if (allow != null && (host == allow || host.endsWith(".$allow"))) {
                    return null
                }

                if (isBlocked(host, lower)) {
                    // retorna 204 "sem conteúdo" para cortar o recurso sem quebrar player
                    return WebResourceResponse(
                        "text/plain",
                        "utf-8",
                        204,
                        "No Content",
                        emptyMap(),
                        ByteArrayInputStream(ByteArray(0))
                    )
                }
                return null
            }
        }

        // 🔒 Cancela qualquer tentativa de abrir nova janela (pop-up)
        web.webChromeClient = object : WebChromeClient() {
            override fun onCreateWindow(
                view: WebView?,
                isDialog: Boolean,
                isUserGesture: Boolean,
                resultMsg: android.os.Message?
            ): Boolean {
                return false // bloqueia window.open / target=_blank
            }
        }

        if (initialUrl.isNotBlank()) web.loadUrl(initialUrl)
    }

    private fun hideStatusBar() {
        // esconde apenas a status bar (nav bar continua visível)
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

    companion object {
        const val EXTRA_URL = "url"
    }

    // ====================== BLOQUEIO: helpers ======================

    private fun loadBlocklist() {
        try {
            val req = Request.Builder().url(blocklistUrl).build()
            client.newCall(req).execute().use { res ->
                val body = res.body?.string().orEmpty()
                parseBlocklist(body)
                blockReady.set(true)
            }
        } catch (_: Exception) {
            // falhou -> sem bloqueio (não toca em blockReady)
        }
    }

    private fun parseBlocklist(text: String) {
        domainRules.clear()
        substringRules.clear()

        text.lineSequence().forEach { rawLine ->
            val line = rawLine.trim()
            if (line.isEmpty() || line.startsWith("#")) return@forEach

            val rule = line.lowercase(Locale.ROOT)

            // Se parece domínio (tem ponto, sem espaço e sem barra) -> domínio
            val isDomain = rule.contains('.') && !rule.contains(' ') && !rule.contains('/')

            if (isDomain) {
                domainRules += rule
            } else {
                substringRules += rule
            }
        }
    }

    private fun isBlocked(host: String, fullUrlLower: String): Boolean {
        // domínio exato ou subdomínio
        for (d in domainRules) {
            if (host == d || host.endsWith(".$d")) return true
        }
        // trechos na URL completa (case-insensitive)
        for (p in substringRules) {
            if (p.isNotEmpty() && fullUrlLower.contains(p)) return true
        }
        return false
    }

    // Permite URLs típicas de mídia para não quebrar o player
    private fun isMediaUrl(u: String): Boolean {
        return u.endsWith(".m3u8") ||
               u.endsWith(".mpd")  ||
               u.endsWith(".ts")   ||
               u.endsWith(".m4s")  ||
               u.endsWith(".mp4")  ||
               u.endsWith(".webm") ||
               u.endsWith(".aac")  ||
               u.endsWith(".mp3")  ||
               u.endsWith(".oga")  ||
               u.endsWith(".vtt")  ||
               u.endsWith(".srt")
    }
}