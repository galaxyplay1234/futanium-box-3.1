package com.futanium.box

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.graphics.Color
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.*
import android.webkit.SslErrorHandler
import android.net.http.SslError
import android.content.Intent
import android.provider.Settings
import android.widget.FrameLayout
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

class WebViewActivity : AppCompatActivity() {

    private lateinit var web: WebView
    private lateinit var insets: WindowInsetsControllerCompat
    // Loader central (igual ao do player)
    private lateinit var webLoader: android.widget.ProgressBar

    // --- overlay preto para esconder erros / URL ---
    private lateinit var blackShield: View
    // última URL principal visitada (para "Tentar novamente")
    private var lastMainUrl: String? = null

    // --- BLOQUEIO (lista remota) ---
    private val client = OkHttpClient()
    private val blocklistUrl =
        "https://raw.githubusercontent.com/galaxyplay1234/bloqueio-ads-futanium/refs/heads/main/blocklist.txt"

    private val domainRules = HashSet<String>()      // domínios exatos (bloqueio)
    private val substringRules = ArrayList<String>() // trechos na URL (bloqueio)

    // >>> ALLOWLIST (linhas iniciadas com "per:")
    private val allowDomainRules = HashSet<String>()      // domínios permitidos (navegação principal)
    private val allowSubstringRules = ArrayList<String>() // trechos permitidos (navegação principal)

    // >>> PROXY (linhas iniciadas com "proxy:")
    private val proxyDomainRules = HashSet<String>()      // domínios que devem ir via proxy
    private val proxySubstringRules = ArrayList<String>() // trechos que devem ir via proxy
    private val PROXY_BASE = "https://controledeestoque.rf.gd/proxy.php?url="

    private var allowHost: String? = null            // host/eTLD+1 do player atual
    private val blockReady = AtomicBoolean(false)
    // --------------------------------

    // === MODO ENC. (bit.ly) ===
    private var shortenerActive: Boolean = false
    private fun isShortener(url: String, host: String?): Boolean {
        val u = url.lowercase(Locale.ROOT)
        val h = (host ?: "").lowercase(Locale.ROOT)
        // bit.ly direto OU bit.ly aparecendo no caminho (ex.: rf.gd/bit.ly/...)
        return h == "bit.ly" || u.contains("/bit.ly/") || u.contains("://bit.ly/")
    }
    // ==========================

    // ----- Suporte a vídeo/fullscreen (para evitar tela preta em YouTube) -----
    private var customVideoView: View? = null
    private var customViewCallback: WebChromeClient.CustomViewCallback? = null
    private var fullScreenContainer: FrameLayout? = null
    private fun isInFullscreen(): Boolean = customVideoView != null
    // --------------------------------------------------------------------------

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Garante aceleração de hardware na janela
        window.setFlags(
            WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED
        )

        // Status bar translúcida (aparece só ao puxar). Nav bar fica fixa e preta.
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

        // 👇 encontra o loader
        webLoader = findViewById(R.id.webLoader)

        // Mantém a tela ligada (reforço além do keepScreenOn da WebView)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val initialUrl = intent.getStringExtra(EXTRA_URL).orEmpty()
        val initHost = runCatching { Uri.parse(initialUrl).host?.lowercase(Locale.ROOT) }.getOrNull()
        allowHost = initHost

        // ativa modo encurtador se a URL inicial já for encurtada
        if (isShortener(initialUrl, initHost)) shortenerActive = true

        // carrega blocklist em background
        Thread { loadBlocklist() }.start()

        web = findViewById(R.id.web)
        web.setBackgroundColor(Color.BLACK)
        web.keepScreenOn = true

        // força renderização acelerada no componente
        web.setLayerType(View.LAYER_TYPE_HARDWARE, null)

        // 🔒 bloqueia long-press/seleção (não deixa copiar nada de telas de erro internas)
        web.isLongClickable = false
        web.setOnLongClickListener { true }
        web.isHapticFeedbackEnabled = false

