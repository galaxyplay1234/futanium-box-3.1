package com.futanium.box

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.futanium.box.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    private lateinit var vb: ActivityMainBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        vb = ActivityMainBinding.inflate(layoutInflater)
        setContentView(vb.root)

        // Usa a Toolbar como ActionBar e define o título à esquerda
        setSupportActionBar(vb.toolbar)
        supportActionBar?.title = "Futanium Box 3.1"

        // Conteúdo da tela
        vb.subtitle.text = "Hello 👋 Projeto pronto para build!"
    }
}