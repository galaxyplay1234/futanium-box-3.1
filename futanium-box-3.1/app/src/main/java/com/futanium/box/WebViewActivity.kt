package com.futanium.box

import android.annotation.SuppressLint
import android.graphics.Color
import android.net.Uri
import android.os.Build
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

    // ==== listas simples (pode ampliar depois) ====
    private val adHosts = setOf(
        "doubleclick.net","googlesyndication.com","googletagservices.com",
        "adservice.google.com","adservice.google.com.br",
        "adnxs.com","taboola.com","outbrain.com","criteo.com",
        "bet365.com","betway.com","popads.net","propellerads.com",
        "exoclick.com","revcontent.com","zedo.com","moatads.com"
    )
    private val adUrlKeywords = listOf(
        "/ads?","/ads/","adserver","advert","banner","popunder","popup",
        "interstitial","push-notification",".m3u8?ads","/vast","/vmap",
        "clickid=","gclid=","utm_campaign=ad"
    )

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        WindowCompat.setDecorFitsSystemWindows(window, true) // nav bar visível
        window.navigationBarColor = Color.BLACK
        insets = WindowInsetsControllerCompat(window, window.decorView).apply {
            isAppearanceLightNavigationBars = false
        }
        hideStatusBar()

        setContentView(R.layout.activity_webview)

        val initialUrl = intent.getStringExtra(EXTRA_URL).orEmpty()

        web = findViewById(R.id.web)
        web.setBackgroundColor(Color.BLACK)
        web.keepScreenOn = true
        web.isHapticFeedbackEnabled = false
        web.setOnLongClickListener { true } // sem menu ao segurar
        web.isLongClickable = false

        with(web.settings) {
            javaScriptEnabled = true
            domStorageEnabled = true
            mediaPlaybackRequiresUserGesture = false

            // bloquear zoom
            setSupportZoom(false)
            builtInZoomControls = false
            displayZoomControls = false

            useWideViewPort = true
            loadWithOverviewMode = true
            mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW

            setSupportMultipleWindows(false)              // não abre nova janela
            javaScriptCanOpenWindowsAutomatically = false // bloqueia window.open
        }

        // cookies de terceiros off
        try { CookieManager.getInstance().setAcceptThirdPartyCookies(web, false) } catch (_: Throwable) {}

        // SafeBrowsing (somente API 26+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            try { WebView.startSafeBrowsing(this, null) } catch (_: Throwable) {}
        }

        web.webViewClient = object : WebViewClient() {

            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val u = request.url.toString()
                return when {
                    !u.startsWith("http") -> {
                        try { startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, Uri.parse(u))) } catch (_: Exception) {}
                        true
                    }
                    isAdUrl(u) -> true // bloqueia navegação para anúncio
                    else -> false
                }
            }

            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                val u = request.url.toString()
                if (isAdUrl(u)) {
                    return WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream(ByteArray(0)))
                }
                return super.shouldInterceptRequest(view, request)
            }

            override fun onPageFinished(view: WebView, url: String) {
                super.onPageFinished(view, url)
                injectAntiPopupJs()
                injectAdHiderCss()
            }
        }

        web.webChromeClient = object : WebChromeClient() {
            override fun onCreateWindow(view: WebView, isDialog: Boolean, isUserGesture: Boolean, resultMsg: android.os.Message?): Boolean {
                return false // recusa criar nova janela
            }
            override fun onJsAlert(view: WebView?, url: String?, message: String?, result: JsResult?): Boolean {
                result?.cancel(); return true
            }
            override fun onJsConfirm(view: WebView?, url: String?, message: String?, result: JsResult?): Boolean {
                result?.cancel(); return true
            }
            override fun onJsPrompt(view: WebView?, url: String?, message: String?, defaultValue: String?, result: JsPromptResult?): Boolean {
                result?.cancel(); return true
            }
        }

        if (initialUrl.isNotBlank()) web.loadUrl(initialUrl)
    }

    private fun isAdUrl(url: String): Boolean {
        val u = try { Uri.parse(url) } catch (_: Exception) { null } ?: return false
        val host = (u.host ?: "").lowercase(Locale.ROOT)
        if (adHosts.any { host == it || host.endsWith(".$it") }) return true
        val full = url.lowercase(Locale.ROOT)
        if (adUrlKeywords.any { it in full }) return true
        return false
    }

    private fun injectAntiPopupJs() {
        val blockedHostsJsArray = adHosts.joinToString(",") { "'$it'" }
        val blockedKwJsArray = adUrlKeywords.joinToString(",") { "'$it'" }

        val js = """
            (function(){
              try{
                window.open = function(){ return null; };
                Array.prototype.forEach.call(document.querySelectorAll('a[target="_blank"]'), function(a){
                  a.setAttribute('target','_self');
                });
                var AD_HOSTS = [$blockedHostsJsArray];
                var AD_KW = [$blockedKwJsArray];
                function isAd(href){
                  if(!href) return false;
                  var u; try{ u=new URL(href, location.href);}catch(e){return false;}
                  var host = (u.host||'').toLowerCase();
                  for (var i=0;i<AD_HOSTS.length;i++){
                    var h = AD_HOSTS[i];
                    if (host===h || host.endsWith('.'+h)) return true;
                  }
                  var full = u.href.toLowerCase();
                  for (var j=0;j<AD_KW.length;j++){
                    if (full.indexOf(AD_KW[j])>=0) return true;
                  }
                  return false;
                }
                document.addEventListener('click', function(e){
                  var a = e.target && e.target.closest ? e.target.closest('a') : null;
                  if(!a) return;
                  var href = a.getAttribute('href');
                  if(isAd(href)){
                    e.preventDefault(); e.stopImmediatePropagation(); e.stopPropagation();
                  } else {
                    a.setAttribute('target','_self');
                  }
                }, true);
              }catch(e){}
            })();
        """.trimIndent()

        try { web.post { web.evaluateJavascript(js, null) } } catch (_: Exception) {}
    }

    private fun injectAdHiderCss() {
        val css = """
            *[id*="ad"], *[class*="ad"],
            *[id*="banner"], *[class*="banner"],
            *[id*="advert"], *[class*="advert"],
            *[id*="pop"], *[class*="pop"],
            [class*="sticky"], [id*="sticky"]
            { display:none !important; }
            html, body { background:#000 !important; }
        """.trimIndent().replace("\n", " ").replace("'", "\\'")
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
        if (this::web.isInitialized && web.canGoBack()) web.goBack() else super.onBackPressed()
    }

    companion object { const val EXTRA_URL = "url" }
}