        // === NÃO ficar atrás da navigation bar ===
        // 1) Aplica padding bottom no root do conteúdo da Activity.
        val contentRoot = findViewById<ViewGroup>(android.R.id.content).getChildAt(0) ?: web
        ViewCompat.setOnApplyWindowInsetsListener(contentRoot) { v, ins ->
            val nav = ins.getInsets(WindowInsetsCompat.Type.navigationBars())
            v.setPadding(v.paddingLeft, v.paddingTop, v.paddingRight, nav.bottom)
            ins
        }
        // 2) Garante também na própria WebView (caso o layout seja diferente).
        ViewCompat.setOnApplyWindowInsetsListener(web) { v, ins ->
            val nav = ins.getInsets(WindowInsetsCompat.Type.navigationBars())
            v.setPadding(v.paddingLeft, v.paddingTop, v.paddingRight, nav.bottom)
            ins
        }
        // 3) Dispara a aplicação dos insets agora.
        ViewCompat.requestApplyInsets(contentRoot)
        ViewCompat.requestApplyInsets(web)
        // =========================================

        // --- overlay preto full-screen (inicialmente invisível) ---
        blackShield = View(this).apply {
            setBackgroundColor(Color.BLACK)
            visibility = View.GONE
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }
        (contentRoot as ViewGroup).addView(blackShield)

        with(web.settings) {
            javaScriptEnabled = true
            domStorageEnabled = true
            mediaPlaybackRequiresUserGesture = false

            // pop-ups/abas novas
            setSupportMultipleWindows(false)
            javaScriptCanOpenWindowsAutomatically = false

            // zoom OFF
            builtInZoomControls = false
            displayZoomControls = false
            setSupportZoom(false)

            // Força layout MOBILE (controles grandes)
            useWideViewPort = false
            loadWithOverviewMode = false

            // User-Agent de celular (Chrome Android)
            userAgentString =
                "Mozilla/5.0 (Linux; Android 13; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"

            mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            // Opcionalmente melhora vídeo:
            mediaPlaybackRequiresUserGesture = false
            allowContentAccess = true
            allowFileAccess = true
        }

        web.webViewClient = object : WebViewClient() {

            // BLOQUEIO de navegação principal (redirecionamentos/clicks que trocam a página)
            override fun shouldOverrideUrlLoading(
                view: WebView,
                request: WebResourceRequest
            ): Boolean {
                val uri = request.url
                val u = uri.toString()
                val uLower = u.lowercase(Locale.ROOT)

                // guarda a última URL principal tentada
                lastMainUrl = u

                // Sempre permite blobs/dados/mídia na própria guia
                if (isMediaUrl(u) || u.startsWith("blob:") || u.startsWith("data:")) return false

                // Evita travar players que usam about:blank no main-frame
                if (u == "about:blank") return false

                // Esquemas externos -> fora da WebView
                if (u.startsWith("intent://") || u.startsWith("market://")
                    || u.startsWith("mailto:") || u.startsWith("tel:")
                    || u.startsWith("sms:")) {
                    return try {
                        startActivity(
                            Intent(
                                Intent.ACTION_VIEW,
                                Uri.parse(u)
                            )
                        )
                        true
                    } catch (_: Exception) { true }
                }

                // http/https
                if (u.startsWith("http")) {
                    // se estiver sem internet, não carrega nada: mostra overlay + popup
                    if (!isOnline()) {
                        blackShield.visibility = View.VISIBLE
                        showOfflineDialog {
                            blackShield.visibility = View.GONE
                            val retry = lastMainUrl ?: u
                            if (isOnline()) view.loadUrl(retry)
                        }
                        return true
                    }

                    val host = uri.host?.lowercase(Locale.ROOT) ?: return true

                    // (A) Se estiver marcado para PROXY no blocklist, reescreve antes de carregar
                    if (mustProxy(host, uLower) && !uLower.startsWith(PROXY_BASE)) {
                        view.loadUrl(PROXY_BASE + Uri.encode(u))
                        return true
                    }

                    // 0) Se é encurtador (bit.ly), permite e mantém shortenerActive ligado
                    if (isShortener(u, host)) {
                        shortenerActive = true
                        return false
                    }

                    // 1) Se URL está na ALLOWLIST (per:), permite e atualiza host do player
                    if (matchesAllowlist(host, uLower)) {
                        allowHost = host
                        shortenerActive = false
                        return false
                    }

                    // 2) Se MODO ENC. ativo (vindo de bit.ly), permite atravessar até cair no player final
                    if (shortenerActive) {
                        allowHost = host
                        shortenerActive = false
                        return false
                    }

                    // 3) Mesmo com gesto, não deixa sair do eTLD+1 do player
                    val allow = allowHost
                    val same = allow != null && (host == allow || host.endsWith(".$allow"))
                    if (!same) return true   // bloqueia ida para domínio de anúncio

                    // 4) Se a blocklist marcar como ad, bloqueia
                    if (request.isForMainFrame && blockReady.get() && isBlocked(host, uLower)) {
                        return true
                    }

                    return false // permitir dentro do mesmo host do player
                }

                // qualquer outro esquema desconhecido -> consumir (bloquear)
                return true
            }

            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                super.onPageStarted(view, url, favicon)
                // mostra loader
                webLoader.visibility = View.VISIBLE

                // guarda URL principal (para retry)
                if (!url.isNullOrBlank()) lastMainUrl = url

                url?.let {
                    val h = runCatching { Uri.parse(it).host?.lowercase(Locale.ROOT) }.getOrNull()
                    // se ainda está no encurtador, mantém o modo; se não, desliga e fixa host
                    if (isShortener(it, h)) {
                        shortenerActive = true
                    } else {
                        allowHost = h
                        shortenerActive = false
                    }
                }
            }

