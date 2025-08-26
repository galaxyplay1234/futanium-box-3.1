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

        val gameStatus: TextView = v.findViewById(R.id.gameStatus) // ⬅️ texto abaixo da hora
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

        // ----- STATUS (texto abaixo da hora; sem badge) -----
        // cancela qualquer "piscar" antigo preso na célula reciclada
        (h.gameStatus.getTag(R.id.tag_blink_anim) as? android.animation.ObjectAnimator)?.let {
            it.cancel()
            h.gameStatus.setTag(R.id.tag_blink_anim, null)
        }
        h.gameStatus.animate().cancel()
        h.gameStatus.alpha = 1f
        h.gameStatus.visibility = View.GONE

        when {
            g.isLive == true -> {
                h.gameStatus.text = "ao vivo"
                h.gameStatus.setTextColor(android.graphics.Color.parseColor("#FF3B30")) // vermelho
                h.gameStatus.visibility = View.VISIBLE

                val anim = android.animation.ObjectAnimator
                    .ofFloat(h.gameStatus, View.ALPHA, 1f, 0.3f)
                    .apply {
                        duration = 500
                        repeatMode = android.animation.ValueAnimator.REVERSE
                        repeatCount = android.animation.ValueAnimator.INFINITE
                        interpolator = android.view.animation.LinearInterpolator()
                    }
                h.gameStatus.setTag(R.id.tag_blink_anim, anim)
                anim.start()
            }
            g.isFinished == true -> {
                h.gameStatus.text = "encerrado"
                h.gameStatus.setTextColor(android.graphics.Color.parseColor("#A5A5A5")) // cinza
                h.gameStatus.visibility = View.VISIBLE
                h.gameStatus.alpha = 1f
            }
            else -> {
                h.gameStatus.visibility = View.GONE
                h.gameStatus.alpha = 1f
            }
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
                val d = h.itemView.resources.displayMetrics.density
val b = Button(h.itemView.context).apply {
    text = title
    setAllCaps(false)
    setTextColor(android.graphics.Color.parseColor("#222222"))
    textSize = 14f

    // fundo tipo chip (cinza claro, cantos arredondados)
    background = android.graphics.drawable.GradientDrawable().apply {
        shape = android.graphics.drawable.GradientDrawable.RECTANGLE
        cornerRadius = 10f * d
        setColor(android.graphics.Color.parseColor("#F2F2F2"))
    }

    // tira o tamanho mínimo grandão do Button padrão
    minHeight = 0
    minimumHeight = 0
    minWidth = 0
    minimumWidth = 0
    includeFontPadding = false

    // padding menor (chip)
    setPadding((14 * d).toInt(), (8 * d).toInt(), (14 * d).toInt(), (8 * d).toInt())

    // margens entre os chips
    layoutParams = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.WRAP_CONTENT,
        LinearLayout.LayoutParams.WRAP_CONTENT
    ).apply {
        marginEnd = (8 * d).toInt()
        topMargin = (6 * d).toInt()
    }

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
            onOpenLink?.invoke(u, title, null, null)
                ?: run {
                    if (u.startsWith("http", ignoreCase = true)) {
                        val it = Intent(ctx, com.futanium.box.WebViewActivity::class.java)
                        it.putExtra(com.futanium.box.WebViewActivity.EXTRA_URL, u)
                        ctx.startActivity(it)
                    } else {
                        val it = Intent(Intent.ACTION_VIEW, Uri.parse(u))
                        ctx.startActivity(it)
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