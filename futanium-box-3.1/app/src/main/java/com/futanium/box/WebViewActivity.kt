package com.futanium.box

import android.annotation.SuppressLint
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.*
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

    // === MODO ENC. (bit.ly) ===
    private var shortenerActive: Boolean = false
    private fun isShortener(url: String, host: String?): Boolean {
        val u = url.lowercase(Locale.ROOT)
        val h = (host ?: "").lowercase(Locale.ROOT)
        return h == "bit.ly" || u.contains("/bit.ly/") || u.contains("://bit.ly/")
    }

    // ===== MODO EXTRAÇÃO M3U8/TS =====
    private var extractMode = false
    private var playerTitleFromIntent: String? = null
    private val extractedOnce = AtomicBoolean(false)
    private var currentPageUrl: String? = null
    private var snifferInjectedForUrl: String? = null
    // =================================

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Status bar translúcida (aparece só ao puxar). Nav bar fixa e preta.
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
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val initialUrl = intent.getStringExtra(EXTRA_URL).orEmpty()
        val initHost = runCatching { Uri.parse(initialUrl).host?.lowercase(Locale.ROOT) }.getOrNull()
        allowHost = initHost
        currentPageUrl = initialUrl

        extractMode = intent.getBooleanExtra("extract_m3u8", false)
        playerTitleFromIntent = intent.getStringExtra("player_title")

        if (isShortener(initialUrl, initHost)) shortenerActive = true

        Thread { loadBlocklist() }.start()

        web = findViewById(R.id.web)
        web.setBackgroundColor(Color.BLACK)
        web.keepScreenOn = true

        // JS bridge para receber URL do m3u8 do sniffer
        web.addJavascriptInterface(object {
            @JavascriptInterface
            fun onM3U8(url: String?) {
                if (url.isNullOrBlank()) return
                if (extractedOnce.compareAndSet(false, true)) {
                    runOnUiThread { launchPlayerFrom(url) }
                }
            }
        }, "Android")

        // Ajuste para não ficar atrás da nav bar
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

            override fun shouldOverrideUrlLoading(
                view: WebView,
                request: WebResourceRequest
            ): Boolean {
                val uri = request.url
                val u = uri.toString()
                val uLower = u.lowercase(Locale.ROOT)

                // Captura navegação principal direta para m3u8/ts
                if (extractMode && (uLower.contains(".m3u8") || uLower.endsWith(".ts"))) {
                    launchPlayerFrom(u)
                    return true
                }

                if (isMediaUrl(u) || u.startsWith("blob:") || u.startsWith("data:")) return false
                if (u == "about:blank") return false

                if (u.startsWith("intent://") || u.startsWith("market://")
                    || u.startsWith("mailto:") || u.startsWith("tel:")
                    || u.startsWith("sms:")) {
                    return try {
                        startActivity(
                            android.content.Intent(
                                android.content.Intent.ACTION_VIEW,
                                Uri.parse(u)
                            )
                        ); true
                    } catch (_: Exception) { true }
                }

                if (u.startsWith("http")) {
                    val host = uri.host?.lowercase(Locale.ROOT) ?: return true

                    if (mustProxy(host, uLower) && !uLower.startsWith(PROXY_BASE)) {
                        view.loadUrl(PROXY_BASE + Uri.encode(u))
                        return true
                    }
                    if (isShortener(u, host)) {
                        shortenerActive = true
                        return false
                    }
                    if (matchesAllowlist(host, uLower)) {
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
                    val same = allow != null && (host == allow || host.endsWith(".$allow"))
                    if (!same) return true

                    if (request.isForMainFrame && blockReady.get() && isBlocked(host, uLower)) {
                        return true
                    }
                    return false
                }
                return true
            }

            override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                super.onPageStarted(view, url, favicon)
                url?.let {
                    currentPageUrl = it
                    val h = runCatching { Uri.parse(it).host?.lowercase(Locale.ROOT) }.getOrNull()
                    if (isShortener(it, h)) {
                        shortenerActive = true
                    } else {
                        allowHost = h
                        shortenerActive = false
                    }
                }
                // Ao trocar de página, permite nova extração
                extractedOnce.set(false)
                snifferInjectedForUrl = null
            }

            override fun onPageFinished(view: WebView, url: String) {
                super.onPageFinished(view, url)
                if (blockReady.get()) injectAdShieldJS() else injectCoreShieldJS(emptyList())

                // Injeta sniffer JS (uma vez por página)
                if (extractMode && snifferInjectedForUrl != url) {
                    injectM3u8SnifferJS()
                    snifferInjectedForUrl = url
                }
            }

            override fun onReceivedError(
                view: WebView?,
                request: WebResourceRequest,
                error: WebResourceError
            ) {
                super.onReceivedError(view, request, error)
                if (request.isForMainFrame) {
                    val code = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
                        error.errorCode
                    } else 0
                    val shouldProxy = code == ERROR_HOST_LOOKUP || code == ERROR_CONNECT || code == ERROR_TIMEOUT
                    if (shouldProxy) {
                        val original = request.url.toString()
                        val lower = original.lowercase(Locale.ROOT)
                        if (!lower.startsWith(PROXY_BASE)) {
                            view?.stopLoading()
                            view?.loadUrl("about:blank")
                            view?.post { view.loadUrl(PROXY_BASE + Uri.encode(original)) }
                        }
                    }
                }
            }

            @Suppress("deprecation")
            override fun onReceivedError(
                view: WebView?,
                errorCode: Int,
                description: String?,
                failingUrl: String?
            ) {
                super.onReceivedError(view, errorCode, description, failingUrl)
                if (failingUrl != null) {
                    val shouldProxy = errorCode == ERROR_HOST_LOOKUP || errorCode == ERROR_CONNECT || errorCode == ERROR_TIMEOUT
                    if (shouldProxy) {
                        val lower = failingUrl.lowercase(Locale.ROOT)
                        if (!lower.startsWith(PROXY_BASE)) {
                            view?.stopLoading()
                            view?.loadUrl("about:blank")
                            view?.post { view.loadUrl(PROXY_BASE + Uri.encode(failingUrl)) }
                        }
                    }
                }
            }

            // BLOQUEIO + (plano B) captura por sub-recursos
            override fun shouldInterceptRequest(
                view: WebView,
                request: WebResourceRequest
            ): WebResourceResponse? {
                val url = request.url.toString()
                val lower = url.lowercase(Locale.ROOT)

                if (extractMode && (lower.contains(".m3u8") || lower.endsWith(".ts"))) {
                    if (extractedOnce.compareAndSet(false, true)) {
                        view.post { launchPlayerFrom(url) }
                    }
                    return null
                }

                if (request.isForMainFrame) return null
                if (!blockReady.get()) return null

                val host = request.url.host?.lowercase(Locale.ROOT) ?: return null
                if (isMediaUrl(url)) return null

                val allow = allowHost
                if (allow != null && (host == allow || host.endsWith(".$allow"))) return null

                return if (isBlocked(host, lower)) empty204() else null
            }
        }

        web.webChromeClient = object : WebChromeClient() {
            override fun onCreateWindow(
                view: WebView?,
                isDialog: Boolean,
                isUserGesture: Boolean,
                resultMsg: android.os.Message?
            ): Boolean = false
        }

        if (initialUrl.isNotBlank()) web.loadUrl(initialUrl)
    }

    private fun launchPlayerFrom(mediaUrl: String) {
        val cm = CookieManager.getInstance()
        val cookie = try {
            val host = runCatching { Uri.parse(mediaUrl).host }.getOrNull()
            if (host.isNullOrBlank()) null else cm.getCookie("https://$host")
        } catch (_: Exception) { null }

        val i = android.content.Intent(this, PlayerActivity::class.java).apply {
            putExtra(PlayerActivity.EXTRA_URL, mediaUrl)
            putExtra(PlayerActivity.EXTRA_TITLE, playerTitleFromIntent ?: "Play")
            putExtra(PlayerActivity.EXTRA_REFERER, currentPageUrl)
            putExtra(PlayerActivity.EXTRA_USER_AGENT, web.settings.userAgentString)
            putExtra(PlayerActivity.EXTRA_SOURCE_PAGE_URL, currentPageUrl)
            if (!cookie.isNullOrBlank()) putExtra(PlayerActivity.EXTRA_COOKIE, cookie)
        }
        startActivity(i)
        finish()
    }

    private fun hideStatusBar() {
        insets.hide(WindowInsetsCompat.Type.statusBars())
        insets.isAppearanceLightNavigationBars = false
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideStatusBar()
    }

    override fun onBackPressed() { finish() }

    override fun onDestroy() {
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        super.onDestroy()
    }

    companion object { const val EXTRA_URL = "url" }

    // ====================== BLOQUEIO: helpers ======================

    private fun loadBlocklist() {
        try {
            val req = Request.Builder().url(blocklistUrl).build()
            client.newCall(req).execute().use { res ->
                val body = res.body?.string().orEmpty()
                parseBlocklist(body)
                blockReady.set(true)
            }
        } catch (_: Exception) { }
    }

    private fun parseBlocklist(text: String) {
        domainRules.clear(); substringRules.clear()
        allowDomainRules.clear(); allowSubstringRules.clear()
        proxyDomainRules.clear(); proxySubstringRules.clear()

        text.lineSequence().forEach { raw ->
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("#")) return@forEach
            val isAllow = line.startsWith("per:", ignoreCase = true)
            val isProxy = line.startsWith("proxy:", ignoreCase = true)
            val ruleText = when {
                isAllow -> line.substringAfter("per:", "").trim()
                isProxy -> line.substringAfter("proxy:", "").trim()
                else -> line
            }
            if (ruleText.isEmpty()) return@forEach
            val rule = ruleText.lowercase(Locale.ROOT)
            val isDomain = rule.contains('.') && !rule.contains(' ') && !rule.contains('/')

            when {
                isAllow -> if (isDomain) allowDomainRules += rule else allowSubstringRules += rule
                isProxy -> if (isDomain) proxyDomainRules += rule else proxySubstringRules += rule
                else    -> if (isDomain) domainRules += rule else substringRules += rule
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
            "text/plain", "utf-8", 204, "No Content",
            emptyMap(), ByteArrayInputStream(ByteArray(0))
        )

    // ---------- Injeção de JS anti-pop/overlay ----------
    private fun injectAdShieldJS() {
        val tokens = (substringRules + domainRules.map { ".$it" })
            .filter { it.isNotBlank() }
            .take(2000)
            .joinToString("\",\"", "[\"", "\"]") { it.replace("\"", "") }
        val js = """
            (function(){
              const LIST = $tokens;
              function isBad(u){
                if(!u) return false;
                u = (""+u).toLowerCase();
                for (let i=0;i<LIST.length;i++){
                  const t = LIST[i]; if(!t) continue;
                  if(t.startsWith(".")) {
                    try { const h = new URL(u, location.href).host.toLowerCase();
                      if (h===t.slice(1) || h.endsWith(t)) return true;
                    } catch(e){}
                  } else { if(u.indexOf(t) !== -1) return true; }
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
                if (el && el.href && isBad(el.href)) { e.preventDefault(); e.stopImmediatePropagation(); return false; }
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
              const css = `[class*="overlay"], .ad, .ads, .ad-overlay { display:none !important; pointer-events:none !important; }`;
              const style = document.createElement('style');
              style.type = 'text/css'; style.appendChild(document.createTextNode(css));
              document.documentElement.appendChild(style);
            })();
        """.trimIndent()
        web.evaluateJavascript(js, null)
    }

    // ---------- Sniffer JS para players tipo Voodc (hls.js/fetch/XHR) ----------
    private fun injectM3u8SnifferJS() {
        val js = """
            (function(){
              if (window.__ftnSnifferInstalled) return; 
              window.__ftnSnifferInstalled = true;
              function report(u){
                try{
                  if(!u) return;
                  u = (""+u);
                  if (u.indexOf(".m3u8") !== -1) {
                    Android.onM3U8(u);
                  }
                }catch(e){}
              }
              // Hls.js hook
              try{
                var g = window, H = g.Hls || g.hlsjs || (g.window && g.window.Hls);
                if (H && H.prototype && H.prototype.loadSource) {
                  var oldLoad = H.prototype.loadSource;
                  H.prototype.loadSource = function(src){
                    try { report(src); } catch(e){}
                    return oldLoad.apply(this, arguments);
                  };
                }
              }catch(e){}
              // fetch hook
              try{
                var _fetch = window.fetch;
                window.fetch = function(){
                  try {
                    var url = arguments[0];
                    if (url && typeof url === 'string') report(url);
                    else if (url && url.url) report(url.url);
                  }catch(e){}
                  return _fetch.apply(this, arguments).then(function(res){
                    try {
                      var u = res && (res.url || (res.headers && res.headers.get && res.headers.get('X-Request-URL')));
                      if (u) report(u);
                    }catch(e){}
                    return res;
                  });
                };
              }catch(e){}
              // XHR hook
              try{
                var _open = XMLHttpRequest.prototype.open;
                XMLHttpRequest.prototype.open = function(method, url){
                  try { report(url); } catch(e){}
                  return _open.apply(this, arguments);
                };
              }catch(e){}
              // Performance observer (pega recursos já baixados)
              try{
                function scanPerf(){
                  try{
                    var list = performance.getEntriesByType('resource') || [];
                    for (var i=0;i<list.length;i++){
                      var n = list[i] && (list[i].name || list[i].url);
                      if(n) report(n);
                    }
                  }catch(e){}
                }
                scanPerf();
                setInterval(scanPerf, 1500);
              }catch(e){}
              // <video>/<source> observador
              try{
                var mo = new MutationObserver(function(muts){
                  muts.forEach(function(m){
                    if (m.type === 'attributes' && m.attributeName === 'src') {
                      var t = m.target && m.target.src; if (t) report(t);
                    }
                  });
                });
                var vids = document.getElementsByTagName('video');
                for (var i=0;i<vids.length;i++){ mo.observe(vids[i], {attributes:true, attributeFilter:['src'], subtree:true}); }
                var sources = document.getElementsByTagName('source');
                for (var j=0;j<sources.length;j++){ mo.observe(sources[j], {attributes:true, attributeFilter:['src'], subtree:true}); }
              }catch(e){}
            })();
        """.trimIndent()
        web.evaluateJavascript(js, null)
    }
}