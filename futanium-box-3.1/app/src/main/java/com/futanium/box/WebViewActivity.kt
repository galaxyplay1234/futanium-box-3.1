package com.futanium.box

import android.annotation.SuppressLint
import android.app.PictureInPictureParams
import android.content.Intent
import android.content.pm.ActivityInfo
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Rational
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

    // --- BLOQUEIO (lista remota) ---
    private val client = OkHttpClient()
    private val blocklistUrl =
        "https://raw.githubusercontent.com/galaxyplay1234/bloqueio-ads-futanium/refs/heads/main/blocklist.txt"

    private val domainRules = HashSet<String>()      // domínios exatos (bloqueio)
    private val substringRules = ArrayList<String>() // trechos na URL (bloqueio)

    // >>> ALLOWLIST (linhas iniciadas com "per:")
    private val allowDomainRules = HashSet<String>()      // domínios permitidos (navegação principal)
    private val allowSubstringRules = ArrayList<String>() // trechos permitidos (navegação principal)

    private var allowHost: String? = null            // host/eTLD+1 do player atual
    private val blockReady = AtomicBoolean(false)
    // --------------------------------

    // === MODO ENC. (bit.ly) ===
    private var shortenerActive: Boolean = false
    private fun isShortener(url: String, host: String?): Boolean {
        val u = url.lowercase(Locale.ROOT)
        val h = (host ?: "").lowercase(Locale.ROOT)
        // bit.ly direto OU bit.ly no caminho (ex.: rf.gd/bit.ly/...)
        return h == "bit.ly" || u.contains("/bit.ly/") || u.contains("://bit.ly/")
    }
    // ==========================

    // ===== Bridge para PiP disparado pelo player (JS -> Android) =====
    private inner class PipBridge {
        @JavascriptInterface
        fun enter() {
            runOnUiThread { goPiPAndShowList() }
        }
    }

    private fun goPiPAndShowList() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val params = PictureInPictureParams.Builder()
                .setAspectRatio(Rational(16, 9))
                .build()
            enterPictureInPictureMode(params)
        } else {
            @Suppress("DEPRECATION")
            enterPictureInPictureMode()
        }
        // traz a lista para frente (por baixo do PiP)
        val i = Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        startActivity(i)
    }
    // ================================================================

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Horizontal com rotação
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE

        // Nav bar visível e preta; status bar oculta
        WindowCompat.setDecorFitsSystemWindows(window, true)
        window.navigationBarColor = Color.BLACK
        insets = WindowInsetsControllerCompat(window, window.decorView).apply {
            isAppearanceLightNavigationBars = false
        }
        hideStatusBar()

        setContentView(R.layout.activity_webview)

        val initialUrl = intent.getStringExtra(EXTRA_URL).orEmpty()
        val initHost = runCatching { Uri.parse(initialUrl).host?.lowercase(Locale.ROOT) }.getOrNull()
        allowHost = initHost

        // ativa modo encurtador se a URL inicial já for encurtada
        if (isShortener(initialUrl, initHost)) shortenerActive = true

        // carrega blocklist em background
        Thread { loadBlocklist() }.start()

        web = findViewById(R.id.web)
        web.setBackgroundColor(Color.BLACK)
        web.keepScreenOn = true  // mantém a tela ligada sempre

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
        }

        // Bridge JS -> Android para PiP
        web.addJavascriptInterface(PipBridge(), "AndroidPip")

        web.webViewClient = object : WebViewClient() {

            // BLOQUEIO de navegação principal (redirecionamentos/clicks que trocam a página)
            override fun shouldOverrideUrlLoading(
                view: WebView,
                request: WebResourceRequest
            ): Boolean {
                val uri = request.url
                val u = uri.toString()
                val uLower = u.lowercase(Locale.ROOT)

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
                            Intent(Intent.ACTION_VIEW, Uri.parse(u))
                        )
                        true
                    } catch (_: Exception) { true }
                }

                // http/https
                if (u.startsWith("http")) {
                    val host = uri.host?.lowercase(Locale.ROOT) ?: return true

                    // 0) Encurtador (bit.ly): deixa passar até cair no player final
                    if (isShortener(u, host)) {
                        shortenerActive = true
                        return false
                    }

                    // 1) Allowlist (per:) permite e fixa host
                    if (matchesAllowlist(host, uLower)) {
                        allowHost = host
                        shortenerActive = false
                        return false
                    }

                    // 2) Se veio do encurtador, a primeira página destino vira o host do player
                    if (shortenerActive) {
                        allowHost = host
                        shortenerActive = false
                        return false
                    }

                    // 3) Fora isso, só navega dentro do mesmo host do player
                    val allow = allowHost
                    val same = allow != null && (host == allow || host.endsWith(".$allow"))
                    if (!same) return true

                    // 4) Se blocklist disser que é ad, barra
                    if (request.isForMainFrame && blockReady.get() && isBlocked(host, uLower)) {
                        return true
                    }

                    return false
                }

                // qualquer outro esquema desconhecido -> consumir (bloquear)
                return true
            }

            override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                super.onPageStarted(view, url, favicon)
                url?.let {
                    val h = runCatching { Uri.parse(it).host?.lowercase(Locale.ROOT) }.getOrNull()
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
                // injeta anti-ads + hook de PiP do player
                if (blockReady.get()) {
                    injectAdShieldJS()
                } else {
                    injectCoreShieldJS(emptyList())
                }
                injectPipHookJS() // <--- garante capturar o botão PiP do player
            }

            // BLOQUEIO de recursos secundários (scripts, iframes, imgs)
            override fun shouldInterceptRequest(
                view: WebView,
                request: WebResourceRequest
            ): WebResourceResponse? {
                // nunca bloquear o frame principal por aqui (deixa a navegação decidir)
                if (request.isForMainFrame) return null
                if (!blockReady.get()) return null

                val url = request.url.toString()
                val host = request.url.host?.lowercase(Locale.ROOT) ?: return null

                // mídia/legendas nunca bloquear
                if (isMediaUrl(url)) return null

                // NÃO bloqueia sub-recursos do mesmo host do player (evita quebrar)
                val allow = allowHost
                if (allow != null && (host == allow || host.endsWith(".$allow"))) {
                    return null
                }

                return if (isBlocked(host, url.lowercase(Locale.ROOT))) empty204() else null
            }
        }

        web.webChromeClient = object : WebChromeClient() {
    // redireciona window.open / target=_blank para o mesmo WebView
    override fun onCreateWindow(
        view: WebView?,
        isDialog: Boolean,
        isUserGesture: Boolean,
        resultMsg: android.os.Message?
    ): Boolean {
        val transport = resultMsg?.obj as? WebView.WebViewTransport ?: return false
        transport.webView = web   // reusa o mesmo WebView
        resultMsg.sendToTarget()
        return true
    }
}

        if (initialUrl.isNotBlank()) web.loadUrl(initialUrl)
    }

    private fun hideStatusBar() {
        insets.hide(WindowInsetsCompat.Type.statusBars())
        insets.isAppearanceLightNavigationBars = false
    }

    // Se o usuário apertar Home (ou gesture), entramos em PiP também
    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        goPiPAndShowList()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideStatusBar()
    }

    override fun onBackPressed() {
        finish() // fecha a activity (não deixa em segundo plano)
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

        text.lineSequence().forEach { raw ->
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("#")) return@forEach

            val isAllow = line.startsWith("per:", ignoreCase = true)
            val ruleText = if (isAllow) line.substringAfter("per:", "").trim() else line
            if (ruleText.isEmpty()) return@forEach

            val rule = ruleText.lowercase(Locale.ROOT)
            val isDomain = rule.contains('.') && !rule.contains(' ') && !rule.contains('/')

            if (isAllow) {
                if (isDomain) allowDomainRules += rule else allowSubstringRules += rule
            } else {
                if (isDomain) domainRules += rule else substringRules += rule
            }
        }
    }

    private fun isBlocked(host: String, fullUrlLower: String): Boolean {
        for (d in domainRules) if (host == d || host.endsWith(".$d")) return true
        for (p in substringRules) if (p.isNotEmpty() && fullUrlLower.contains(p)) return true
        return false
    }

    // >>> checa se a URL/host está permitida pela allowlist (per:)
    private fun matchesAllowlist(host: String, fullUrlLower: String): Boolean {
        for (d in allowDomainRules) if (host == d || host.endsWith(".$d")) return true
        for (p in allowSubstringRules) if (p.isNotEmpty() && fullUrlLower.contains(p)) return true
        return false
    }

    private fun isMediaUrl(u: String): Boolean {
        val x = u.lowercase(Locale.ROOT)
        return x.endsWith(".m3u8") || x.endsWith(".mpd") || x.endsWith(".ts") ||
                x.endsWith(".m4s") || x.endsWith(".mp4") || x.endsWith(".webm") ||
                x.endsWith(".aac") || x.endsWith(".mp3") || x.endsWith(".oga") ||
                x.endsWith(".vtt") || x.endsWith(".srt")
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
              ['assign','replace'].forEach(function(k){
                try {
                  var orig = location[k].bind(location);
                  location[k] = function(u){ if (isBad(u)) return; try{orig(u);}catch(e){} };
                } catch(e) {}
              });
              Object.defineProperty(window, 'onbeforeunload', {get:()=>null,set:()=>true});
              window.addEventListener('click', function(e){
                let el = e.target;
                while (el && el !== document && !('href' in el)) el = el.parentElement;
                if (el && el.href && isBad(el.href)) {
                  e.preventDefault(); e.stopImmediatePropagation(); return false;
                }
              }, true);
              const css = [
                '[id*="ad"]', '[class*="ad"]', '.ads', '.adsbox', '.advert', '.adunit',
                '.ad-container', '.ad-banner', '.ad-overlay', '[class*="overlay"]'
              ].join(',') + ' { display:none !important; pointer-events:none !important; } ' +
              'body { overscroll-behavior: contain; }';
              var style = document.createElement('style');
              style.type = 'text/css';
              style.appendChild(document.createTextNode(css));
              document.documentElement.appendChild(style);

              // (não bloqueamos timers aqui, só ignoramos strings maliciosas)
              var _setInterval = window.setInterval;
              window.setInterval = function(fn, t){
                if (typeof fn === 'string' && isBad(fn)) return 0;
                return _setInterval(fn, t);
              };
            })();
        """.trimIndent()
        web.evaluateJavascript(js, null)
    }

    // Fallback mínimo (sem lista remota)
    private fun injectCoreShieldJS(extraTokens: List<String>) {
        val js = """
            (function(){
              // anula window.open
              window.open = function(){ return null; };

              // blinda assign/replace (sem lista)
              ['assign','replace'].forEach(function(k){
                try {
                  var orig = location[k].bind(location);
                  location[k] = function(u){ if(!u) return; try{orig(u);}catch(e){} };
                } catch(e) {}
              });

              // remove overlays comuns
              var css = [
                '[class*="overlay"]',
                '.ad', '.ads', '.ad-overlay'
              ].join(',') + ' { display:none !important; pointer-events:none !important; }';

              var style = document.createElement('style');
              style.type = 'text/css';
              style.appendChild(document.createTextNode(css));
              document.documentElement.appendChild(style);
            })();
        """.trimIndent()
        web.evaluateJavascript(js, null)
    }

    // ---- Hook de Picture-in-Picture do player (HTML5 -> Android) ----
    private fun injectPipHookJS() {
        val js = """
            (function(){
              try {
                if (!window.__pip_hooked) {
                  window.__pip_hooked = true;
                  var orig = HTMLVideoElement.prototype.requestPictureInPicture;
                  if (orig) {
                    HTMLVideoElement.prototype.requestPictureInPicture = function(){
                      try { if (window.AndroidPip && AndroidPip.enter) AndroidPip.enter(); } catch(e){}
                      return orig.apply(this, arguments);
                    };
                  } else {
                    // alguns players disparam via document; ainda assim chamamos Android
                    document.addEventListener('enterpictureinpicture', function(){
                      try { if (window.AndroidPip && AndroidPip.enter) AndroidPip.enter(); } catch(e){}
                    }, {once:true});
                  }
                }
              } catch(e){}
            })();
        """.trimIndent()
        web.evaluateJavascript(js, null)
    }
}