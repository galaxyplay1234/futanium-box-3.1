package com.futanium.box

import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.animation.LinearInterpolator
import androidx.appcompat.app.AppCompatActivity
import com.futanium.box.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    private lateinit var vb: ActivityMainBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        vb = ActivityMainBinding.inflate(layoutInflater)
        setContentView(vb.root)

        // Toolbar (título à esquerda)
        setSupportActionBar(vb.toolbar)
        supportActionBar?.title = "Futanium Box 3.1"
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.main_menu, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_refresh -> {
                // animação de giro do ícone
                val view = vb.toolbar.findViewById<android.view.View>(R.id.action_refresh)
                // fallback caso o findViewById não ache a view do ícone
                val target = view ?: vb.toolbar
                target.animate()
                    .rotationBy(360f)
                    .setDuration(600)
                    .setInterpolator(LinearInterpolator())
                    .withEndAction { target.rotation = 0f }
                    .start()

                // TODO: colocar sua lógica de atualização aqui
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }
}
