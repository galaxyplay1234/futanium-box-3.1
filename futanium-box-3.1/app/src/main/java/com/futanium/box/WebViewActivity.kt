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

    // --- Controle de PiP ---
    private var isFullscreenVideo = false
    private var isVideoPlaying = false
    private var customViewCallback: WebChromeClient.CustomViewCallback? = null

    // --- BLOQUEIO (lista remota) ---
    private val client = OkHttpClient()
    private val blocklistUrl =
        "https://raw.githubusercontent.com/galaxyplay1234/bloqueio-ads-futanium/refs/heads/main/blocklist.txt"

    private val domainRules = HashSet<String>()      // domínios exatos (bloqueio)
    private val substringRules = ArrayList<String>() // trechos na URL (bloqueio)

    // >>> ALLOWLIST (linhas iniciadas com "per:")
    private val allowDomainRules = HashSet<String>()      // domínios permitidos (navegação principal)
    private val allowSubstringRules = ArrayList<String>() // trechos permitidos (navegação principal)

    private var allowHost: String? = null                  // host/eTLD+1 do player atual
    private val blockReady = AtomicBoolean(false)

    // === MODO ENC. (bit.ly) ===
    private var shortenerActive: Boolean = false
    private fun isShortener(url: String, host: String?): Boolean {
        val u = url.lowercase(Locale.ROOT)
        val h = (host ?: "").lowercase(Locale.ROOT)
        return h == "bit.ly" || u.contains("/bit.ly/") || u.contains("://bit.ly/")
    }

    // ===== Bridge JS -> Android =====
    private inner class PipBridge {
        @JavascriptInterface fun enter() { runOnUiThread { goPiPAndShowList() } }
    }
    private inner class PlayerStateBridge {
        @JavascriptInterface fun setPlaying(p: Boolean) { isVideoPlaying = p }
        @JavascriptInterface fun setFullscreen(f: Boolean) { isFullscreenVideo = f }
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
    // ==============================

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE

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
        if (isShortener(initialUrl, initHost)) shortenerActive = true

        Thread { loadBlocklist() }.start()

        web = findViewById(R.id.web)
        web.setBackgroundColor(Color.BLACK)
        web.keepScreenOn = true

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

        // Bridges JS
        web.addJavascriptInterface(PipBridge(), "AndroidPip")
        web.addJavascriptInterface(PlayerStateBridge(), "AndroidPlayerState")

        web.webViewClient = object : WebViewClient() {

            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val uri = request.url
                val u = uri.toString()
                val uLower = u.lowercase(Locale.ROOT)

                if (isMediaUrl(u) || u.startsWith("blob:") || u.startsWith("data:")) return false
                if (u == "about:blank") return false

                if (u.startsWith("intent://") || u.startsWith("market://")
                    || u.startsWith("mailto:") || u.startsWith("tel:") || u.startsWith("sms:")
                ) {
                    return try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(u))); true }
                    catch (_: Exception) { true }
                }

                if (u.startsWith("http")) {
                    val host = uri.host?.lowercase(Locale.ROOT) ?: return true

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

            override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                super.onPageStarted(view, url, favicon)
                url?.let {
                    val h = runCatching { Uri.parse(it).host?.lowercase(Locale.ROOT) }.getOrNull()
                    if (isShortener(it, h)) shortenerActive = true
                    else { allowHost = h; shortenerActive = false }
                }
                // reset estado de reprodução quando troca de página
                isVideoPlaying = false
            }

            override fun onPageFinished(view: WebView, url: String) {
                super.onPageFinished(view, url)
                if (blockReady.get()) injectAdShieldJS() else injectCoreShieldJS(emptyList())
                injectPipHookJS()
                injectVideoStateHookJS() // <- marca play/pause/fullscreen do vídeo
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

            // FULLSCREEN do player
            override fun onShowCustomView(view: android.view.View?, callback: CustomViewCallback?) {
                isFullscreenVideo = true
                customViewCallback = callback
                super.onShowCustomView(view, callback)
            }

            override fun onHideCustomView() {
                isFullscreenVideo = false
                customViewCallback?.onCustomViewHidden()
                customViewCallback = null
                super.onHideCustomView()
            }

            // Reaproveita o mesmo WebView para window.open/target=_blank (evita travar)
            override fun onCreateWindow(
                view: WebView?,
                isDialog: Boolean,
                isUserGesture: Boolean,
                resultMsg: android.os.Message?
            ): Boolean {
                val transport = resultMsg?.obj as? WebView.WebViewTransport ?: return false
                transport.webView = web
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

    // Home/Recentes: só entra em PiP se estiver fullscreen OU tocando vídeo
    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && (isFullscreenVideo || isVideoPlaying)) {
            val params = PictureInPictureParams.Builder()
                .setAspectRatio(Rational(16, 9))
                .build()
            enterPictureInPictureMode(params)

            // volta para a lista (Activity principal) por baixo do PiP
            val i = Intent(this, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            }
            startActivity(i)
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideStatusBar()
    }

    override fun onBackPressed() {
        finish()
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
        } catch (_: Exception) { }
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

    // ---------- Anti-ads ----------
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
                  const t = LIST[i]; if(!t) continue;
                  if(t.startsWith(".")){
                    try{ const h = new URL(u, location.href).host.toLowerCase();
                      if(h===t.slice(1) || h.endsWith(t)) return true; }catch(e){}
                  } else { if(u.indexOf(t)!==-1) return true; }
                }
                return false;
              }
              window.open = function(u){ if(isBad(u)) return null; return null; };
              ['assign','replace'].forEach(function(k){
                try{ var orig = location[k].bind(location);
                  location[k]=function(u){ if(isBad(u)) return; try{orig(u);}catch(e){} }; }catch(e){}
              });
              Object.defineProperty(window,'onbeforeunload',{get:()=>null,set:()=>true});
              window.addEventListener('click',function(e){
                let el=e.target; while(el&&el!==document&&!("href" in el)) el=el.parentElement;
                if(el&&el.href&&isBad(el.href)){ e.preventDefault(); e.stopImmediatePropagation(); return false; }
              },true);
              var css=['[id*="ad"]','[class*="ad"]','.ads','.adsbox','.advert','.adunit',
                       '.ad-container','.ad-banner','.ad-overlay','[class*="overlay"]'
                      ].join(',')+'{display:none!important;pointer-events:none!important;} body{overscroll-behavior:contain;}';
              var s=document.createElement('style'); s.type='text/css'; s.appendChild(document.createTextNode(css));
              document.documentElement.appendChild(s);
              var _setInterval=window.setInterval;
              window.setInterval=function(fn,t){ if(typeof fn==='string'&&isBad(fn)) return 0; return _setInterval(fn,t); };
            })();
        """.trimIndent()
        web.evaluateJavascript(js, null)
    }

    private fun injectCoreShieldJS(extraTokens: List<String>) {
        val js = """
            (function(){
              window.open=function(){return null;};
              ['assign','replace'].forEach(function(k){
                try{var o=location[k].bind(location); location[k]=function(u){if(!u)return;try{o(u);}catch(e){}};}catch(e){}
              });
              var css='[class*="overlay"],.ad,.ads,.ad-overlay{display:none!important;pointer-events:none!important;}';
              var s=document.createElement('style'); s.type='text/css'; s.appendChild(document.createTextNode(css));
              document.documentElement.appendChild(s);
            })();
        """.trimIndent()
        web.evaluateJavascript(js, null)
    }

    // ---- Hook de PiP e estado do vídeo ----
    private fun injectPipHookJS() {
        val js = """
            (function(){
              try{
                if(!window.__pip_hooked){
                  window.__pip_hooked=true;
                  var orig = HTMLVideoElement.prototype.requestPictureInPicture;
                  if(orig){
                    HTMLVideoElement.prototype.requestPictureInPicture=function(){
                      try{ if(window.AndroidPip&&AndroidPip.enter) AndroidPip.enter(); }catch(e){}
                      try{ return orig.apply(this, arguments); }catch(e){ return Promise.reject(e); }
                    };
                  } else {
                    document.addEventListener('enterpictureinpicture', function(){
                      try{ if(window.AndroidPip&&AndroidPip.enter) AndroidPip.enter(); }catch(e){}
                    }, {once:true});
                  }
                }
              }catch(e){}
            })();
        """.trimIndent()
        web.evaluateJavascript(js, null)
    }

    private fun injectVideoStateHookJS() {
        val js = """
            (function(){
              try{
                if(!window.__video_state_hooked){
                  window.__video_state_hooked = true;
                  function hook(v){
                    if(!v || v.__hooked) return; v.__hooked = true;
                    v.addEventListener('play',  function(){ try{AndroidPlayerState.setPlaying(true);}catch(e){} }, {passive:true});
                    v.addEventListener('pause', function(){ try{AndroidPlayerState.setPlaying(false);}catch(e){} }, {passive:true});
                    ['webkitbeginfullscreen','fullscreenchange','enterpictureinpicture'].forEach(function(ev){
                      v.addEventListener(ev, function(){ try{AndroidPlayerState.setFullscreen(true);}catch(e){} }, {passive:true});
                    });
                    ['webkitendfullscreen','leavepictureinpicture'].forEach(function(ev){
                      v.addEventListener(ev, function(){ try{AndroidPlayerState.setFullscreen(false);}catch(e){} }, {passive:true});
                    });
                  }
                  document.querySelectorAll('video').forEach(hook);
                  var mo = new MutationObserver(function(muts){
                    muts.forEach(function(m){
                      m.addedNodes && m.addedNodes.forEach(function(n){
                        if(n.tagName && n.tagName.toLowerCase()==='video') hook(n);
                        if(n.querySelectorAll) n.querySelectorAll('video').forEach(hook);
                      });
                    });
                  });
                  mo.observe(document.documentElement, {childList:true,subtree:true});
                }
              }catch(e){}
            })();
        """.trimIndent()
        web.evaluateJavascript(js, null)
    }
}