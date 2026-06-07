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
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

class YoutubeWebViewActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_URL = "url"
    }

    private lateinit var web: WebView

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)

    window.statusBarColor = Color.BLACK
    window.navigationBarColor = Color.BLACK

    WindowCompat.setDecorFitsSystemWindows(window, true)

    WindowInsetsControllerCompat(
        window,
        window.decorView
    ).apply {

        isAppearanceLightStatusBars = false
        isAppearanceLightNavigationBars = false

        hide(
            WindowInsetsCompat.Type.statusBars()
        )

        systemBarsBehavior =
            WindowInsetsControllerCompat
                .BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
    }

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
    request: android.webkit.WebResourceRequest
): Boolean {
    return false
}
}
        web.webChromeClient = object : WebChromeClient() {

    private var customView: android.view.View? = null
    private var callback: CustomViewCallback? = null

    override fun onShowCustomView(
        view: android.view.View?,
        callback: CustomViewCallback?
    ) {

        customView = view
        this.callback = callback

        addContentView(
            view,
            ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )

        WindowInsetsControllerCompat(
            window,
            window.decorView
        ).hide(
            WindowInsetsCompat.Type.statusBars()
            
        )
    }

    override fun onHideCustomView() {

        (customView?.parent as? ViewGroup)
            ?.removeView(customView)

        customView = null

        callback?.onCustomViewHidden()
    }
}

        val url = intent.getStringExtra(EXTRA_URL) ?: ""
        web.loadUrl(url)
    }

  override fun onWindowFocusChanged(hasFocus: Boolean) {
    super.onWindowFocusChanged(hasFocus)

    if (hasFocus) {
        WindowInsetsControllerCompat(
            window,
            window.decorView
        ).hide(
            WindowInsetsCompat.Type.statusBars()
            
        )
    }
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