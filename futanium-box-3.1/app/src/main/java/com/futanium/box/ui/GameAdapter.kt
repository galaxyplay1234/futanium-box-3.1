package com.futanium.box.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import coil.load
import com.futanium.box.R
import com.futanium.box.model.Game
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView

class GameAdapter(
    private val items: MutableList<Game> = mutableListOf()
) : RecyclerView.Adapter<GameAdapter.VH>() {

    private var expandedPos = RecyclerView.NO_POSITION

    fun submit(newItems: List<Game>) {
        items.clear()
        items.addAll(newItems)
        expandedPos = RecyclerView.NO_POSITION
        notifyDataSetChanged()
    }

    inner class VH(v: View) : RecyclerView.ViewHolder(v) {
        val cardRoot: MaterialCardView = v.findViewById(R.id.cardRoot)
        val tvChamp: TextView          = v.findViewById(R.id.tvChamp)

        val ivHome: ImageView          = v.findViewById(R.id.ivHome)
        val tvHome: TextView           = v.findViewById(R.id.tvHome)

        val tvTime: TextView           = v.findViewById(R.id.tvTime)

        val ivAway: ImageView          = v.findViewById(R.id.ivAway)
        val tvAway: TextView           = v.findViewById(R.id.tvAway)

        val divider: View              = v.findViewById(R.id.divider)
        val groupButtons: LinearLayout = v.findViewById(R.id.groupButtons)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_game, parent, false)
        return VH(view)
    }

    override fun getItemCount() = items.size

    override fun onBindViewHolder(h: VH, position: Int) {
        val g = items[position]

        // Topo: campeonato
        h.tvChamp.text = g.championship.orEmpty()

        // Lado esquerdo (mandante)
        h.tvHome.text = g.homeName.orEmpty()
        h.ivHome.load(g.homeLogo) {
            crossfade(true)
            placeholder(android.R.drawable.stat_sys_download)
            error(android.R.drawable.ic_menu_report_image)
        }

        // Centro: hora (em negrito no layout)
        h.tvTime.text = g.time.orEmpty()

        // Lado direito (visitante)
        h.tvAway.text = g.awayName.orEmpty()
        h.ivAway.load(g.awayLogo) {
            crossfade(true)
            placeholder(android.R.drawable.stat_sys_download)
            error(android.R.drawable.ic_menu_report_image)
        }

        // --- Expand/Collapse somente se houver botões ---
        val hasButtons = !g.buttons.isNullOrEmpty()
        val isExpanded = (position == expandedPos) && hasButtons

        h.divider.visibility = if (isExpanded) View.VISIBLE else View.GONE
        h.groupButtons.visibility = if (isExpanded) View.VISIBLE else View.GONE
        h.groupButtons.removeAllViews()

        if (isExpanded) {
            val ctx = h.itemView.context
            val pad = (8 * ctx.resources.displayMetrics.density).toInt()
            g.buttons!!.forEachIndexed { i, label ->
                val btn = MaterialButton(ctx, null,
                    com.google.android.material.R.attr.materialButtonOutlinedStyle
                ).apply {
                    text = label
                    isAllCaps = false
                }
                val lp = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
                if (i > 0) lp.marginStart = pad
                h.groupButtons.addView(btn, lp)

                // TODO: clique do botão -> abrir link/player quando você definir
                // btn.setOnClickListener { ... }
            }
        }

        // Clique no card: abre/fecha só se tiver botões
        h.cardRoot.setOnClickListener {
            if (!hasButtons) return@setOnClickListener
            val prev = expandedPos
            expandedPos = if (position == expandedPos) RecyclerView.NO_POSITION else position
            if (prev != RecyclerView.NO_POSITION) notifyItemChanged(prev)
            notifyItemChanged(position)
        }
    }
}