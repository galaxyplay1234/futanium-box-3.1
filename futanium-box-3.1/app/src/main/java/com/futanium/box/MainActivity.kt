package com.futanium.box

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.view.animation.LinearInterpolator
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.AppCompatImageButton
import androidx.core.widget.ImageViewCompat
import androidx.recyclerview.widget.LinearLayoutManager
import com.futanium.box.databinding.ActivityMainBinding
import com.futanium.box.model.Game
import com.futanium.box.ui.GameAdapter
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import kotlin.math.max

class MainActivity : AppCompatActivity() {

    private lateinit var vb: ActivityMainBinding
    private val client = OkHttpClient()
    private val adapter = GameAdapter()

    private val API_URL = "http://91.108.124.236:8080/games/api"

    // refresh button + animação
    private var refreshBtn: AppCompatImageButton? = null
    private var spinAnim: ObjectAnimator? = null
    private var spinStartedAt: Long = 0L
    private val MIN_SPIN_MS = 900L   // garante pelo menos 1 volta

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        vb = ActivityMainBinding.inflate(layoutInflater)
        setContentView(vb.root)

        setSupportActionBar(vb.toolbar)

        // Status bar #10131C
        window.statusBarColor = Color.parseColor("#10131C")

        // Sombra leve na toolbar
        vb.toolbar.elevation = 6f

        // Afastar os actions da borda (bem visível)
        vb.toolbar.contentInsetEndWithActions = dp(32)
        // Também aumenta os insets base
        vb.toolbar.setContentInsetsRelative(dp(16), dp(32))

        // Título menor e bold
        vb.toolbar.post {
            for (i in 0 until vb.toolbar.childCount) {
                val child = vb.toolbar.getChildAt(i)
                if (child is TextView) {
                    child.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
                    child.typeface = Typeface.DEFAULT_BOLD
                    break
                }
            }
        }

        vb.rvGames.layoutManager = LinearLayoutManager(this)
        vb.rvGames.adapter = adapter

        adapter.onOpenLink = { url, title, referer, ua ->
            LinkHelper.openLinkSmart(this, url, title, referer, ua)
        }

        vb.swipe.setOnRefreshListener {
            startSpin()
            fetchGames(onFinally = { stopSpin() })
        }

        // 1º carregamento
        startSpin()
        fetchGames(onFinally = { stopSpin() })
    }

    // ---------------- Toolbar Menu ----------------

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.main_menu, menu)
        menu.findItem(R.id.action_refresh)?.let { setupRefreshButton(it) }
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_refresh -> {
                startSpin()
                fetchGames(onFinally = { stopSpin() })
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    /** ActionView com margem grande, ripple CLARO e mais contido. */
    private fun setupRefreshButton(item: MenuItem) {
        val btn = AppCompatImageButton(this).apply {
            // Ripple BOUNDED (menor) e claro
            val tv = TypedValue()
            theme.resolveAttribute(android.R.attr.selectableItemBackground, tv, true)
            background = getDrawable(tv.resourceId)?.mutate()
            // deixa o efeito de clique bem claro
            foregroundTintList = ColorStateList.valueOf(Color.parseColor("#99FFFFFF"))

            // Ícone + tint branco
            setImageDrawable(item.icon)
            ImageViewCompat.setImageTintList(this, ColorStateList.valueOf(Color.WHITE))

            // Tamanho & margens (afasta bem da borda)
            val size = dp(40) // botão um pouco menor para ripple ficar mais “curto”
            layoutParams = androidx.appcompat.widget.Toolbar.LayoutParams(
                size,
                ViewGroup.LayoutParams.MATCH_PARENT,
                Gravity.END
            ).apply {
                marginEnd = dp(16)  // margem adicional
            }
            // padding reduzido = ripple visualmente menor
            setPadding(dp(4), 0, dp(4), 0)
            scaleType = android.widget.ImageView.ScaleType.CENTER
            contentDescription = item.title

            setOnClickListener {
                startSpin()
                fetchGames(onFinally = { stopSpin() })
            }
        }

        item.setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
        item.actionView = btn
        refreshBtn = btn
    }

    /** Inicia rotação contínua (sempre no mesmo sentido). */
    private fun startSpin() {
        val v = refreshBtn ?: return
        // se já está girando, não recria
        if (spinAnim?.isRunning == true) return

        v.post {
            v.pivotX = v.width / 2f
            v.pivotY = max(1, v.height / 2) * 1f
            val startAngle = v.rotation % 360f
            spinAnim = ObjectAnimator.ofFloat(v, View.ROTATION, startAngle, startAngle + 360f).apply {
                duration = 700
                interpolator = LinearInterpolator()
                repeatCount = ValueAnimator.INFINITE
                repeatMode = ValueAnimator.RESTART
            }
            spinStartedAt = System.currentTimeMillis()
            spinAnim?.start()
        }
    }

    /** Para rotação, mas respeita um tempo mínimo para completar 1 volta. */
    private fun stopSpin() {
        val needDelay = (spinStartedAt + MIN_SPIN_MS) - System.currentTimeMillis()
        if (needDelay > 0) {
            refreshBtn?.postDelayed({ reallyStopSpin() }, needDelay)
        } else {
            reallyStopSpin()
        }
    }

    private fun reallyStopSpin() {
        spinAnim?.cancel()
        spinAnim = null
        refreshBtn?.rotation = 0f
    }

    // ---------------- Dados ----------------

    private fun fetchGames(onFinally: (() -> Unit)? = null) {
        Thread {
            try {
                val req = Request.Builder().url(API_URL).build()
                val res = client.newCall(req).execute()
                val body = res.body?.string() ?: "[]"

                val games = parseGames(body)
                runOnUiThread {
                    adapter.submit(games)
                    if (games.isEmpty()) {
                        Toast.makeText(this, "Nenhum jogo encontrado.", Toast.LENGTH_SHORT).show()
                    }
                }
            } catch (e: Exception) {
                runOnUiThread {
                    Toast.makeText(this, "Erro ao carregar: ${e.message}", Toast.LENGTH_LONG).show()
                }
            } finally {
                runOnUiThread { onFinally?.invoke() }
            }
        }.start()
    }

    private fun parseGames(json: String): List<Game> {
        val arr = JSONArray(json)
        val list = ArrayList<Game>(arr.length())

        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            if (o.has("header")) continue

            val championship         = o.optString("championship", "")
            val championshipImageUrl = o.optString("championship_image_url", null)
            val startTime            = o.optString("start_time", "")
            val homeTeam             = o.optString("home_team", "")
            val visitingTeam         = o.optString("visiting_team", "")
            val homeLogo             = o.optString("home_team_image_url", null)
            val visitingLogo         = o.optString("visiting_team_image_url", null)
            val isLive               = o.optBoolean("is_live", false)
            val isFinished           = o.optBoolean("is_finished", false)

            val buttonsList: List<Any>? = o.optJSONArray("buttons")?.let { ja ->
                val tmp = ArrayList<Any>(ja.length())
                for (j in 0 until ja.length()) tmp += ja.get(j)
                tmp
            }

            list += Game(
                championship = championship,
                championshipImageUrl = championshipImageUrl,
                homeName = homeTeam,
                homeLogo = homeLogo,
                awayName = visitingTeam,
                awayLogo = visitingLogo,
                time = startTime,
                isLive = isLive,
                isFinished = isFinished,
                buttons = buttonsList
            )
        }
        return list
    }

    // ---------------- Utils ----------------
    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
}