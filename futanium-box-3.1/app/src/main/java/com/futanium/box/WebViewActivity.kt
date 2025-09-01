package com.futanium.box

import android.annotation.SuppressLint
import android.graphics.Color
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.*
import android.widget.ProgressBar
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity

class WebViewActivity : AppCompatActivity() {

    private lateinit var web: WebView
    private lateinit var loader: ProgressBar
    private var initialHost: String? = null
    private var lastUrl: String? = null

    // 🔒 Lista simples de bloqueio (curta e eficaz)
    private val BAD = arrayOf(
        "doubleclick", "googlesyndication", "adsystem", "adservice",
        "adnxs", "taboola", "outbrain", "popads", "adsterra",
        "propeller", "revcontent", "exoclick", "trafmag", "onclick",
        "pushlend", "adhigh", "/banner", "/popunder", "/popup",
        "clks", "trk", "utm_", "js/ad", "ads.js", "advert", "overlay",
        "sponsor", "safeframe", "impression", "pixel", "metrics", "tracking"
    )

    override fun onBackPressed() { finish() }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_webview)

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.statusBarColor = Color.BLACK
        window.navigationBarColor = Color.BLACK

        loader = findViewById(R.id.webLoader)
        web = findViewById(R.id.web)

        // 🔧 Configuração mínima
        with(web) {
            setBackgroundColor(Color.BLACK)
            keepScreenOn = true
            overScrollMode = View.OVER_SCROLL_NEVER
            isVerticalScrollBarEnabled = false
            isHorizontalScrollBarEnabled = false
            scrollBarStyle = View.SCROLLBARS_INSIDE_OVERLAY
            isLongClickable = false
            setOnLongClickListener { true } // sem seleção/cópia

            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.mediaPlaybackRequiresUserGesture = false
            settings.setSupportZoom(false)
            settings.builtInZoomControls = false
            settings.displayZoomControls = false
            settings.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            settings.userAgentString =
                "Mozilla/5.0 (Linux; Android 13; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"
        }

        val startUrl = intent.getStringExtra(EXTRA_URL).orEmpty()
        initialHost = runCatching { Uri.parse(startUrl).host?.lowercase() }.getOrNull()

        web.webViewClient = object : WebViewClient() {

            // 🔒 Mantém no mesmo host do player e bloqueia navegações suspeitas
            override fun shouldOverrideUrlLoading(v: WebView, req: WebResourceRequest): Boolean {
                val u = req.url.toString()
                lastUrl = u

                // deixa mídia no próprio frame
                if (isMedia(u) || u.startsWith("blob:") || u.startsWith("data:") || u == "about:blank")
                    return false

                if (!isOnline()) {
                    showNoNetDialog(); return true
                }

                val host = req.url.host?.lowercase()
                val allowSameHost = initialHost != null && (host == initialHost || host?.endsWith(".${initialHost}") == true)
                return !allowSameHost // bloqueia tudo fora do player
            }

            override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                loader.visibility = View.VISIBLE
                if (!url.isNullOrBlank()) lastUrl = url
            }

            override fun onPageFinished(view: WebView, url: String) {
                loader.visibility = View.GONE

                // 🛑 Remove tap-highlight (flash azul) e overlays chatos
                val js = """
                    (function(){
                      try{
                        const css = `
                          * { -webkit-tap-highlight-color: rgba(0,0,0,0) !important; outline: none !important; }
                          html, body { -webkit-tap-highlight-color: transparent !important; }
                          [class*="ad"], [id*="ad"], .ads, .ad, .ad-container, .ad-overlay,
                          [class*="overlay"], .backdrop, .modal, .popup, .pop, .banner {
                            display: none !important; pointer-events: none !important;
                          }
                        `;
                        var st = document.createElement('style');
                        st.type = 'text/css';
                        st.appendChild(document.createTextNode(css));
                        document.documentElement.appendChild(st);

                        // corta popups/redirecionamentos
                        window.open = function(){ return null; };
                        ['assign','replace'].forEach(function(k){
                          var o = location[k].bind(location);
                          location[k] = function(u){ if(!u) return; try{o(u);}catch(e){} };
                        });
                        window.addEventListener('click', function(e){
                          var el = e.target;
                          while (el && el !== document && !('href' in el)) el = el.parentElement;
                          if (el && el.href) {
                            var h = (new URL(el.href, location.href)).host.toLowerCase();
                            var base = "${initialHost ?: ""}";
                            if (base && h !== base && !h.endsWith("." + base)) {
                              e.preventDefault(); e.stopImmediatePropagation(); return false;
                            }
                          }
                        }, true);
                      }catch(e){}
                    })();
                """.trimIndent()
                view.evaluateJavascript(js, null)
            }

            // 🚫 Bloqueia recursos típicos de anúncio (curto e eficiente)
            override fun shouldInterceptRequest(v: WebView, req: WebResourceRequest): WebResourceResponse? {
                if (req.isForMainFrame) return null
                val u = req.url.toString().lowercase()
                if (isMedia(u)) return null
                for (t in BAD) if (u.contains(t)) {
                    return WebResourceResponse("text/plain", "utf-8", 204, "No Content", emptyMap(), null)
                }
                return null
            }

            override fun onReceivedSslError(view: WebView?, handler: SslErrorHandler?, error: android.net.http.SslError?) {
                handler?.cancel(); showNoNetDialog()
            }

            override fun onReceivedError(view: WebView?, request: WebResourceRequest, error: WebResourceError) {
                if (request.isForMainFrame) showNoNetDialog()
            }
        }

        // 🚫 Cancela abertura de novas janelas/abas
        web.webChromeClient = object : WebChromeClient() {
            override fun onCreateWindow(view: WebView?, isDialog: Boolean, isUserGesture: Boolean, resultMsg: android.os.Message?): Boolean {
                return false
            }
        }

        if (startUrl.isNotBlank()) {
            lastUrl = startUrl
            web.loadUrl(startUrl)
        }
    }

    private fun isMedia(u: String): Boolean {
        val x = u.lowercase()
        return x.endsWith(".m3u8") || x.endsWith(".mpd") || x.endsWith(".ts") ||
               x.endsWith(".m4s")  || x.endsWith(".mp4") || x.endsWith(".webm") ||
               x.endsWith(".aac")  || x.endsWith(".mp3") || x.endsWith(".oga") ||
               x.endsWith(".vtt")  || x.endsWith(".srt")
    }

    private fun isOnline(): Boolean {
        val cm = getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager
        val n = cm.activeNetwork ?: return false
        val c = cm.getNetworkCapabilities(n) ?: return false
        return c.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
                || c.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)
                || c.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
    }

    private fun showNoNetDialog() {
        runOnUiThread {
            AlertDialog.Builder(this)
                .setTitle("Sem conexão")
                .setMessage("Verifique sua internet e tente novamente.")
                .setPositiveButton("OK", null)
                .show()
        }
    }

    companion object { const val EXTRA_URL = "url" }
}