            override fun onPageFinished(view: WebView, url: String) {
                super.onPageFinished(view, url)
                // esconde loader
                webLoader.visibility = View.GONE

                if (blockReady.get()) {
                    injectAdShieldJS()
                } else {
                    injectCoreShieldJS(emptyList())
                }
            }

            // Mostrar overlay e popup para qualquer erro principal
            private fun showBlackShieldAndDialog(view: WebView?) {
                webLoader.visibility = View.GONE
                // apaga totalmente qualquer tela interna (sem URL)
                showBlank(view)
                blackShield.visibility = View.VISIBLE

                showOfflineDialog {
                    blackShield.visibility = View.GONE
                    val retry = lastMainUrl
                    if (isOnline()) {
                        if (retry.isNullOrBlank()) view?.reload() else view?.loadUrl(retry)
                    } else {
                        showOfflineDialog(this::hideBlackShieldIfOnline)
                    }
                }
            }

            private fun hideBlackShieldIfOnline() {
                if (isOnline()) blackShield.visibility = View.GONE
            }

            // HTTP (ex.: 404/500) no frame principal
            override fun onReceivedHttpError(
                view: WebView,
                request: WebResourceRequest,
                errorResponse: WebResourceResponse
            ) {
                super.onReceivedHttpError(view, request, errorResponse)
                if (request.isForMainFrame) showBlackShieldAndDialog(view)
            }

            // DNS/Conexão/Timeout no frame principal
            override fun onReceivedError(
                view: WebView?,
                request: WebResourceRequest,
                error: WebResourceError
            ) {
                super.onReceivedError(view, request, error)
                if (request.isForMainFrame) showBlackShieldAndDialog(view)
            }

            @Suppress("deprecation")
            override fun onReceivedError(
                view: WebView?,
                errorCode: Int,
                description: String?,
                failingUrl: String?
            ) {
                super.onReceivedError(view, errorCode, description, failingUrl)
                showBlackShieldAndDialog(view)
            }

            // SSL
            override fun onReceivedSslError(
                view: WebView?,
                handler: SslErrorHandler?,
                error: SslError?
            ) {
                handler?.cancel()
                showBlackShieldAndDialog(view)
            }

