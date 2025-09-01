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
    private lateinit var webLoader: android.widget.ProgressBar

    // overlay preto para esconder erros / URL
    private lateinit var blackShield: View
    private var lastMainUrl: String? = null

    // ==== FULLSCREEN VIDEO (YouTube etc.)
    private lateinit var fullContainer: FrameLayout
    private var fullView: View? = null
    private var fullCallback: WebChromeClient.CustomViewCallback? = null
    // =====================================

    // --- BLOQUEIO (lista remota) ---
    private val client = OkHttpClient()
    private val blocklistUrl =
        "https://raw.githubusercontent.com/galaxyplay1234/bloqueio-ads-futanium/refs/heads/main/blocklist.txt"

    private val domainRules = HashSet<String>()
    private val substringRules = ArrayList<String>()

    private val allowDomainRules = HashSet<String>()
    private val allowSubstringRules = ArrayList<String>()

    private val proxyDomainRules = HashSet<String>()
    private val proxySubstringRules = ArrayList<String>()
    private val PROXY_BASE = "https://controledeestoque.rf.gd/proxy.php?url="

    private var allowHost: String? = null
    private val blockReady = AtomicBoolean(false)

    private var shortenerActive: Boolean = false
    private fun isShortener(url: String, host: String?): Boolean {
        val u = url.lowercase(Locale.ROOT)
        val h = (host ?: "").lowercase(Locale.ROOT)
        return h == "bit.ly" || u.contains("/bit.ly/") || u.contains("://bit.ly/")
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        WindowCompat.setDecorFitsSystemWindows(window, true)
        window.statusBarColor = Color.BLACK
        window.navigationBarColor = Color.BLACK
        insets = WindowInsetsControllerCompat(window, window.decorView).apply {
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            isAppearanceLightStatusBars = false
            isAppearanceLightNavigationBars = false
        }
        hideStatusBar()

        setContentView(R.layout.activity_webview)

        webLoader = findViewById(R.id.webLoader)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val initialUrl = intent.getStringExtra(EXTRA_URL).orEmpty()
        val initHost = runCatching { Uri.parse(initialUrl).host?.lowercase(Locale.ROOT) }.getOrNull()
        allowHost = initHost
        if (isShortener(initialUrl, initHost)) shortenerActive = true

        // WebView básico
        web = findViewById(R.id.web)
        web.setBackgroundColor(Color.BLACK)
        web.keepScreenOn = true
        // força renderização acelerada
        web.setLayerType(View.LAYER_TYPE_HARDWARE, null)

        // bloqueia long-press/seleção
        web.isLongClickable = false
        web.setOnLongClickListener { true }
        web.isHapticFeedbackEnabled = false

        // container para fullscreen de vídeo (fica por cima do conteúdo)
        fullContainer = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            visibility = View.GONE
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }
        addContentView(fullContainer, fullContainer.layoutParams)

        // padding bottom para não ficar atrás da nav bar
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

        // overlay preto (erros)
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

        // cookies (YouTube precisa)
        CookieManager.getInstance().setAcceptCookie(true)
        if (android.os.Build.VERSION.SDK_INT >= 21) {
            CookieManager.getInstance().setAcceptThirdPartyCookies(web, true)
        }

        // carrega blocklist em background
        Thread { loadBlocklist() }.start()

        web.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val uri = request.url
                val u = uri.toString()
                val uLower = u.lowercase(Locale.ROOT)
                lastMainUrl = u

                if (isMediaUrl(u) || u.startsWith("blob:") || u.startsWith("data:")) return false
                if (u == "about:blank") return false

                if (u.startsWith("intent://") || u.startsWith("market://")
                    || u.startsWith("mailto:") || u.startsWith("tel:") || u.startsWith("sms:")) {
                    return try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(u))); true }
                    catch (_: Exception) { true }
                }

                if (u.startsWith("http")) {
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

                    if (mustProxy(host, uLower) && !uLower.startsWith(PROXY_BASE)) {
                        view.loadUrl(PROXY_BASE + Uri.encode(u))
                        return true
                    }
                    if (isShortener(u, host)) { shortenerActive = true; return false }
                    if (matchesAllowlist(host, uLower)) { allowHost = host; shortenerActive = false; return false }
                    if (shortenerActive) { allowHost = host; shortenerActive = false; return false }

                    val allow = allowHost
                    val same = allow != null && (host == allow || host.endsWith(".$allow"))
                    if (!same) return true

                    if (request.isForMainFrame && blockReady.get() && isBlocked(host, uLower)) return true
                    return false
                }
                return true
            }

            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                super.onPageStarted(view, url, favicon)
                webLoader.visibility = View.VISIBLE
                if (!url.isNullOrBlank()) lastMainUrl = url
                url?.let {
                    val h = runCatching { Uri.parse(it).host?.lowercase(Locale.ROOT) }.getOrNull()
                    if (isShortener(it, h)) shortenerActive = true else { allowHost = h; shortenerActive = false }
                }
            }

            override fun onPageFinished(view: WebView, url: String) {
                super.onPageFinished(view, url)
                webLoader.visibility = View.GONE
                if (blockReady.get()) injectAdShieldJS() else injectCoreShieldJS(emptyList())
            }

            private fun showBlackShieldAndDialog(view: WebView?) {
                webLoader.visibility = View.GONE
                showBlank(view)
                blackShield.visibility = View.VISIBLE
                showOfflineDialog {
                    blackShield.visibility = View.GONE
                    val retry = lastMainUrl
                    if (isOnline()) { if (retry.isNullOrBlank()) view?.reload() else view?.loadUrl(retry) }
                    else { showOfflineDialog(this::hideBlackShieldIfOnline) }
                }
            }
            private fun hideBlackShieldIfOnline() { if (isOnline()) blackShield.visibility = View.GONE }

            override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, errorResponse: WebResourceResponse) {
                super.onReceivedHttpError(view, request, errorResponse)
                if (request.isForMainFrame) showBlackShieldAndDialog(view)
            }
            override fun onReceivedError(view: WebView?, request: WebResourceRequest, error: WebResourceError) {
                super.onReceivedError(view, request, error)
                if (request.isForMainFrame) showBlackShieldAndDialog(view)
            }
            @Suppress("deprecation")
            override fun onReceivedError(view: WebView?, errorCode: Int, description: String?, failingUrl: String?) {
                super.onReceivedError(view, errorCode, description, failingUrl)
                showBlackShieldAndDialog(view)
            }
            override fun onReceivedSslError(view: WebView?, handler: SslErrorHandler?, error: SslError?) {
                handler?.cancel()
                showBlackShieldAndDialog(view)
            }

            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                if (request.isForMainFrame) return null
                if (!blockReady.get()) return null
                val url = request.url.toString()
                val host = request.url.host?.lowercase(Locale.ROOT) ?: return null
                if (isMediaUrl(url)) return null
                val allow = allowHost
                if (allow != null && (host == allow || host.endsWith(".$allow"))) return null
                return if (isBlocked(host, url.lowercase(Locale.ROOT))) empty204() else null
            }
        }

        web.webChromeClient = object : WebChromeClient() {
            // suporte a vídeo/fullscreen (YouTube)
            override fun onShowCustomView(view: View?, callback: CustomViewCallback?) {
                if (fullView != null) {
                    callback?.onCustomViewHidden()
                    return
                }
                fullView = view
                fullCallback = callback
                fullContainer.addView(
                    view,
                    FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    )
                )
                fullContainer.visibility = View.VISIBLE
                web.visibility = View.GONE
                window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }

            override fun onHideCustomView() {
                fullContainer.removeAllViews()
                fullContainer.visibility = View.GONE
                web.visibility = View.VISIBLE
                fullCallback?.onCustomViewHidden()
                fullView = null
                window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }

            // evita poster preto em alguns devices
            override fun getDefaultVideoPoster(): Bitmap? {
                return Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
            }

            // bloqueia novas janelas
            override fun onCreateWindow(
                view: WebView?, isDialog: Boolean, isUserGesture: Boolean, resultMsg: android.os.Message?
            ): Boolean = false
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
        if (fullContainer.visibility == View.VISIBLE) {
            (web.webChromeClient as? WebChromeClient)?.onHideCustomView()
            return
        }
        finish()
    }

    override fun onDestroy() {
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        super.onDestroy()
    }

    companion object { const val EXTRA_URL = "url" }

    // ======= helpers de bloqueio/JS (inalterados) =======

    private fun loadBlocklist() {
        try {
            val req = Request.Builder().url(blocklistUrl).build()
            client.newCall(req).execute().use { res ->
                val body = res.body?.string().orEmpty()
                parseBlocklist(body); blockReady.set(true)
            }
        } catch (_: Exception) {}
    }

    private fun parseBlocklist(text: String) {
        domainRules.clear(); substringRules.clear()
        allowDomainRules.clear(); allowSubstringRules.clear()
        proxyDomainRules.clear(); proxySubstringRules.clear()
        text.lineSequence().forEach { raw ->
            val line = raw.trim(); if (line.isEmpty() || line.startsWith("#")) return@forEach
            val isAllow = line.startsWith("per:", true)
            val isProxy = line.startsWith("proxy:", true)
            val ruleText = when { isAllow -> line.substringAfter("per:", "").trim()
                isProxy -> line.substringAfter("proxy:", "").trim()
                else -> line }
            if (ruleText.isEmpty()) return@forEach
            val rule = ruleText.lowercase(Locale.ROOT)
            val isDomain = rule.contains('.') && !rule.contains(' ') && !rule.contains('/')
            when {
                isAllow -> if (isDomain) allowDomainRules += rule else allowSubstringRules += rule
                isProxy -> if (isDomain) proxyDomainRules += rule else proxySubstringRules += rule
                else -> if (isDomain) domainRules += rule else substringRules += rule
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
        WebResourceResponse("text/plain", "utf-8", 204, "No Content", emptyMap(), ByteArrayInputStream(ByteArray(0)))

    private fun injectAdShieldJS() {
        val tokens = (substringRules + domainRules.map { ".$it" })
            .filter { it.isNotBlank() }.take(2000)
            .joinToString("\",\"", "[\"", "\"]") { it.replace("\"", "") }
        val js = """
            (function(){
              const LIST = $tokens;
              function isBad(u){ if(!u) return false; u=(""+u).toLowerCase();
                for (let i=0;i<LIST.length;i++){ const t=LIST[i]; if(!t) continue;
                  if(t.startsWith(".")){ try{ const h=new URL(u,location.href).host.toLowerCase(); if(h===t.slice(1)||h.endsWith(t)) return true; }catch(e){} }
                  else { if(u.indexOf(t)!==-1) return true; } }
                return false; }
              window.open = function(){ return null; };
              ['assign','replace'].forEach(k=>{ const o=location[k].bind(location); location[k]=function(u){ if(isBad(u)) return; try{o(u);}catch(e){} }; });
              Object.defineProperty(window,'onbeforeunload',{get:()=>null,set:()=>true});
              window.addEventListener('click',function(e){ let el=e.target; while(el&&el!==document&&!('href'in el)) el=el.parentElement;
                if(el&&el.href&&isBad(el.href)){ e.preventDefault(); e.stopImmediatePropagation(); return false; } }, true);
              const css=`[id*="ad"],[class*="ad"],.ads,.adsbox,.advert,.adunit,.ad-container,.ad-banner,.ad-overlay,[class*="overlay"]{display:none!important;pointer-events:none!important;} body{overscroll-behavior:contain;}`;
              const s=document.createElement('style'); s.type='text/css'; s.appendChild(document.createTextNode(css)); document.documentElement.appendChild(s);
              const _si=window.setInterval; window.setInterval=function(fn,t){ if(typeof fn==='string'&&isBad(fn)) return 0; return _si(fn,t); };
            })();
        """.trimIndent()
        web.evaluateJavascript(js, null)
    }

    private fun injectCoreShieldJS(extraTokens: List<String>) {
        val js = """
            (function(){
              window.open=function(){return null;};
              ['assign','replace'].forEach(k=>{ const o=location[k].bind(location); location[k]=function(u){ if(!u)return; try{o(u);}catch(e){} }; });
              const css=`[class*="overlay"],.ad,.ads,.ad-overlay{display:none!important;pointer-events:none!important;}`;
              const s=document.createElement('style'); s.type='text/css'; s.appendChild(document.createTextNode(css)); document.documentElement.appendChild(s);
            })();
        """.trimIndent()
        web.evaluateJavascript(js, null)
    }

    private fun showBlank(view: WebView?) {
        try { view?.stopLoading() } catch (_: Exception) {}
        try { view?.loadDataWithBaseURL("about:blank","", "text/html","utf-8", null) } catch (_: Exception) {}
    }

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