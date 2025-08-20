package com.futanium.box.ui

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.recyclerview.widget.RecyclerView
import coil.load
import com.futanium.box.R
import com.futanium.box.model.Game

class GameAdapter(
    private val items: MutableList<Game> = mutableListOf()
) : RecyclerView.Adapter<GameAdapter.VH>() {

    /** qual posição está expandida; -1 = nenhuma */
    private var expandedPos: Int = -1

    fun submit(newItems: List<Game>) {
        items.clear()
        items.addAll(newItems)
        expandedPos = -1
        notifyDataSetChanged()
    }

    inner class VH(v: View) : RecyclerView.ViewHolder(v) {
        // topo (campeonato)
        val imgChamp: ImageView = v.findViewById(R.id.imgChamp)
        val tvChamp: TextView   = v.findViewById(R.id.tvChamp)

        // linha principal
        val tvHome: TextView    = v.findViewById(R.id.tvHomeName)
        val ivHome: ImageView   = v.findViewById(R.id.imgHome)
        val tvTime: TextView    = v.findViewById(R.id.tvTime)
        val ivAway: ImageView   = v.findViewById(R.id.imgAway)
        val tvAway: TextView    = v.findViewById(R.id.tvAwayName)

        // expansão
        val divider: View             = v.findViewById(R.id.divider)
        val btnContainer: LinearLayout= v.findViewById(R.id.btnContainer)

        // clique no card inteiro
        val cardRoot: View            = v.findViewById(R.id.cardRoot)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_game, parent, false)
        return VH(view)
    }

    override fun getItemCount() = items.size

    override fun onBindViewHolder(h: VH, position: Int) {
        val g = items[position]

        // --- topo (campeonato) ---
        h.tvChamp.text = g.championship.orEmpty()
        h.imgChamp.load(g.championshipImageUrl) {
            crossfade(true)
        }

        // --- nomes + hora + escudos ---
        h.tvHome.text = g.homeName.orEmpty()
        h.tvAway.text = g.awayName.orEmpty()
        h.tvTime.text = g.time.orEmpty()

        h.ivHome.load(g.homeLogo) {
            crossfade(true)
            placeholder(android.R.drawable.stat_sys_download)
            error(android.R.drawable.ic_menu_report_image)
        }
        h.ivAway.load(g.awayLogo) {
            crossfade(true)
            placeholder(android.R.drawable.stat_sys_download)
            error(android.R.drawable.ic_menu_report_image)
        }

        // ------ botões (expand/collapse) ------
        val btns = g.buttons ?: emptyList()
        val hasButtons = btns.isNotEmpty()

        val isExpanded = (position == expandedPos) && hasButtons
        h.divider.visibility = if (isExpanded) View.VISIBLE else View.GONE
        h.btnContainer.visibility = if (isExpanded) View.VISIBLE else View.GONE

        if (isExpanded) {
            h.btnContainer.removeAllViews()
            btns.forEachIndexed { idx, anyBtn ->
                val (title, link) = extractTitleAndLink(anyBtn, idx)
                val b = Button(h.itemView.context).apply {
                    text = title
                    setOnClickListener { openLink(h.itemView, link) }
                }
                h.btnContainer.addView(b)
            }
        } else {
            h.btnContainer.removeAllViews()
        }

        // clique no card abre/fecha só se houver botões
        h.cardRoot.setOnClickListener {
            if (!hasButtons) return@setOnClickListener
            val old = expandedPos
            expandedPos = if (position == expandedPos) -1 else position

            if (old != -1) notifyItemChanged(old)
            notifyItemChanged(position)
        }
    }

    /** Extrai (título, link) de várias formas e suporta "Canal 1 go:espn1" -> ("Canal 1","go:espn1") */
    @Suppress("UNCHECKED_CAST")
    private fun extractTitleAndLink(anyBtn: Any, index: Int): Pair<String, String> {
        var rawName: String? = null
        var rawUrl: String? = null

        when (anyBtn) {
            is ButtonInfo -> {
                rawName = anyBtn.name
                rawUrl  = anyBtn.url
            }
            is Map<*, *> -> {
                rawName = anyBtn["name"]?.toString()
                rawUrl  = anyBtn["url"]?.toString()
            }
            is org.json.JSONObject -> {
                rawName = anyBtn.optString("name", null)
                rawUrl  = anyBtn.optString("url", null)
            }
            else -> rawName = anyBtn.toString()
        }

        var title = rawName?.trim().orEmpty()
        var link  = rawUrl?.trim().orEmpty()

        // pega go:xxx no final do nome
        val goMatch = Regex("""\s+(go:\S+)\s*$""").find(title)
        if (goMatch != null) {
            link = goMatch.groupValues[1]
            title = title.removeRange(goMatch.range).trim()
        }

        if (title.isBlank()) title = "Canal ${index + 1}"
        if (link.isBlank())  link  = rawUrl?.takeIf { it.isNotBlank() } ?: "#"

        return title to link
    }

    private fun openLink(view: View, link: String) {
        try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(link))
            view.context.startActivity(intent)
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(view.context, "Não há app para abrir: $link", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(view.context, "Erro ao abrir link: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }
}

/** opcional (se quiser tipar os botões da API) */
data class ButtonInfo(
    val name: String?,
    val url: String?
)