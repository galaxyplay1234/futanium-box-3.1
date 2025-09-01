package com.futanium.box

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.webkit.WebSettings
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

class WebViewActivity : AppCompatActivity() {

    private lateinit var web: WebView
    private lateinit var insets: WindowInsetsControllerCompat
    private lateinit var webLoader: android.widget.ProgressBar

    // ====== Listas “duras” (mesmo conceito do B4A) ======
    private val blockedHosts = setOf(
        "googlesyndication.com", "doubleclick.net", "adnxs.com", "taboola.com",
        "outbrain.com", "popads.net", "propellerads.com", "exdynsrv.com",
        "adskeeper.com", "adform.net", "trafficjunky.net", "revcontent.com"
    )
    private val blockedPathParts = listOf(
        "/ads", "/adserver", "/advert", "/banner", "/popup", "/popunder",
        "redirect", "/clk", "ads?"
    )

    private fun shouldBlockUrl(u: String): Boolean {
        val uLower = u.lowercase()
        val host = runCatching { Uri.parse(uLower).host ?: "" }.getOrNull() ?: ""

        // 1) hosts de ads conhecidos
        if (blockedHosts.any { host == it || host.endsWith(".$it") }) return true

        // 2) palavras/trechos comuns de ads no path/query
        val uri = runCatching { Uri.parse(uLower) }.getOrNull()
        val pathq = ((uri?.path ?: "") + "?" + (uri?.query ?: "")).lowercase()
        if (blockedPathParts.any { it in pathq }) return true

        // 3) esquemas de redirect/externos que você já bloqueava no B4A
        if (uLower.startsWith("intent:") ||
            uLower.startsWith("mailto:") ||
            uLower.startsWith("tel:") ||
            uLower.startsWith("sms:")) return true

        return false
    }
    // =====================================================

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Status bar/Nav bar pretas (como antes)
        WindowCompat.setDecorFitsSystemWindows(window, true)
        window.statusBarColor = Color.BLACK
        window.navigationBarColor = Color.BLACK
        insets = WindowInsetsControllerCompat(window, window.decorView).apply {
            systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            isAppearanceLightStatusBars = false
            isAppearanceLightNavigationBars = false
        }
        hideStatusBar()

        setContentView(R.layout.activity_webview)
        webLoader = findViewById(R.id.webLoader)

        // Mantém a tela ligada enquanto estiver no player
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val initialUrl = intent.getStringExtra(EXTRA_URL).orEmpty()

        web = findViewById(R.id.web)
        web.setBackgroundColor(Color.BLACK)
        web.keepScreenOn = true

        // Ajuste de insets para não ficar atrás da nav bar (mesmo que você já usava)
        val contentRoot = findViewById<ViewGroup>(android.R.id.content).getChildAt(0) ?: web
        ViewCompat.setOnApplyWindowInsetsListener(contentRoot) { v, ins ->
            val nav = ins.getInsets(WindowInsetsCompat.Type.navigationBars())
            v.setPadding(v.paddingLeft, v.paddingTop, v.paddingRight, nav.bottom)
            ins
        }
        ViewCompat.setOnApplyWindowInsetsListener(web) { v, ins ->
            val nav = ins.getInsets(WindowInsetsCompat.Type.navigationBars())
            v.setPadding(v.paddingLeft, v.paddingTop, v.paddingRight, nav.bottom)
            ins
        }
        ViewCompat.requestApplyInsets(contentRoot)
        ViewCompat.requestApplyInsets(web)

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

            mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
        }

        web.webViewClient = object : WebViewClient() {

            // === BLOQUEIO SOMENTE NA NAVEGAÇÃO PRINCIPAL (como no B4A) ===
            override fun shouldOverrideUrlLoading(
                view: WebView,
                request: WebResourceRequest
            ): Boolean {
                val url = request.url.toString()

                // NUNCA bloqueia mídia/recursos essenciais
                if (url == "about:blank" ||
                    url.startsWith("blob:") ||
                    url.startsWith("data:")) return false

                // Se a URL casar com as regras, não carrega (bloqueia)
                if (shouldBlockUrl(url)) return true

                // Caso contrário, deixa seguir
                return false
            }

            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                super.onPageStarted(view, url, favicon)
                webLoader.visibility = View.VISIBLE
            }

            override fun onPageFinished(view: WebView, url: String) {
                super.onPageFinished(view, url)
                webLoader.visibility = View.GONE

                // === JS mínimo do B4A: mata popup e força target=_self ===
                val js = """
                    (function(){
                      try {
                        window.open = function(){ return null; };
                        document.querySelectorAll('a[target="_blank"]').forEach(function(a){
                          a.setAttribute('target','_self');
                        });
                      } catch(e){}
                    })();
                """.trimIndent()
                view.evaluateJavascript(js, null)
            }
        }

        web.webChromeClient = object : WebChromeClient() {
            // Bloqueia window.open/abas novas
            override fun onCreateWindow(
                view: WebView?,
                isDialog: Boolean,
                isUserGesture: Boolean,
                resultMsg: android.os.Message?
            ): Boolean {
                return false
            }
        }

        if (initialUrl.isNotBlank()) {
            web.loadUrl(initialUrl)
        }
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
        finish()
    }

    override fun onDestroy() {
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        super.onDestroy()
    }

    companion object {
        const val EXTRA_URL = "url"
    }
}