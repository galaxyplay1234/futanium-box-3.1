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
    private var lastMainUrl: String? = null

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
    private var isYoutubeMode: Boolean = false  // evita bloqueios no embed do YouTube
    private var playerOpened = false

    private fun isShortener(url: String, host: String?): Boolean {
        val u = url.lowercase(Locale.ROOT)
        val h = (host ?: "").lowercase(Locale.ROOT)
        return h == "bit.ly" || u.contains("/bit.ly/") || u.contains("://bit.ly/")
    }


        private var webviewRefId: String? = null
    private var isWebviewActive = false

    private fun setWebviewStatus(active: Boolean) {
        val db = FirebaseDatabase.getInstance("https://futanium-web-default-rtdb.firebaseio.com/")
        val webviewRef = db.getReference("webview")

        if (active) {
            webviewRefId = UUID.randomUUID().toString()
            webviewRef.child(webviewRefId!!).setValue(true)
            webviewRef.child(webviewRefId!!).onDisconnect().removeValue()
            isWebviewActive = true
        } else {
            webviewRefId?.let {
                webviewRef.child(it).removeValue()
                webviewRefId = null
                isWebviewActive = false
            }
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
				setWebviewStatus(true)

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
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val initialUrl = intent.getStringExtra(EXTRA_URL).orEmpty()
        val initHost = runCatching { Uri.parse(initialUrl).host?.lowercase(Locale.ROOT) }.getOrNull()
        allowHost = initHost

        // ativa modo YouTube quando o link inicial é embed
        isYoutubeMode =
            initialUrl.contains("youtube.com/embed") || initialUrl.contains("youtube-nocookie.com/embed")

        if (isShortener(initialUrl, initHost)) shortenerActive = true

        Thread { loadBlocklist() }.start()

        web = findViewById(R.id.web)
        web.setBackgroundColor(Color.BLACK)
        web.keepScreenOn = true
        web.setLayerType(View.LAYER_TYPE_HARDWARE, null)

        // desativa seleção/callout
        web.isLongClickable = false
        web.setOnLongClickListener { true }
        web.isHapticFeedbackEnabled = false

        // insets
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

        // overlay preto para esconder telas de erro internas
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
            loadsImagesAutomatically = true
            mediaPlaybackRequiresUserGesture = false
        }

        web.webViewClient = object : WebViewClient() {

            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
    val uri = request.url
    val u = uri.toString()
    lastMainUrl = u

    if (isYoutubeMode) return false
    if (isMediaUrl(u) || u.startsWith("blob:") || u.startsWith("data:")) return false
    if (u == "about:blank") return false

    if (u.startsWith("intent://") || u.startsWith("market://")
        || u.startsWith("mailto:") || u.startsWith("tel:")
        || u.startsWith("sms:")) {
        return try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(u)))
            true
        } catch (_: Exception) { true }
    }

    if (!u.startsWith("http")) return false

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

    if (mustProxy(host, u.lowercase(Locale.ROOT)) && !u.startsWith(PROXY_BASE)) {
        view.loadUrl(PROXY_BASE + Uri.encode(u))
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
    if (allow != null && (host == allow || host.endsWith(".$allow"))) {
        return false
    }

    if (request.isForMainFrame) {
        return true
    }

    return false
}

            

            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                super.onPageStarted(view, url, favicon)
                webLoader.visibility = View.VISIBLE
                if (!url.isNullOrBlank()) lastMainUrl = url
            }

            override fun onPageFinished(view: WebView, url: String) {
                super.onPageFinished(view, url)
                webLoader.visibility = View.GONE
                if (!isYoutubeMode) {
                    if (blockReady.get()) injectAdShieldJS() else injectCoreShieldJS(emptyList())
                }
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

            override fun onReceivedSslError(
    view: WebView?,
    handler: SslErrorHandler?,
    error: SslError?
) {
    handler?.proceed()
}

            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                if (isYoutubeMode) return null
                if (request.isForMainFrame) return null
                if (!blockReady.get()) return null

                val url = request.url.toString()
val host = request.url.host?.lowercase(Locale.ROOT) ?: return null

if (
    !playerOpened &&
    (
        url.contains(".m3u8", true) ||
        url.contains("__index.m3u8", true)
    )
) {

    playerOpened = true

    runOnUiThread {

        val i = Intent(
            this@WebViewActivity,
            PlayerActivity::class.java
        )

        i.putExtra("url", url)

        startActivity(i)

        finish()
    }

    return null
}

if (isMediaUrl(url)) return null

                val allow = allowHost
                if (allow != null && (host == allow || host.endsWith(".$allow"))) {
                    return null
                }
                return if (isBlocked(host, url.lowercase(Locale.ROOT))) empty204() else null
            }

            private fun showBlackShieldAndDialog(view: WebView?) {
                webLoader.visibility = View.GONE
                showBlank(view)
                blackShield.visibility = View.VISIBLE
                showOfflineDialog {
                    blackShield.visibility = View.GONE
                    val retry = lastMainUrl
                    if (isOnline()) {
                        if (retry.isNullOrBlank()) view?.reload() else view?.loadUrl(retry)
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
    
		    override fun onPause() {
        super.onPause()
        setWebviewStatus(false)
    }

    override fun onResume() {
        super.onResume()
        if (!isWebviewActive) setWebviewStatus(true)
    }


    
    companion object { const val EXTRA_URL = "url" }

    // ===== Blocklist =====
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

            val isAllow = line.startsWith("per:", true)
            val isProxy = line.startsWith("proxy:", true)
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
    for (d in allowDomainRules) {
        if (host == d || host.endsWith(".$d")) return true
    }
    for (p in allowSubstringRules) {
        if (p.isNotEmpty() && fullUrlLower.contains(p)) return true
    }
    return false
}

    private fun isMediaUrl(u: String): Boolean {
        val x = u.lowercase(Locale.ROOT)
        return x.endsWith(".m3u8") || x.endsWith(".mp4") || x.endsWith(".webm") ||
               x.endsWith(".mpd")  || x.endsWith(".ts")  || x.endsWith(".m4s") ||
               x.endsWith(".aac")  || x.endsWith(".mp3") || x.endsWith(".oga") ||
               x.endsWith(".vtt")  || x.endsWith(".srt")
    }

    private fun empty204(): WebResourceResponse =
        WebResourceResponse("text/plain", "utf-8", 204, "No Content", emptyMap(), ByteArrayInputStream(ByteArray(0)))

    // ===== JS injections =====
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
              window.open = function(){ return null; };
              ['assign','replace'].forEach(k=>{
                const orig = location[k].bind(location);
                location[k] = function(u){ if (isBad(u)) return; try{orig(u);}catch(e){} };
              });
              Object.defineProperty(window, 'onbeforeunload', {get:()=>null,set:()=>true});
              window.addEventListener('click', function(e){
                let el = e.target;
                while (el && el !== document && !('href' in el)) el = el.parentElement;
                if (el && el.href && isBad(el.href)) {
    e.preventDefault();
}
              }, true);
              const css = `
                [id*="ad"], [class*="ad"], .ads, .adsbox, .advert, .adunit,
                .ad-container, .ad-banner, .ad-overlay {
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
  .ad, .ads, .ad-overlay {
    display:none !important;
    pointer-events:none !important;
  }
`;
              const style = document.createElement('style');
              style.type = 'text/css'; style.appendChild(document.createTextNode(css));
              document.documentElement.appendChild(style);
            })();
        """.trimIndent()
        web.evaluateJavascript(js, null)
    }

    // ===== utilidades =====
    private fun showBlank(view: WebView?) {
        try { view?.stopLoading() } catch (_: Exception) {}
        try { view?.loadDataWithBaseURL("about:blank", "", "text/html", "utf-8", null) } catch (_: Exception) {}
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