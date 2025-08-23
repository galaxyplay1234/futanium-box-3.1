package com.futanium.box.ui

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
import org.json.JSONObject

class GameAdapter(
    private val items: MutableList<Game> = mutableListOf()
) : RecyclerView.Adapter<GameAdapter.VH>() {

    /** Callback para abrir links (Activity decide se vai WebView ou ExoPlayer) */
    var onOpenLink: ((url: String, title: String?, referer: String?, ua: String?) -> Unit)? = null

    /** posição atualmente expandida; -1 = nenhuma */
    private var expandedPos: Int = -1

    fun submit(newItems: List<Game>) {
        items.clear()
        items.addAll(newItems)
        expandedPos = -1
        notifyDataSetChanged()
    }

    inner class VH(v: View) : RecyclerView.ViewHolder(v) {
        val imgChamp: ImageView = v.findViewById(R.id.imgChamp)
        val tvChamp: TextView   = v.findViewById(R.id.tvChamp)

        val tvHome: TextView  = v.findViewById(R.id.tvHomeName)
        val ivHome: ImageView = v.findViewById(R.id.imgHome)
        val tvTime: TextView  = v.findViewById(R.id.tvTime)
        val ivAway: ImageView = v.findViewById(R.id.imgAway)
        val tvAway: TextView  = v.findViewById(R.id.tvAwayName)

        val btnContainer: LinearLayout = v.findViewById(R.id.btnContainer)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_game, parent, false)
        return VH(view)
    }

    override fun getItemCount() = items.size

    override fun onBindViewHolder(h: VH, position: Int) {
        val g = items[position]

        // Campeonato (esconde se vier vazio)
        val champName = g.championship.orEmpty()
        h.tvChamp.text = champName
        h.tvChamp.visibility = if (champName.isBlank()) View.GONE else View.VISIBLE

        val champLogo = g.championshipImageUrl
        if (champLogo.isNullOrBlank()) {
            h.imgChamp.visibility = View.GONE
        } else {
            h.imgChamp.visibility = View.VISIBLE
            h.imgChamp.load(champLogo) { crossfade(true) }
        }

        // Times / hora
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

        // ----- BOTÕES -----
        val btns: List<Any> = (g.buttons as? List<*>)?.filterNotNull() ?: emptyList()
        val hasButtons = btns.isNotEmpty()
        val isExpanded = (position == expandedPos) && hasButtons

        h.btnContainer.visibility = if (isExpanded) View.VISIBLE else View.GONE
        h.btnContainer.removeAllViews()
        if (isExpanded) {
            btns.forEachIndexed { idx, anyBtn ->
                val (title, link) = extractTitleAndLink(anyBtn, idx)
                val b = Button(h.itemView.context).apply {
                    text = title
                    setOnClickListener { openLink(h.itemView, title, link) }
                }
                h.btnContainer.addView(b)
            }
        }

        // Expansão por clique (só se houver botões)
        h.itemView.setOnClickListener {
            if (!hasButtons) return@setOnClickListener
            val old = expandedPos
            expandedPos = if (position == expandedPos) -1 else position
            if (old != -1) notifyItemChanged(old)
            notifyItemChanged(position)
        }
    }

    /** Extrai (título, link) de um item de botão vindo da API */
    private fun extractTitleAndLink(anyBtn: Any, index: Int): Pair<String, String> {
        var rawName: String? = null
        var rawUrl: String? = null

        when (anyBtn) {
            is ButtonInfo -> { rawName = anyBtn.name; rawUrl = anyBtn.url }
            is Map<*, *> -> { rawName = anyBtn["name"]?.toString(); rawUrl = anyBtn["url"]?.toString() }
            is JSONObject -> { rawName = anyBtn.optString("name", null); rawUrl = anyBtn.optString("url", null) }
            else -> rawName = anyBtn.toString()
        }

        var title = rawName?.trim().orEmpty()
        var link = rawUrl?.trim().orEmpty()

        // Se vier "Canal 1 go:xxx" -> separa
        Regex("""\s+(go:\S+)\s*$""").find(title)?.let { m ->
            link = m.groupValues[1]
            title = title.removeRange(m.range).trim()
        }

        if (title.isBlank()) title = "Canal ${index + 1}"
        if (link.isBlank())  link  = rawUrl?.takeIf { it.isNotBlank() } ?: "#"
        return title to link
    }

    private fun openLink(view: View, title: String?, link: String) {
        val ctx = view.context
        val u = link.trim()
        try {
            // Se o host chamar o callback, ele decide entre WebView/ExoPlayer
            onOpenLink?.invoke(u, title, null, null)
                ?: run {
                    // Fallback (sem callback): comportamento antigo
                    if (u.startsWith("http", ignoreCase = true)) {
                        val it = Intent(ctx, com.futanium.box.WebViewActivity::class.java)
                        it.putExtra(com.futanium.box.WebViewActivity.EXTRA_URL, u)
                        ctx.startActivity(it)  // <-- use o contexto
                    } else {
                        val it = Intent(Intent.ACTION_VIEW, Uri.parse(u))
                        ctx.startActivity(it)  // <-- use o contexto
                    }
                }
        } catch (e: Exception) {
            Toast.makeText(ctx, "Não foi possível abrir o link.", Toast.LENGTH_SHORT).show()
        }
    }
}

/** Opcional: tipo forte para botões */
data class ButtonInfo(
    val name: String?,
    val url: String?
)