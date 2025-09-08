package com.futanium.box

import android.os.Build
import android.os.Bundle
import android.view.KeyEvent
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.appcompat.app.AppCompatActivity
import com.futanium.box.databinding.ActivityExternalWebviewBinding

class ExternalWebViewActivity : AppCompatActivity() {

    private lateinit var vb: ActivityExternalWebviewBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        vb = ActivityExternalWebviewBinding.inflate(layoutInflater)
        setContentView(vb.root)

        // Equivalente ao PhoneWakeState.KeepAlive(true)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        // Fullscreen imersivo (como o B4A com fullScreen = true)
        enterImmersiveMode()

        setupWebView(vb.webView)

        // URL “igual ao main._link” do B4A (vem por Intent)
        val url = intent.getStringExtra(EXTRA_URL) ?: DEFAULT_URL
        vb.webView.loadUrl(url)
    }

    private fun setupWebView(wv: WebView) = with(wv.settings) {
        javaScriptEnabled = true
        domStorageEnabled = true
        databaseEnabled = true
        loadsImagesAutomatically = true

        // mídia sem exigir toque (auto-play quando o site permitir)
        mediaPlaybackRequiresUserGesture = false

        // performance/visual
        cacheMode = WebSettings.LOAD_DEFAULT
        useWideViewPort = true
        loadWithOverviewMode = true
        builtInZoomControls = false
        displayZoomControls = false

        // aceitar http dentro de https (útil pros seus http/m3u8)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
        }
    }.also {
        vb.webView.webViewClient = object : WebViewClient() {}
        vb.webView.webChromeClient = object : WebChromeClient() {}
    }

    // Reaplica o modo imersivo quando a janela ganha foco
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) enterImmersiveMode()
    }

    private fun enterImmersiveMode() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val controller = window.insetsController
            controller?.let {
                it.hide(WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars())
                it.systemBarsBehavior =
                    WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility =
                (android.view.View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        or android.view.View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        or android.view.View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                        or android.view.View.SYSTEM_UI_FLAG_FULLSCREEN
                        or android.view.View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        or android.view.View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY)
        }
    }

    // Botão voltar: volta no histórico da WebView antes de fechar
    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK && vb.webView.canGoBack()) {
            vb.webView.goBack()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    companion object {
        const val EXTRA_URL = "extra_url"
        private const val DEFAULT_URL = "https://example.com/" // fallback
        fun start(context: android.content.Context, url: String) {
            val i = android.content.Intent(context, ExternalWebViewActivity::class.java)
            i.putExtra(EXTRA_URL, url)
            context.startActivity(i)
        }
    }
}