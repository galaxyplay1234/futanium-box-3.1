package com.futanium.box.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import coil.load
import com.futanium.box.R
import com.futanium.box.model.Game

class GameAdapter(
    private val items: MutableList<Game> = mutableListOf()
) : RecyclerView.Adapter<GameAdapter.VH>() {

    fun submit(newItems: List<Game>) {
        items.clear()
        items.addAll(newItems)
        notifyDataSetChanged()
    }

    inner class VH(v: View) : RecyclerView.ViewHolder(v) {
        val txtChampionship: TextView = v.findViewById(R.id.txtChampionship)
        val imgHome: ImageView      = v.findViewById(R.id.imgHomeTeam)
        val txtHome: TextView       = v.findViewById(R.id.txtHomeTeam)
        val txtVs: TextView         = v.findViewById(R.id.txtVs)
        val txtAway: TextView       = v.findViewById(R.id.txtVisitingTeam)
        val imgAway: ImageView      = v.findViewById(R.id.imgVisitingTeam)
        val txtTime: TextView       = v.findViewById(R.id.txtTime)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_game, parent, false)
        return VH(view)
    }

    override fun getItemCount() = items.size

    override fun onBindViewHolder(h: VH, position: Int) {
        val g = items[position]
        h.txtChampionship.text = g.championship
        h.txtHome.text = g.homeName
        h.txtAway.text = g.awayName
        h.txtTime.text = g.time
        h.txtVs.text = "vs"

        // carrega imagens (se tiver URL)
        h.imgHome.load(g.homeLogo) {
            crossfade(true)
            placeholder(android.R.drawable.stat_sys_download)
            error(android.R.drawable.ic_menu_report_image)
        }
        h.imgAway.load(g.awayLogo) {
            crossfade(true)
            placeholder(android.R.drawable.stat_sys_download)
            error(android.R.drawable.ic_menu_report_image)
        }
    }
}
