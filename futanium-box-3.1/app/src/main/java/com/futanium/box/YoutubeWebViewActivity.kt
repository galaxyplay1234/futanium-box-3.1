package com.futanium.box

import android.annotation.SuppressLint
import android.graphics.Color
import android.net.http.SslError
import android.os.Bundle
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.SslErrorHandler
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.appcompat.app.AppCompatActivity

class YoutubeWebViewActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_URL = "url"
    }

    private lateinit var web: WebView

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        web = WebView(this)
        web.layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        )

        setContentView(web)

        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(web, true)

        web.setBackgroundColor(Color.BLACK)

        with(web.settings) {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            allowFileAccess = true
            allowContentAccess = true

            mediaPlaybackRequiresUserGesture = false
            loadsImagesAutomatically = true

            mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW

            userAgentString =
                "Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/136.0.0.0 Mobile Safari/537.36"
        }

        web.webViewClient = object : WebViewClient() {

            override fun onReceivedSslError(
                view: WebView?,
                handler: SslErrorHandler?,
                error: SslError?
            ) {
                handler?.proceed()
            }

            override fun shouldOverrideUrlLoading(
                view: WebView,
                url: String
            ): Boolean {
                return false
            }
        }

        web.webChromeClient = WebChromeClient()

        val url = intent.getStringExtra(EXTRA_URL) ?: ""
        web.loadUrl(url)
    }

    override fun onBackPressed() {
        if (web.canGoBack()) {
            web.goBack()
        } else {
            super.onBackPressed()
        }
    }

    override fun onDestroy() {
        web.destroy()
        super.onDestroy()
    }
}