package com.futanium.box

import android.animation.ObjectAnimator
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.util.TypedValue
import android.view.Menu
import android.view.MenuItem
import android.view.animation.LinearInterpolator
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
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

    // guardamos o animator atual pra não reiniciar a cada clique
    private var refreshAnimator: ObjectAnimator? = null
    private var refreshItem: MenuItem? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        vb = ActivityMainBinding.inflate(layoutInflater)
        setContentView(vb.root)

        setSupportActionBar(vb.toolbar)

        // Status bar #10131C
        window.statusBarColor = Color.parseColor("#10131C")

        // Sombra leve na toolbar
        vb.toolbar.elevation = 6f

        // Deixar o ícone de atualizar mais “pra dentro” (longe da borda)
        // Aumente/diminua esse valor se quiser mais/menos margem.
        vb.toolbar.contentInsetEndWithActions = dp(28)

        // Título menor e em negrito, alinhado à esquerda
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

        // Decide ExoPlayer x WebView automaticamente
        adapter.onOpenLink = { url, title, referer, ua ->
            LinkHelper.openLinkSmart(this, url, title, referer, ua)
        }

        // Pull-to-refresh
        vb.swipe.setOnRefreshListener {
            if (!vb.swipe.isRefreshing) vb.swipe.isRefreshing = true
            fetchGames()
        }

        fetchGames()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.main_menu, menu)
        refreshItem = menu.findItem(R.id.action_refresh)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_refresh -> {
                // evita recriar anim se já está girando
                if (refreshAnimator?.isRunning != true) startRefreshSpin(item)
                // inicia o carregamento
                if (!vb.swipe.isRefreshing) vb.swipe.isRefreshing = true
                fetchGames(onFinally = { stopRefreshSpin() })
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    private fun fetchGames(onFinally: (() -> Unit)? = null) {
        // garante que a bolinha não “trave” mesmo se algo der errado
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

    // ------------------ Refresh icon animation ------------------

    private fun startRefreshSpin(item: MenuItem) {
        // usa ActionView só enquanto gira (mantém ripple padrão quando parar)
        val iv = ImageView(this).apply {
            setImageDrawable(item.icon)
            // melhora suavidade do giro
            setLayerType(ImageView.LAYER_TYPE_HARDWARE, null)
        }
        MenuItemCompat.setActionView(item, iv)

        refreshAnimator = ObjectAnimator.ofFloat(iv, "rotation", 0f, 360f).apply {
            duration = 700
            interpolator = LinearInterpolator()
            repeatCount = ObjectAnimator.INFINITE
            start()
        }
    }

    private fun stopRefreshSpin() {
        val item = refreshItem ?: return
        refreshAnimator?.cancel()
        refreshAnimator = null
        // volta ao menu normal para recuperar o ripple de clique padrão
        MenuItemCompat.setActionView(item, null)
    }

    // ------------------------------------------------------------

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
}