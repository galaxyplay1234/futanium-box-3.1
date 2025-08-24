package com.futanium.box

import android.animation.ObjectAnimator
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.util.TypedValue
import android.view.*
import android.view.animation.LinearInterpolator
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.AppCompatImageButton
import androidx.core.view.MenuItemCompat
import androidx.core.widget.ImageViewCompat
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
    private val games = ArrayList<Game>()

    private val API_URL = "http://91.108.124.236:8080/games/api"

    // refs do botão/anim
    private var refreshBtn: AppCompatImageButton? = null
    private var spinAnim: ObjectAnimator? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        vb = ActivityMainBinding.inflate(layoutInflater)
        setContentView(vb.root)

        setSupportActionBar(vb.toolbar)

        // Status bar #10131C
        window.statusBarColor = Color.parseColor("#10131C")
        vb.toolbar.elevation = 6f

        // Título menor e negrito
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

        vb.swipe.setOnRefreshListener { fetchGames() }
        fetchGames()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.main_menu, menu)

        val item = menu.findItem(R.id.action_refresh)

        // Cria actionView com tamanho padrão de Toolbar (48dp), padding p/ ripple aparecer,
        // e margenzinha à direita pra não “colar” na borda.
        val btn = AppCompatImageButton(this).apply {
            // ripple borderless
            val tv = TypedValue()
            theme.resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, tv, true)
            setBackgroundResource(tv.resourceId)

            // Ícone e tint do item
            setImageDrawable(item.icon)
            ImageViewCompat.setImageTintList(this, item.iconTintList)

            // Tamanho padrão toolbar (48dp x MATCH_PARENT) + margemEnd
            layoutParams = androidx.appcompat.widget.Toolbar.LayoutParams(
                dp(48), ViewGroup.LayoutParams.MATCH_PARENT, Gravity.END
            ).apply {
                marginEnd = dp(8)
            }

            // padding interno para evidenciar ripple
            setPadding(dp(12), 0, dp(12), 0)
            scaleType = android.widget.ImageView.ScaleType.CENTER
            contentDescription = item.title

            setOnClickListener {
                startSpin()
                fetchGames(onFinally = { stopSpin() })
            }
        }

        MenuItemCompat.setActionView(item, btn)
        refreshBtn = btn
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_refresh -> {
                // fallback (ex.: teclado/TV)
                refreshBtn?.performClick()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    private fun startSpin() {
        val v = refreshBtn ?: return
        spinAnim?.cancel()
        // rotação contínua, sem “voltar”
        spinAnim = ObjectAnimator.ofFloat(v, View.ROTATION, 0f, 360f).apply {
            duration = 700
            interpolator = LinearInterpolator()
            repeatCount = ObjectAnimator.INFINITE
            repeatMode = ObjectAnimator.RESTART
        }
        spinAnim?.start()
    }

    private fun stopSpin() {
        spinAnim?.cancel()
        refreshBtn?.rotation = 0f
    }

    private fun fetchGames(onFinally: (() -> Unit)? = null) {
        vb.swipe.isRefreshing = true
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
                runOnUiThread {
                    vb.swipe.isRefreshing = false
                    onFinally?.invoke()
                }
            }
        }.start()
    }

    /** Converte o JSON da API -> lista de Game. Ignora itens com "header". */
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

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
}