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

    // TODO: troque pela sua URL real
    private val API_URL = "http://91.108.124.236:8080/games/api"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        vb = ActivityMainBinding.inflate(layoutInflater)
        setContentView(vb.root)

        // toolbar já configurada no layout (título à esquerda)
        setSupportActionBar(vb.toolbar)

        vb.rvGames.layoutManager = LinearLayoutManager(this)
        vb.rvGames.adapter = adapter

        vb.swipe.setOnRefreshListener { fetchGames() }

        fetchGames()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.main_menu, menu) // já criamos antes
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

    // Exemplo de JSON aceito:
    // [
    //   {"championship":"Brasileirão","time":"21:30",
    //    "homeName":"Flamengo","homeLogo":"https://.../fla.png",
    //    "awayName":"Palmeiras","awayLogo":"https://.../pal.png"}
    // ]
    private fun parseGames(json: String): List<Game> {
        val arr = JSONArray(json)
        val list = ArrayList<Game>(arr.length())
        for (i in 0 until arr.length()) {
            val o: JSONObject = arr.getJSONObject(i)
            list += Game(
                championship = o.optString("championship"),
                time = o.optString("time"),
                homeName = o.optString("homeName"),
                homeLogo = o.optString("homeLogo", null),
                awayName = o.optString("awayName"),
                awayLogo = o.optString("awayLogo", null),
            )
        }
        return list
    }

    // --- animação do ícone "atualizar" (rotaciona enquanto carrega) ---
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
        (v?.animation)?.cancel()
        MenuItemCompat.setActionView(item, null)
    }
}