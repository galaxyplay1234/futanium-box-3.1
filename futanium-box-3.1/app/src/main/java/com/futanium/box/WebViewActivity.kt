package com.futanium.box

import android.annotation.SuppressLint
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
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

    private val domainRules = HashSet<String>()      // domínios exatos
    private val substringRules = ArrayList<String>() // trechos na URL

    private var allowHost: String? = null            // host/eTLD+1 do player
    private val blockReady = AtomicBoolean(false)
    // --------------------------------

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        WindowCompat.setDecorFitsSystemWindows(window, true)
        window.navigationBarColor = Color.BLACK
        insets = WindowInsetsControllerCompat(window, window.decorView).apply {
            isAppearanceLightNavigationBars = false
        }
        hideStatusBar()

        setContentView(R.layout.activity_webview)

        val initialUrl = intent.getStringExtra(EXTRA_URL).orEmpty()
        allowHost = runCatching { Uri.parse(initialUrl).host?.lowercase(Locale.ROOT) }.getOrNull()

        // carrega blocklist em background
        Thread { loadBlocklist() }.start()

        web = findViewById(R.id.web)
        web.setBackgroundColor(Color.BLACK)
        web.keepScreenOn = true

        with(web.settings) {
            javaScriptEnabled = true
            domStorageEnabled = true
            mediaPlaybackRequiresUserGesture = false

            builtInZoomControls = false
            displayZoomControls = false
            setSupportZoom(false)

            // Força layout MOBILE (controles grandes)
            useWideViewPort = false
            loadWithOverviewMode = false

            userAgentString =
                "Mozilla/5.0 (Linux; Android 13; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"

            mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW

            // bloqueia pop-ups
            setSupportMultipleWindows(false)
            javaScriptCanOpenWindowsAutomatically = false
        }

        web.webViewClient = object : WebViewClient() {

            override fun shouldOverrideUrlLoading(
                view: WebView,
                request: WebResourceRequest
            ): Boolean {
                val uri = request.url
                val u = uri.toString()
                // Sempre permite mídia/blobs na própria guia
                if (isMediaUrl(u) || u.startsWith("blob:") || u.startsWith("data:")) return false

                // navegação http/https: só permite dentro do mesmo eTLD+1 do player
                if (u.startsWith("http")) {
                    val host = uri.host?.lowercase(Locale.ROOT) ?: return true
                    val allow = allowHost
                    // se for o mesmo host (ou subdomínio) do player, deixa; senão, bloqueia (ad)
                    val same = allow != null && (host == allow || host.endsWith(".$allow"))
                    return !same // true => a gente consome e BLOQUEIA a navegação
                }

                // esquemas externos (intent:, whatsapp:, etc.) — permitir abrir fora
                return try {
                    startActivity(
                        android.content.Intent(
                            android.content.Intent.ACTION_VIEW,
                            Uri.parse(u)
                        )
                    )
                    true
                } catch (_: Exception) {
                    true
                }
            }

            override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                super.onPageStarted(view, url, favicon)
                url?.let {
                    // atualiza o host principal se o player trocar de URL
                    allowHost = runCatching { Uri.parse(it).host?.lowercase(Locale.ROOT) }.getOrNull()
                }
            }

            override fun onPageFinished(view: WebView, url: String) {
                super.onPageFinished(view, url)
                // injeta JS para neutralizar redirecionamentos/overlays e bloquear cliques para domínios da lista
                if (blockReady.get()) {
                    injectAdShieldJS()
                } else {
                    // mesmo sem lista, ainda anulamos window.open/overlays
                    injectCoreShieldJS(emptyList())
                }
            }

            // Bloqueio de recursos secundários (imagens, scripts, iframes)
            override fun shouldInterceptRequest(
                view: WebView,
                request: WebResourceRequest
            ): WebResourceResponse? {
                if (request.isForMainFrame) return null
                if (!blockReady.get()) return null

                val url = request.url.toString()
                val host = request.url.host?.lowercase(Locale.ROOT) ?: return null

                // nunca bloquear mídia do player
                if (isMediaUrl(url) || url.startsWith("blob:") || url.startsWith("data:")) return null

                val allow = allowHost
                if (allow != null && (host == allow || host.endsWith(".$allow"))) return null

                if (isBlocked(host, url.lowercase(Locale.ROOT))) {
                    return WebResourceResponse(
                        "text/plain", "utf-8",
                        204, "No Content",
                        emptyMap(),
                        ByteArrayInputStream(ByteArray(0))
                    )
                }
                return null
            }
        }

        web.webChromeClient = object : WebChromeClient() {
            override fun onCreateWindow(
                view: WebView?,
                isDialog: Boolean,
                isUserGesture: Boolean,
                resultMsg: android.os.Message?
            ): Boolean {
                // bloqueia window.open / target=_blank
                return false
            }
        }

        if (initialUrl.isNotBlank()) web.loadUrl(initialUrl)
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
        if (this::web.isInitialized && web.canGoBack()) {
            web.goBack()
        } else {
            super.onBackPressed()
        }
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
        text.lineSequence().forEach { raw ->
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("#")) return@forEach
            val rule = line.lowercase(Locale.ROOT)
            val isDomain = rule.contains('.') && !rule.contains(' ') && !rule.contains('/')
            if (isDomain) domainRules += rule else substringRules += rule
        }
    }

    private fun isBlocked(host: String, fullUrlLower: String): Boolean {
        for (d in domainRules) if (host == d || host.endsWith(".$d")) return true
        for (p in substringRules) if (p.isNotEmpty() && fullUrlLower.contains(p)) return true
        return false
    }

    private fun isMediaUrl(u: String): Boolean {
        val x = u.lowercase(Locale.ROOT)
        return x.endsWith(".m3u8") || x.endsWith(".mpd") || x.endsWith(".ts") ||
               x.endsWith(".m4s")  || x.endsWith(".mp4") || x.endsWith(".webm") ||
               x.endsWith(".aac")  || x.endsWith(".mp3") || x.endsWith(".oga") ||
               x.endsWith(".vtt")  || x.endsWith(".srt")
    }

    // ---------- Injeção de JS anti-pop/overlay/redirecionamento ----------

    private fun injectAdShieldJS() {
        // junta tudo num array JS simples (apenas substrings; domínios entram como "*.dominio")
        val tokens = (substringRules + domainRules.map { ".$it" })
            .filter { it.isNotBlank() }
            .take(2000) // segurança
            .joinToString(separator = "\",\"", prefix = "[\"", postfix = "\"]") { it.replace("\"", "") }
        val js = """
            (function(){
              const LIST = $tokens;

              // helper: verifica se a URL contém algum token da lista
              function isBad(u){
                if(!u) return false;
                u = (""+u).toLowerCase();
                for (let i=0;i<LIST.length;i++){
                  const t = LIST[i];
                  if(!t) continue;
                  if(t.startsWith(".")) {
                    // domínio: confere host
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

              // neutraliza APIs de navegação maliciosa
              const NOP = function(){};
              window.open = function(u){ if (isBad(u)) return null; return null; };
              ['assign','replace'].forEach(k=>{
                const orig = location[k].bind(location);
                location[k] = function(u){ if (isBad(u)) return; try{orig(u);}catch(e){} };
              });
              Object.defineProperty(window, 'onbeforeunload', {get:()=>null,set:()=>true});

              // captura cliques: bloqueia anchors e handlers que tentem levar a domínio ruim
              window.addEventListener('click', function(e){
                let el = e.target;
                // sobe na árvore até achar <a>
                while (el && el !== document && !('href' in el)) el = el.parentElement;
                if (el && el.href && isBad(el.href)) {
                  e.preventDefault(); e.stopImmediatePropagation(); return false;
                }
              }, true);

              // remove overlays comuns de anúncio
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

              // desarma timers que trocam top.location
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
        // fallback mínimo (sem lista remota)
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
}