            // BLOQUEIO de recursos secundários
            override fun shouldInterceptRequest(
                view: WebView,
                request: WebResourceRequest
            ): WebResourceResponse? {
                if (request.isForMainFrame) return null
                if (!blockReady.get()) return null

                val url = request.url.toString()
                val host = request.url.host?.lowercase(Locale.ROOT) ?: return null

                if (isMediaUrl(url)) return null

                val allow = allowHost
                if (allow != null && (host == allow || host.endsWith(".$allow"))) {
                    return null
                }

                return if (isBlocked(host, url.lowercase(Locale.ROOT))) empty204() else null
            }
        }

        web.webChromeClient = object : WebChromeClient() {

            // cancela qualquer window.open / target=_blank
            override fun onCreateWindow(
                view: WebView?,
                isDialog: Boolean,
                isUserGesture: Boolean,
                resultMsg: android.os.Message?
            ): Boolean {
                return false
            }

            // --- FULLSCREEN DE VÍDEO (corrige tela preta em YouTube/JW/etc.) ---
            override fun onShowCustomView(view: View?, callback: CustomViewCallback?) {
                if (customVideoView != null) {
                    callback?.onCustomViewHidden()
                    return
                }
                customVideoView = view
                customViewCallback = callback

                // container fullscreen simples
                fullScreenContainer = FrameLayout(this@WebViewActivity).apply {
                    setBackgroundColor(Color.BLACK)
                    addView(
                        customVideoView,
                        FrameLayout.LayoutParams(
                            FrameLayout.LayoutParams.MATCH_PARENT,
                            FrameLayout.LayoutParams.MATCH_PARENT
                        )
                    )
                }

                // coloca por cima de tudo
                val decor = window.decorView as ViewGroup
                decor.addView(
                    fullScreenContainer,
                    ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    )
                )

                // esconde a WebView por trás
                web.visibility = View.GONE

                // esconde barras do sistema
                insets.hide(WindowInsetsCompat.Type.systemBars())
                insets.isAppearanceLightStatusBars = false
                insets.isAppearanceLightNavigationBars = false
            }

            override fun onHideCustomView() {
                if (customVideoView == null) return

                val decor = window.decorView as ViewGroup
                try {
                    decor.removeView(fullScreenContainer)
                } catch (_: Exception) { }

                fullScreenContainer = null
                customVideoView = null

                // mostra a WebView novamente
                web.visibility = View.VISIBLE

                // restaura barras (seguimos escondendo status bar como no app)
                hideStatusBar()

                try {
                    customViewCallback?.onCustomViewHidden()
                } catch (_: Exception) { }
                customViewCallback = null
            }

            // evita “poster” branco/preto enquanto o vídeo inicializa
            override fun getDefaultVideoPoster(): Bitmap? {
                return Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
            }
            // -------------------------------------------------------------------
        }

        if (initialUrl.isNotBlank()) {
            lastMainUrl = initialUrl
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
        // se estiver em fullscreen de vídeo, sai do fullscreen primeiro
        if (isInFullscreen()) {
            try { (web.webChromeClient as? WebChromeClient)?.onHideCustomView() } catch (_: Exception) {}
            return
        }
        finish()
    }

    override fun onDestroy() {
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        super.onDestroy()
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
            // sem lista -> segue sem bloquear recursos por URL
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
            if (line.isEmpty() || line.startsWith("#")) return@forEach

            val isAllow = line.startsWith("per:",   ignoreCase = true)
            val isProxy = line.startsWith("proxy:", ignoreCase = true)

            val ruleText = when {
                isAllow -> line.substringAfter("per:",   "").trim()
                isProxy -> line.substringAfter("proxy:", "").trim()
                else    -> line
            }
            if (ruleText.isEmpty()) return@forEach

            val rule = ruleText.lowercase(Locale.ROOT)
            val isDomain = rule.contains('.') && !rule.contains(' ') && !rule.contains('/')

            when {
                isAllow -> {
                    if (isDomain) allowDomainRules += rule else allowSubstringRules += rule
                }
                isProxy -> {
                    if (isDomain) proxyDomainRules += rule else proxySubstringRules += rule
                }
                else -> {
                    if (isDomain) domainRules += rule else substringRules += rule
                }
            }
        }
    }

    private fun isBlocked(host: String, fullUrlLower: String): Boolean {
        for (d in domainRules) if (host == d || host.endsWith(".$d")) return true
        for (p in substringRules) if (p.isNotEmpty() && fullUrlLower.contains(p)) return true
        return false
    }

    private fun mustProxy(host: String, fullUrlLower: String): Boolean {
        for (d in proxyDomainRules) if (host == d || host.endsWith(".$d")) return true
        for (p in proxySubstringRules) if (p.isNotEmpty() && fullUrlLower.contains(p)) return true
        return false
    }

    private fun matchesAllowlist(host: String, fullUrlLower: String): Boolean {
        for (d in allowDomainRules) if (host == d || host.endsWith(".$d")) return true
        for (p in allowSubstringRules) if (p.isNotEmpty() && fullUrlLower.contains(p)) return true
        return false
    }

    private fun isMediaUrl(u: String): Boolean {
        val x = u.lowercase(Locale.ROOT)
        return x.endsWith(".m3u8") || x.endsWith(".mpd") || x.endsWith(".ts") ||
               x.endsWith(".m4s")  || x.endsWith(".mp4") || x.endsWith(".webm") ||
               x.endsWith(".aac")  || x.endsWith(".mp3") || x.endsWith(".oga") ||
               x.endsWith(".vtt")  || x.endsWith(".srt")
    }

    private fun empty204(): WebResourceResponse =
        WebResourceResponse(
            "text/plain",
            "utf-8",
            204,
            "No Content",
            emptyMap(),
            ByteArrayInputStream(ByteArray(0))
        )

    // ---------- Injeção de JS anti-pop/overlay/redirecionamento ----------
    private fun injectAdShieldJS() {
        val tokens = (substringRules + domainRules.map { ".$it" })
            .filter { it.isNotBlank() }
            .take(2000)
            .joinToString(separator = "\",\"", prefix = "[\"", postfix = "\"]") { it.replace("\"", "") }
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
              window.open = function(u){ if (isBad(u)) return null; return null; };
              ['assign','replace'].forEach(k=>{
                const orig = location[k].bind(location);
                location[k] = function(u){ if (isBad(u)) return; try{orig(u);}catch(e){} };
              });
              Object.defineProperty(window, 'onbeforeunload', {get:()=>null,set:()=>true});
              window.addEventListener('click', function(e){
                let el = e.target;
                while (el && el !== document && !('href' in el)) el = el.parentElement;
                if (el && el.href && isBad(el.href)) {
                  e.preventDefault(); e.stopImmediatePropagation(); return false;
                }
              }, true);
              const css = `
                [id*="ad"], [class*="ad"], .ads, .adsbox, .advert, .adunit,
                .ad-container, .ad-banner, .ad-overlay, [class*="overlay"] {
                  display:none !important; pointer-events:none !important;
                }
                body { overscroll-behavior: contain; }
              `;
              const style = document.createElement('style');
              style.type = 'text/css'; style.appendChild(document.createTextNode(css));
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
                location[k] = function(u){ if(!u) return; try{orig(u);}catch(e){} };
              });
              const css = `
                [class*="overlay"], .ad, .ads, .ad-overlay { display:none !important; pointer-events:none !important; }
              `;
              const style = document.createElement('style');
              style.type = 'text/css'; style.appendChild(document.createTextNode(css));
              document.documentElement.appendChild(style);
            })();
        """.trimIndent()
        web.evaluateJavascript(js, null)
    }

    // === apaga totalmente a página atual (evita tela de erro com URL) ===
    private fun showBlank(view: WebView?) {
        try { view?.stopLoading() } catch (_: Exception) {}
        try { view?.loadDataWithBaseURL("about:blank", "", "text/html", "utf-8", null) } catch (_: Exception) {}
    }

    // ===== Conectividade + popup "Sem conexão" (mesmo padrão do app) =====
    private fun isOnline(): Boolean {
        val cm = getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager
        val net = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(net) ?: return false
        return caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
               caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) ||
               caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
    }

    private fun showOfflineDialog(onRetry: (() -> Unit)? = null) {
        val d = AlertDialog.Builder(this)
            .setTitle("⚠️ Sem conexão")
            .setMessage("Verifique sua internet e tente novamente.")
            .setNegativeButton("CONFIGURAR WI-FI") { _, _ ->
                startActivity(Intent(Settings.ACTION_WIFI_SETTINGS))
            }
            .setPositiveButton("TENTAR NOVAMENTE") { _, _ ->
                if (isOnline()) onRetry?.invoke() else showOfflineDialog(onRetry)
            }
            .create()

        d.setOnShowListener {
            val c = getColor(R.color.menuColor)
            d.getButton(AlertDialog.BUTTON_POSITIVE)?.setTextColor(c)
            d.getButton(AlertDialog.BUTTON_NEGATIVE)?.setTextColor(c)
        }
        d.show()
    }
}