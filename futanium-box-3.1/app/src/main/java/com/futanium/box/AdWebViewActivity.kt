package com.futanium.box

import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.os.CountDownTimer
import android.view.View
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.futanium.box.databinding.ActivityAdWebviewBinding

class AdWebViewActivity : AppCompatActivity() {

    private lateinit var vb: ActivityAdWebviewBinding
    private var countdown: CountDownTimer? = null

    companion object {
        const val EXTRA_AD_URL = "extra_ad_url"
        const val EXTRA_FINAL_URL = "extra_final_url"
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        vb = ActivityAdWebviewBinding.inflate(layoutInflater)
        setContentView(vb.root)

        window.statusBarColor = Color.BLACK
        supportActionBar?.hide()

        val adUrl = intent.getStringExtra(EXTRA_AD_URL)
        val finalUrl = intent.getStringExtra(EXTRA_FINAL_URL)

        vb.webView.settings.javaScriptEnabled = true
        vb.webView.settings.domStorageEnabled = true
        vb.webView.webViewClient = WebViewClient()

        // Carrega o link do anúncio
        if (adUrl != null) vb.webView.loadUrl(adUrl)

        // Inicia contagem regressiva
        startCountdown(finalUrl)
    }

    private fun startCountdown(finalUrl: String?) {
        var timeLeft = 3
        vb.countdownText.visibility = View.VISIBLE

        countdown = object : CountDownTimer(3000, 1000) {
            override fun onTick(millisUntilFinished: Long) {
                vb.countdownText.text = "Você será direcionado para o canal em $timeLeft..."
                timeLeft--
            }

            override fun onFinish() {
                vb.countdownText.text = "Abrindo o canal..."
                if (!finalUrl.isNullOrBlank()) {
                    val i = Intent(this@AdWebViewActivity, WebViewActivity::class.java)
                    i.putExtra(WebViewActivity.EXTRA_URL, finalUrl)
                    startActivity(i)
                    finish()
                }
            }
        }.start()
    }

    override fun onDestroy() {
        countdown?.cancel()
        super.onDestroy()
    }
}