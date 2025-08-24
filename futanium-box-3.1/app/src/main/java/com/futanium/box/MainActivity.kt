package com.futanium.box

import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.InsetDrawable
import android.graphics.drawable.RippleDrawable
import android.graphics.drawable.ShapeDrawable
import android.graphics.drawable.shapes.OvalShape
import android.os.Bundle
import android.util.TypedValue
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.view.animation.LinearInterpolator
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.AppCompatImageView
import androidx.core.view.MenuItemCompat
import androidx.recyclerview.widget.LinearLayoutManager
import com.futanium.box.databinding.ActivityMainBinding
import com.futanium.box.model.Game
import com.futanium.box.ui.GameAdapter
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray

class MainActivity : AppCompatActivity() {

    private lateinit var vb: ActivityMainBinding
    private val client = OkHttpClient()
    private val adapter = GameAdapter()

    private val API_URL = "http://91.108.124.236:8080/games/api"

    // referências do botão de refresh
    private var refreshItem: MenuItem? = null
    private var refreshView: AppCompatImageView? = null

    // controle do giro
    private val SPIN_TAG_KEY = 0x13572468
    private var spinCompletedOne = false
    private var spinPendingStop = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        vb = ActivityMainBinding.inflate(layoutInflater)
        setContentView(vb.root)

        setSupportActionBar(vb.toolbar)

        // Status bar #10131C
        window.statusBarColor = Color.parseColor("#10131C")

        // Sombra leve na toolbar
        vb.toolbar.elevation = 6f

        // Ícone da direita afastado da borda
        vb.toolbar.contentInsetEndWithActions = dp(44)

        // Título menor e em negrito
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
            if (!vb.swipe.isRefreshing) vb.swipe.isRefreshing = true
            startRefreshSpin() // garante giro imediato
            fetchGames(onFinally = { stopRefreshSpin() })
        }

        fetchGames()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.main_menu, menu)
        refreshItem = menu.findItem(R.id.action_refresh)

        // ActionView fixa com ripple custom (menor e claro) e sem "pulo"
        val size = obtainActionBarSize()
        val iv = AppCompatImageView(this).apply {
            layoutParams = ViewGroup.LayoutParams(size, size)
            setImageDrawable(refreshItem!!.icon)
            scaleType = ImageView.ScaleType.CENTER

            // Ripple menor/claro
            val rippleColor = ColorStateList.valueOf(Color.parseColor("#33FFFFFF"))
            val inset = dp(5)
            val mask = InsetDrawable(ShapeDrawable(OvalShape()), inset, inset, inset, inset)
            background = RippleDrawable(rippleColor, null, mask)
            setPadding(dp(8), dp(8), dp(8), dp(8))

            contentDescription = refreshItem!!.title
            isClickable = true
            isFocusable = true

            setOnClickListener { onOptionsItemSelected(refreshItem!!) }
        }
        MenuItemCompat.setActionView(refreshItem, iv)
        refreshView = iv

        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_refresh -> {
                startRefreshSpin()
                if (!vb.swipe.isRefreshing) vb.swipe.isRefreshing = true
                fetchGames(onFinally = { stopRefreshSpin() })
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    private fun fetchGames(onFinally: (() -> Unit)? = null) {
        if (!vb.swipe.isRefreshing) vb.swipe.isRefreshing = true

        Thread {
            try {
                val req = Request.Builder().url(API_URL).build()
                val res = client.newCall(req).execute()
                val body = res.body?.string() ?: "[]"

                val games = parseGames(body)
                runOnUiThread {
                    (vb.rvGames.adapter as GameAdapter).submit(games)
                    if (games.isEmpty()) {
                        Toast.makeText(this, "Nenhum jogo encontrado.", Toast.LENGTH_SHORT).show()
                    }
                }
            } catch (e: Exception) {
                runOnUiThread {
                    Toast.makeText(this, "Erro ao carregar: ${e.message}", Toast.LENGTH_LONG).show()
                }
            } finally {
                runOnUiThread {
                    vb.swipe.isRefreshing = false
                    onFinally?.invoke()
                }
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

    // -------- animação suave do refresh (mín. 1 volta, sem “voltar”) --------

    private fun startRefreshSpin() {
        val v = refreshView ?: return
        if (v.getTag(SPIN_TAG_KEY) == true) return // já está girando

        v.animate().cancel()
        v.clearAnimation()

        spinCompletedOne = false
        spinPendingStop = false

        v.setTag(SPIN_TAG_KEY, true)
        v.setLayerType(View.LAYER_TYPE_HARDWARE, null)

        v.post {
            v.pivotX = v.width / 2f
            v.pivotY = v.height / 2f
            v.rotation = (v.rotation % 360f + 360f) % 360f

            fun loop() {
                v.animate()
                    .rotationBy(360f)
                    .setDuration(1100) // 1.1s: liso, dá tempo de “ver” a volta
                    .setInterpolator(LinearInterpolator())
                    .withEndAction {
                        spinCompletedOne = true
                        if (v.getTag(SPIN_TAG_KEY) == true) {
                            if (spinPendingStop) {
                                finalizeStop(v) // parar só pra frente
                            } else {
                                loop()
                            }
                        }
                    }
                    .start()
            }
            loop()
        }
    }

    private fun stopRefreshSpin() {
        val v = refreshView ?: return
        if (v.getTag(SPIN_TAG_KEY) != true) return

        if (!spinCompletedOne) {
            // ainda não completou 1 volta → pede pra parar quando completar
            spinPendingStop = true
            return
        }
        finalizeStop(v)
    }

    private fun finalizeStop(v: View) {
        // encerra a volta atual SEM girar para trás
        v.setTag(SPIN_TAG_KEY, false)
        v.animate().cancel()

        val current = (v.rotation % 360f + 360f) % 360f
        val remaining = if (current == 0f) 0f else 360f - current
        if (remaining > 0f) {
            val dur = (remaining / 360f * 250).toLong().coerceAtLeast(100L) // 100–250ms
            v.animate()
                .rotationBy(remaining)      // completa para frente
                .setDuration(dur)
                .setInterpolator(LinearInterpolator())
                .withEndAction {
                    v.rotation = 0f          // normaliza, sem “pulo”
                    v.setLayerType(View.LAYER_TYPE_NONE, null)
                }
                .start()
        } else {
            v.rotation = 0f
            v.setLayerType(View.LAYER_TYPE_NONE, null)
        }
    }

    // -------------------------------------------

    private fun obtainActionBarSize(): Int {
        val tv = TypedValue()
        var size = dp(48) // fallback
        if (theme.resolveAttribute(android.R.attr.actionBarSize, tv, true)) {
            size = TypedValue.complexToDimensionPixelSize(tv.data, resources.displayMetrics)
        }
        return size
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
}