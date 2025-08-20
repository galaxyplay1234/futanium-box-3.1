package com.futanium.box

import android.animation.ObjectAnimator
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.widget.ImageView
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
import org.json.JSONObject

class MainActivity : AppCompatActivity() {

    private lateinit var vb: ActivityMainBinding
    private val client = OkHttpClient()
    private val adapter = GameAdapter()
    private val games = ArrayList<Game>()

    private val API_URL = "http://91.108.124.236:8080/games/api"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        vb = ActivityMainBinding.inflate(layoutInflater)
        setContentView(vb.root)

        setSupportActionBar(vb.toolbar)

        vb.rvGames.layoutManager = LinearLayoutManager(this)
        vb.rvGames.adapter = adapter

        vb.swipe.setOnRefreshListener { fetchGames() }

        fetchGames()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.main_menu, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_refresh -> {
                spinMenuItem(item)
                fetchGames(onFinally = { stopSpin(item) })
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
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

    /** Converte o JSON da sua API -> lista de Game.
     *  Ignora entradas que sejam 'header'. NÃO usa 'background'. */
    private fun parseGames(json: String): List<Game> {
        val arr = JSONArray(json)
        val list = ArrayList<Game>(arr.length())
        for (i in 0 until arr.length()) {
            val o: JSONObject = arr.getJSONObject(i)

            // pular itens de header
            if (o.has("header")) continue

            val championship        = o.optString("championship", "")
            val startTime           = o.optString("start_time", "")
            val endTime             = o.optString("end_time", "")
            val homeTeam            = o.optString("home_team", "")
            val visitingTeam        = o.optString("visiting_team", "")
            val homeLogo            = o.optString("home_team_image_url", null)
            val visitingLogo        = o.optString("visiting_team_image_url", null)
            val isLive              = o.optBoolean("is_live", false)
            val isFinished          = o.optBoolean("is_finished", false)

            // Se quiser mostrar "16h00" ou "16h00–18h10"
            val timeText = if (endTime.isNotBlank()) "$startTime – $endTime" else startTime

            list += Game(
                championship = championship,
                time = timeText,
                homeName = homeTeam,
                homeLogo = homeLogo,
                awayName = visitingTeam,
                awayLogo = visitingLogo,
                isLive = isLive,
                isFinished = isFinished
            )
        }
        return list
    }

    private fun spinMenuItem(item: MenuItem) {
        val iv = ImageView(this).apply { setImageDrawable(item.icon) }
        MenuItemCompat.setActionView(item, iv)
        ObjectAnimator.ofFloat(iv, "rotation", 0f, 360f).apply {
            duration = 700
            repeatCount = ObjectAnimator.INFINITE
        }.start()
    }

    private fun stopSpin(item: MenuItem) {
        val v = MenuItemCompat.getActionView(item)
        v?.animate()?.cancel()
        MenuItemCompat.setActionView(item, null)
    }
}