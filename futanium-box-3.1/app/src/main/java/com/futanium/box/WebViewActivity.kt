
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

    @Volatile
    private var lastMainUrl: String? = null

    private var webUserAgent: String = ""

    private val client = OkHttpClient()

    private val blocklistUrl =
        "https://raw.githubusercontent.com/galaxyplay1234/bloqueio-ads-futanium/refs/heads/main/blocklist.txt"

    private val domainRules = HashSet<String>()
    private val substringRules = ArrayList<String>()
    private val allowDomainRules = HashSet<String>()
    private val allowSubstringRules = ArrayList<String>()
    private val proxyDomainRules = HashSet<String>()
    private val proxySubstringRules = ArrayList<String>()

    private val PROXY_BASE =
        "https://controledeestoque.rf.gd/proxy.php?url="

    private var allowHost: String? = null
    private val blockReady = AtomicBoolean(false)

    private var shortenerActive: Boolean = false
    private var isYoutubeMode: Boolean = false
    private var playerOpened = false
    private var captureM3u8 = false

    private var operatorDialogOpen = false
    private var operatorDialog: AlertDialog? = null
    private var returningFromSettings = false

    private var webviewRefId: String? = null
    private var isWebviewActive = false

    // =====================================================
    // UTILITÁRIO DE CABEÇALHOS
    // =====================================================

    private fun getHeader(
        headers: Map<String, String>,
        name: String
    ): String? {
        return headers.entries.firstOrNull {
            it.key.equals(name, ignoreCase = true)
        }?.value
    }

    // =====================================================
    // SHORTENER
    // =====================================================

    private fun isShortener(
        url: String,
        host: String?
    ): Boolean {

        val u = url.lowercase(Locale.ROOT)
        val h = (host ?: "").lowercase(Locale.ROOT)

        return h == "bit.ly" ||
            u.contains("/bit.ly/") ||
            u.contains("://bit.ly/")
    }

    // =====================================================
    // FIREBASE
    // =====================================================

    private fun setWebviewStatus(active: Boolean) {

        val db = FirebaseDatabase.getInstance(
            "https://futanium-web-default-rtdb.firebaseio.com/"
        )

        val webviewRef = db.getReference("webview")

        if (active) {

            if (isWebviewActive) return

            webviewRefId = UUID.randomUUID().toString()

            webviewRef.child(webviewRefId!!).setValue(true)

            webviewRef.child(webviewRefId!!)
                .onDisconnect()
                .removeValue()

            isWebviewActive = true

        } else {

            webviewRefId?.let {
                webviewRef.child(it).removeValue()
                webviewRefId = null
            }

            isWebviewActive = false
        }
    }

    // =====================================================
    // ON CREATE
    // =====================================================

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {

        super.onCreate(savedInstanceState)

        setWebviewStatus(true)

        WindowCompat.setDecorFitsSystemWindows(window, true)

        window.statusBarColor = Color.BLACK
        window.navigationBarColor = Color.BLACK

        insets = WindowInsetsControllerCompat(
            window,
            window.decorView
        ).apply {

            systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE

            isAppearanceLightStatusBars = false
            isAppearanceLightNavigationBars = false
        }

        hideStatusBar()

        setContentView(R.layout.activity_webview)

        webLoader = findViewById(R.id.webLoader)

        window.addFlags(
            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
        )

        val initialUrl =
            intent.getStringExtra(EXTRA_URL).orEmpty()

        captureM3u8 =
            intent.getBooleanExtra("captureM3u8", false)

        if (
            initialUrl.contains("youtube.com", true) ||
            initialUrl.contains("youtu.be", true)
        ) {

            startActivity(
                Intent(
                    this,
                    YoutubeWebViewActivity::class.java
                ).apply {
                    putExtra("url", initialUrl)
                }
            )

            finish()
            return
        }

        val initHost = runCatching {
            Uri.parse(initialUrl)
                .host
                ?.lowercase(Locale.ROOT)
        }.getOrNull()

        allowHost = initHost

        isYoutubeMode =
            initialUrl.contains("youtube.com/embed") ||
            initialUrl.contains("youtube-nocookie.com/embed")

        if (isShortener(initialUrl, initHost)) {
            shortenerActive = true
        }

        Thread {
            loadBlocklist()
        }.start()

        web = findViewById(R.id.web)

        web.setBackgroundColor(Color.BLACK)
        web.keepScreenOn = true
        web.setLayerType(View.LAYER_TYPE_HARDWARE, null)

        web.isLongClickable = false
        web.setOnLongClickListener { true }
        web.isHapticFeedbackEnabled = false

        // =================================================
        // INSETS
        // =================================================

        val contentRoot =
            findViewById<ViewGroup>(android.R.id.content)
                .getChildAt(0) ?: web

        ViewCompat.setOnApplyWindowInsetsListener(
            contentRoot
        ) { v, ins ->

            val nav = ins.getInsets(
                WindowInsetsCompat.Type.navigationBars()
            )

            v.setPadding(
                v.paddingLeft,
                v.paddingTop,
                v.paddingRight,
                nav.bottom
            )

            ins
        }

        ViewCompat.setOnApplyWindowInsetsListener(web) { v, ins ->

            val nav = ins.getInsets(
                WindowInsetsCompat.Type.navigationBars()
            )

            v.setPadding(
                v.paddingLeft,
                v.paddingTop,
                v.paddingRight,
                nav.bottom
            )

            ins
        }

        ViewCompat.requestApplyInsets(contentRoot)
        ViewCompat.requestApplyInsets(web)

        // =================================================
        // BLACK SHIELD
        // =================================================

        blackShield = View(this).apply {

            setBackgroundColor(Color.BLACK)

            visibility = View.GONE

            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }

        (contentRoot as ViewGroup).addView(blackShield)

        // =================================================
        // WEBVIEW SETTINGS
        // =================================================

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

            mixedContentMode =
                WebSettings.MIXED_CONTENT_ALWAYS_ALLOW

            loadsImagesAutomatically = true
        }

        webUserAgent = web.settings.userAgentString.orEmpty()

        // =================================================
        // WEBVIEW CLIENT
        // =================================================

        web.webViewClient = object : WebViewClient() {

            override fun shouldOverrideUrlLoading(
                view: WebView,
                request: WebResourceRequest
            ): Boolean {

                val uri = request.url
                val u = uri.toString()

                lastMainUrl = u

                if (isYoutubeMode) return false

                if (
                    isMediaUrl(u) ||
                    u.startsWith("blob:") ||
                    u.startsWith("data:")
                ) {
                    return false
                }

                if (u == "about:blank") return false

                if (
                    u.startsWith("intent://") ||
                    u.startsWith("market://") ||
                    u.startsWith("mailto:") ||
                    u.startsWith("tel:") ||
                    u.startsWith("sms:")
                ) {

                    return try {

                        startActivity(
                            Intent(
                                Intent.ACTION_VIEW,
                                Uri.parse(u)
                            )
                        )

                        true

                    } catch (_: Exception) {
                        true
                    }
                }

                if (!u.startsWith("http")) return false

                if (!isOnline()) {

                    blackShield.visibility = View.VISIBLE

                    showOfflineDialog {

                        blackShield.visibility = View.GONE

                        val retry = lastMainUrl ?: u

                        if (isOnline()) {
                            view.loadUrl(retry)
                        }
                    }

                    return true
                }

                val host =
                    uri.host?.lowercase(Locale.ROOT)
                        ?: return true

                if (
                    mustProxy(host, u.lowercase(Locale.ROOT)) &&
                    !u.startsWith(PROXY_BASE)
                ) {

                    view.loadUrl(
                        PROXY_BASE + Uri.encode(u)
                    )

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

                if (
                    allow != null &&
                    (host == allow || host.endsWith(".$allow"))
                ) {
                    return false
                }

                if (request.isForMainFrame) {
                    return true
                }

                return false
            }

            override fun onPageStarted(
                view: WebView?,
                url: String?,
                favicon: Bitmap?
            ) {

                super.onPageStarted(view, url, favicon)

                webLoader.visibility = View.VISIBLE

                if (!url.isNullOrBlank()) {
                    lastMainUrl = url
                }
            }

            override fun onPageFinished(
                view: WebView,
                url: String
            ) {

                super.onPageFinished(view, url)

                webLoader.visibility = View.GONE

                if (!isYoutubeMode) {

                    if (blockReady.get()) {
                        injectAdShieldJS()
                    } else {
                        injectCoreShieldJS(emptyList())
                    }
                }
            }

            override fun onReceivedHttpError(
                view: WebView?,
                request: WebResourceRequest?,
                errorResponse: WebResourceResponse?
            ) {

                super.onReceivedHttpError(
                    view,
                    request,
                    errorResponse
                )

                if (request?.isForMainFrame != true) return

                val code = errorResponse?.statusCode ?: return

                if (code == 403 || code == 451) {

                    runOnUiThread {
                        showOperatorBlockedDialog()
                    }
                }
            }

            override fun onReceivedError(
                view: WebView?,
                request: WebResourceRequest,
                error: WebResourceError
            ) {

                super.onReceivedError(view, request, error)

                if (!request.isForMainFrame) return

                if (!isOnline()) {

                    showBlackShieldAndDialog(view)
                    return
                }

                if (
                    error.errorCode == WebViewClient.ERROR_HOST_LOOKUP ||
                    error.errorCode == WebViewClient.ERROR_CONNECT ||
                    error.errorCode == WebViewClient.ERROR_TIMEOUT ||
                    error.errorCode == WebViewClient.ERROR_FAILED_SSL_HANDSHAKE
                ) {

                    showOperatorBlockedDialog()
                }
            }

            @Suppress("deprecation")
            override fun onReceivedError(
                view: WebView?,
                errorCode: Int,
                description: String?,
                failingUrl: String?
            ) {

                super.onReceivedError(
                    view,
                    errorCode,
                    description,
                    failingUrl
                )

                if (!isOnline()) {

                    showBlackShieldAndDialog(view)
                    return
                }

                if (
                    errorCode == WebViewClient.ERROR_HOST_LOOKUP ||
                    errorCode == WebViewClient.ERROR_CONNECT ||
                    errorCode == WebViewClient.ERROR_TIMEOUT ||
                    errorCode == WebViewClient.ERROR_FAILED_SSL_HANDSHAKE
                ) {

                    showOperatorBlockedDialog()
                }
            }

            override fun onReceivedSslError(
                view: WebView?,
                handler: SslErrorHandler?,
                error: SslError?
            ) {
                handler?.proceed()
            }

            // =================================================
            // CAPTURA AUTOMÁTICA MPD / M3U8
            // =================================================

            override fun shouldInterceptRequest(
                view: WebView,
                request: WebResourceRequest
            ): WebResourceResponse? {

                if (isYoutubeMode) return null

                val url = request.url.toString()

                val host =
                    request.url.host?.lowercase(Locale.ROOT)
                        ?: return null

                // Captura acontece ANTES da blocklist.
                // Não depende de blockReady.
                if (
                    captureM3u8 &&
                    isStreamManifestUrl(url)
                ) {

                    var shouldOpenPlayer = false

                    synchronized(this@WebViewActivity) {

                        if (!playerOpened) {
                            playerOpened = true
                            shouldOpenPlayer = true
                        }
                    }

                    if (shouldOpenPlayer) {

                        val headers = request.requestHeaders

                        val cookies =
                            CookieManager.getInstance()
                                .getCookie(url)
                                .orEmpty()

                        val referer =
                            getHeader(headers, "Referer")
                                ?: lastMainUrl
                                ?: ""

                        val userAgent =
                            getHeader(headers, "User-Agent")
                                ?: webUserAgent

                        val origin =
                            getHeader(headers, "Origin")
                                ?: ""

                        val method = request.method

                        runOnUiThread {

                            if (isFinishing || isDestroyed) {
                                return@runOnUiThread
                            }

                            val i = Intent(
                                this@WebViewActivity,
                                PlayerActivity::class.java
                            ).apply {

                                putExtra(
                                    PlayerActivity.EXTRA_URL,
                                    url
                                )

                                putExtra(
                                    PlayerActivity.EXTRA_COOKIE,
                                    cookies
                                )

                                putExtra(
                                    PlayerActivity.EXTRA_REFERER,
                                    referer
                                )

                                putExtra(
                                    PlayerActivity.EXTRA_USER_AGENT,
                                    userAgent
                                )

                                // Origin original
                                putExtra("origin", origin)

                                // Método da requisição
                                putExtra("manifest_method", method)
                            }

                            startActivity(i)
                            finish()
                        }
                    }

                    return null
                }

                // =================================================
                // RESTANTE DO BLOQUEADOR ORIGINAL
                // =================================================

                if (!blockReady.get()) return null

                if (isMediaUrl(url)) return null

                val allow = allowHost

                if (
                    allow != null &&
                    (host == allow || host.endsWith(".$allow"))
                ) {
                    return null
                }

                return if (
                    isBlocked(
                        host,
                        url.lowercase(Locale.ROOT)
                    )
                ) {
                    empty204()
                } else {
                    null
                }
            }

            private fun showBlackShieldAndDialog(view: WebView?) {

                webLoader.visibility = View.GONE

                showBlank(view)

                blackShield.visibility = View.VISIBLE

                showOfflineDialog {

                    blackShield.visibility = View.GONE

                    val retry = lastMainUrl

                    if (isOnline()) {

                        if (retry.isNullOrBlank()) {
                            view?.reload()
                        } else {
                            view?.loadUrl(retry)
                        }
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

    // =====================================================
    // STATUS BAR
    // =====================================================

    private fun hideStatusBar() {

        insets.hide(WindowInsetsCompat.Type.statusBars())

        insets.isAppearanceLightNavigationBars = false
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {

        super.onWindowFocusChanged(hasFocus)

        if (hasFocus) hideStatusBar()
    }

    @Deprecated("Deprecated in Android")
    override fun onBackPressed() {
        finish()
    }

    override fun onDestroy() {

        window.clearFlags(
            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
        )

        super.onDestroy()
    }

    override fun onPause() {

        super.onPause()

        setWebviewStatus(false)
    }

    override fun onResume() {

        super.onResume()

        if (!isWebviewActive) {
            setWebviewStatus(true)
        }

        if (returningFromSettings) {

            returningFromSettings = false

            operatorDialogOpen = false

            operatorDialog?.dismiss()
            operatorDialog = null

            blackShield.visibility = View.GONE

            if (!lastMainUrl.isNullOrBlank()) {
                web.loadUrl(lastMainUrl!!)
            } else {
                web.reload()
            }
        }
    }

    companion object {
        const val EXTRA_URL = "url"
    }

    // =====================================================
    // BLOCKLIST
    // =====================================================

    private fun loadBlocklist() {

        try {

            val req = Request.Builder()
                .url(blocklistUrl)
                .build()

            client.newCall(req).execute().use { res ->

                val body = res.body?.string().orEmpty()

                parseBlocklist(body)

                blockReady.set(true)
            }

        } catch (_: Exception) {
            // Mantém o comportamento original.
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

            if (line.isEmpty() || line.startsWith("#")) {
                return@forEach
            }

            val isAllow = line.startsWith("per:", true)
            val isProxy = line.startsWith("proxy:", true)

            val ruleText = when {

                isAllow ->
                    line.substringAfter("per:", "").trim()

                isProxy ->
                    line.substringAfter("proxy:", "").trim()

                else -> line
            }

            if (ruleText.isEmpty()) return@forEach

            val rule = ruleText.lowercase(Locale.ROOT)

            val isDomain =
                rule.contains('.') &&
                !rule.contains(' ') &&
                !rule.contains('/')

            when {

                isAllow -> {
                    if (isDomain) {
                        allowDomainRules += rule
                    } else {
                        allowSubstringRules += rule
                    }
                }

                isProxy -> {
                    if (isDomain) {
                        proxyDomainRules += rule
                    } else {
                        proxySubstringRules += rule
                    }
                }

                else -> {
                    if (isDomain) {
                        domainRules += rule
                    } else {
                        substringRules += rule
                    }
                }
            }
        }
    }

    private fun isBlocked(
        host: String,
        fullUrlLower: String
    ): Boolean {

        for (d in domainRules) {
            if (host == d || host.endsWith(".$d")) return true
        }

        for (p in substringRules) {
            if (p.isNotEmpty() && fullUrlLower.contains(p)) return true
        }

        return false
    }

    private fun mustProxy(
        host: String,
        fullUrlLower: String
    ): Boolean {

        for (d in proxyDomainRules) {
            if (host == d || host.endsWith(".$d")) return true
        }

        for (p in proxySubstringRules) {
            if (p.isNotEmpty() && fullUrlLower.contains(p)) return true
        }

        return false
    }

    private fun matchesAllowlist(
        host: String,
        fullUrlLower: String
    ): Boolean {

        for (d in allowDomainRules) {
            if (host == d || host.endsWith(".$d")) return true
        }

        for (p in allowSubstringRules) {
            if (p.isNotEmpty() && fullUrlLower.contains(p)) return true
        }

        return false
    }

    // =====================================================
    // IDENTIFICAÇÃO DE MANIFESTOS
    // =====================================================

    private fun isStreamManifestUrl(url: String): Boolean {

        val path = Uri.parse(url)
            .path
            ?.lowercase(Locale.ROOT)
            .orEmpty()

        return path.endsWith(".m3u8") ||
            path.endsWith(".mpd")
    }

    private fun isMediaUrl(u: String): Boolean {

        val path = Uri.parse(u)
            .path
            ?.lowercase(Locale.ROOT)
            .orEmpty()

        return path.endsWith(".m3u8") ||
            path.endsWith(".mpd") ||
            path.endsWith(".mp4") ||
            path.endsWith(".webm") ||
            path.endsWith(".ts") ||
            path.endsWith(".m4s") ||
            path.endsWith(".aac") ||
            path.endsWith(".mp3") ||
            path.endsWith(".oga") ||
            path.endsWith(".vtt") ||
            path.endsWith(".srt")
    }

    private fun empty204(): WebResourceResponse {

        return WebResourceResponse(
            "text/plain",
            "utf-8",
            204,
            "No Content",
            emptyMap(),
            ByteArrayInputStream(ByteArray(0))
        )
    }

    // =====================================================
    // JS INJECTIONS
    // =====================================================

    private fun injectAdShieldJS() {

        val tokens =
            (substringRules + domainRules.map { ".$it" })
                .filter { it.isNotBlank() }
                .take(2000)
                .joinToString(
                    separator = "\",\"",
                    prefix = "[\"",
                    postfix = "\"]"
                ) {
                    it.replace("\"", "")
                }

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

                location[k] = function(u){
                  if (isBad(u)) return;
                  try{orig(u);}catch(e){}
                };
              });

              Object.defineProperty(window, 'onbeforeunload', {
                get:()=>null,
                set:()=>true
              });

              window.addEventListener('click', function(e){
                let el = e.target;

                while (el && el !== document && !('href' in el)) {
                  el = el.parentElement;
                }

                if (el && el.href && isBad(el.href)) {
                  e.preventDefault();
                }
              }, true);

              const css = `
                [id*="ad"], [class*="ad"], .ads, .adsbox, .advert, .adunit,
                .ad-container, .ad-banner, .ad-overlay {
                  display:none !important;
                  pointer-events:none !important;
                }

                body { overscroll-behavior: contain; }
              `;

              const style = document.createElement('style');

              style.type = 'text/css';

              style.appendChild(document.createTextNode(css));

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

                location[k] = function(u){
                  if(!u) return;
                  try{orig(u);}catch(e){}
                };
              });

              const css = `
                .ad, .ads, .ad-overlay {
                  display:none !important;
                  pointer-events:none !important;
                }
              `;

              const style = document.createElement('style');

              style.type = 'text/css';

              style.appendChild(document.createTextNode(css));

              document.documentElement.appendChild(style);
            })();
        """.trimIndent()

        web.evaluateJavascript(js, null)
    }

    // =====================================================
    // UTILIDADES
    // =====================================================

    private fun showBlank(view: WebView?) {

        try {
            view?.stopLoading()
        } catch (_: Exception) {}

        try {
            view?.loadDataWithBaseURL(
                "about:blank",
                "",
                "text/html",
                "utf-8",
                null
            )
        } catch (_: Exception) {}
    }

    private fun isOnline(): Boolean {

        val cm =
            getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager

        val net = cm.activeNetwork ?: return false

        val caps = cm.getNetworkCapabilities(net) ?: return false

        return caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) ||
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
    }

    // =====================================================
    // OPERATOR DIALOG
    // =====================================================

    private fun showOperatorBlockedDialog() {

        if (operatorDialogOpen) return

        operatorDialogOpen = true

        operatorDialog = AlertDialog.Builder(this)

            .setTitle("📡 Possível bloqueio da operadora")

            .setMessage(
                "Este player pode estar bloqueado pela sua operadora móvel.\n\n" +
                    "Tente abrir usando Wi-Fi ou uma VPN como o 1.1.1.1 WARP."
            )

            .setNegativeButton("CONFIGURAR WI-FI") { _, _ ->

                returningFromSettings = true

                startActivity(
                    Intent(Settings.ACTION_WIFI_SETTINGS)
                )
            }

            .setNeutralButton("VPN") { _, _ ->

                returningFromSettings = true

                try {

                    val launchIntent =
                        packageManager.getLaunchIntentForPackage(
                            "com.cloudflare.onedotonedotonedotone"
                        )

                    if (launchIntent != null) {

                        startActivity(launchIntent)

                    } else {

                        startActivity(
                            Intent(
                                Intent.ACTION_VIEW,
                                Uri.parse(
                                    "market://details?id=com.cloudflare.onedotonedotonedotone"
                                )
                            )
                        )
                    }

                } catch (_: Exception) {

                    startActivity(
                        Intent(
                            Intent.ACTION_VIEW,
                            Uri.parse(
                                "https://play.google.com/store/apps/details?id=com.cloudflare.onedotonedotonedotone"
                            )
                        )
                    )
                }
            }

            .setPositiveButton("FECHAR") { _, _ ->

                operatorDialogOpen = false
                finish()
            }

            .create()

        operatorDialog?.setCanceledOnTouchOutside(false)

        operatorDialog?.setOnDismissListener {

            operatorDialogOpen = false
            operatorDialog = null
        }

        operatorDialog?.setOnShowListener {

            val c = getColor(R.color.menuColor)

            operatorDialog?.getButton(AlertDialog.BUTTON_POSITIVE)
                ?.setTextColor(c)

            operatorDialog?.getButton(AlertDialog.BUTTON_NEGATIVE)
                ?.setTextColor(c)

            operatorDialog?.getButton(AlertDialog.BUTTON_NEUTRAL)
                ?.setTextColor(c)
        }

        operatorDialog?.show()
    }

    // =====================================================
    // OFFLINE DIALOG
    // =====================================================

    private fun showOfflineDialog(
        onRetry: (() -> Unit)? = null
    ) {

        val d = AlertDialog.Builder(this)

            .setTitle("⚠️ Sem conexão")

            .setMessage(
                "Verifique sua internet e tente novamente."
            )

            .setNegativeButton("CONFIGURAR WI-FI") { _, _ ->

                returningFromSettings = true

                startActivity(
                    Intent(Settings.ACTION_WIFI_SETTINGS)
                )
            }

            .setPositiveButton("TENTAR NOVAMENTE") { _, _ ->

                if (isOnline()) {

                    blackShield.visibility = View.GONE

                    if (!lastMainUrl.isNullOrBlank()) {
                        web.loadUrl(lastMainUrl!!)
                    } else {
                        web.reload()
                    }

                } else {

                    showOfflineDialog(onRetry)
                }
            }

            .create()

        d.setOnShowListener {

            val c = getColor(R.color.menuColor)

            d.getButton(AlertDialog.BUTTON_POSITIVE)
                ?.setTextColor(c)

            d.getButton(AlertDialog.BUTTON_NEGATIVE)
                ?.setTextColor(c)
        }

        d.show()
    }
}
