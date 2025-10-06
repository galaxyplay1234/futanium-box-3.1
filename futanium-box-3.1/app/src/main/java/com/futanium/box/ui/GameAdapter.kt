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
import androidx.browser.customtabs.CustomTabsIntent

class GameAdapter(
    private val items: MutableList<Game> = mutableListOf()
) : RecyclerView.Adapter<GameAdapter.VH>() {
		// 🔹 Aviso global (primeiro card)
private var notice: com.futanium.box.model.Notice? = null

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


   fun setNotice(noticeData: com.futanium.box.model.Notice?) {
    notice = noticeData
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

        val gameStatus: TextView = v.findViewById(R.id.gameStatus)

        // 🔧 Agora como ViewGroup para suportar FlexboxLayout ou LinearLayout
        val btnContainer: ViewGroup = v.findViewById(R.id.btnContainer)
    }

		override fun getItemViewType(position: Int): Int {
    return if (notice != null && notice?.ativo == "sim" && position == 0) 0 else 1
}


    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
    val inflater = LayoutInflater.from(parent.context)
    val layout = if (viewType == 0)
        R.layout.item_notice
    else
        R.layout.item_game
    val view = inflater.inflate(layout, parent, false)
    return VH(view)
}

    override fun getItemCount(): Int {
    return items.size + if (notice != null && notice?.ativo == "sim") 1 else 0
}

    override fun onBindViewHolder(h: VH, position: Int) {
        if (notice != null && notice?.ativo == "sim" && position == 0) {
    bindNotice(h)
    return
}
val gameIndex = if (notice != null && notice?.ativo == "sim") position - 1 else position
val g = items[gameIndex]

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
                h.gameStatus.setTextColor(android.graphics.Color.parseColor("#FF3B30"))
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
                h.gameStatus.setTextColor(android.graphics.Color.parseColor("#A5A5A5"))
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
            val d = h.itemView.resources.displayMetrics.density
            btns.forEachIndexed { idx, anyBtn ->
                val (title, link) = extractTitleAndLink(anyBtn, idx)

                val ctx = h.itemView.context

                // ripple
                val rippleColor = android.content.res.ColorStateList.valueOf(
                    android.graphics.Color.parseColor("#22000000")
                )
                val content = androidx.appcompat.content.res.AppCompatResources.getDrawable(
                    ctx, R.drawable.bg_channel_button
                )
                val mask = android.graphics.drawable.GradientDrawable().apply {
                    shape = android.graphics.drawable.GradientDrawable.RECTANGLE
                    cornerRadius = 12f * d
                    setColor(android.graphics.Color.WHITE)
                }
                val ripple = android.graphics.drawable.RippleDrawable(rippleColor, content, mask)

                val b = Button(ctx).apply {
                    text = title
                    setAllCaps(false)
                    setTextColor(android.graphics.Color.parseColor("#222222"))
                    textSize = 14f
                    background = ripple
                    stateListAnimator = null
                    elevation = 0f
                    backgroundTintList = null
                    minHeight = 0; minimumHeight = 0
                    minWidth  = 0; minimumWidth  = 0
                    includeFontPadding = false
                    setPadding((14 * d).toInt(), (8 * d).toInt(), (14 * d).toInt(), (8 * d).toInt())

                    setOnClickListener { v ->
                        v.performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY)
                        v.isPressed = true
                        v.refreshDrawableState()
                        v.animate().cancel()
                        v.animate().scaleX(0.98f).scaleY(0.98f).setDuration(90)
                            .withEndAction { v.animate().scaleX(1f).scaleY(1f).setDuration(140).start() }
                            .start()
                        v.postDelayed({ openLink(h.itemView, title, link) }, 130)
                    }
                }

                // 👉 LayoutParams compatível (FlexboxLayout OU LinearLayout)
                val lp: ViewGroup.MarginLayoutParams =
                    if (h.btnContainer is com.google.android.flexbox.FlexboxLayout) {
                        com.google.android.flexbox.FlexboxLayout.LayoutParams(
                            ViewGroup.LayoutParams.WRAP_CONTENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT
                        ).apply {
                            // margem entre "chips"
                            rightMargin = (8 * d).toInt()
                            topMargin = (6 * d).toInt()
                        }
                    } else {
                        LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.WRAP_CONTENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT
                        ).apply {
                            marginEnd = (8 * d).toInt()
                            topMargin = (6 * d).toInt()
                        }
                    }

                h.btnContainer.addView(b, lp)
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


// === abre o canal + bloqueia múltiplos cliques + mostra anúncio ===
private var lastClickTime = 0L

private fun openLink(view: View, title: String?, link: String) {
    val ctx = view.context
    val u = link.trim()
    val monetagUrl = "https://otieu.com/4/9902033" // 🔸 seu link Monetag

    try {
        // ⛔ bloqueia cliques múltiplos por 3s
        val now = System.currentTimeMillis()
        if (now - lastClickTime < 3000) {
            Toast.makeText(ctx, "Aguarde um momento...", Toast.LENGTH_SHORT).show()
            return
        }
        lastClickTime = now

        // 🔹 Decide se vai para Player ou WebView
        if (u.startsWith("http", ignoreCase = true)) {
            val lower = u.lowercase()
            if (lower.endsWith(".m3u8") || lower.endsWith(".ts") || lower.endsWith(".mp4")) {
                // 🎥 Abre PlayerActivity (ExoPlayer)
                val it = Intent(ctx, com.futanium.box.PlayerActivity::class.java)
                it.putExtra(com.futanium.box.PlayerActivity.EXTRA_URL, u)
                it.putExtra(com.futanium.box.PlayerActivity.EXTRA_TITLE, title ?: "")
                ctx.startActivity(it)
            } else {
                // 🌐 Abre WebViewActivity
                val it = Intent(ctx, com.futanium.box.WebViewActivity::class.java)
                it.putExtra(com.futanium.box.WebViewActivity.EXTRA_URL, u)
                ctx.startActivity(it)
            }
        } else {
            val it = Intent(Intent.ACTION_VIEW, Uri.parse(u))
            ctx.startActivity(it)
        }

        // 🔹 Após 1s, abre o anúncio Monetag
        view.postDelayed({
            try {
                val customTabsIntent = androidx.browser.customtabs.CustomTabsIntent.Builder()
                    .setShowTitle(true)
                    .setUrlBarHidingEnabled(false)
                    .setToolbarColor(android.graphics.Color.parseColor("#202020"))
                    .setColorScheme(androidx.browser.customtabs.CustomTabsIntent.COLOR_SCHEME_DARK)
                    .build()

                customTabsIntent.intent.addFlags(Intent.FLAG_ACTIVITY_NO_HISTORY)
                customTabsIntent.intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
                customTabsIntent.intent.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)

                customTabsIntent.launchUrl(ctx, Uri.parse(monetagUrl))

                view.postDelayed({
                    Toast.makeText(
                        ctx,
                        "Esta é uma página de anúncio.\nFeche no X ou use o botão Voltar.",
                        Toast.LENGTH_LONG
                    ).show()
                }, 300)

            } catch (e: Exception) {
                e.printStackTrace()
                Toast.makeText(ctx, "Não foi possível abrir o anúncio.", Toast.LENGTH_SHORT).show()
            }
        }, 1000)

    } catch (e: Exception) {
        e.printStackTrace()
        Toast.makeText(ctx, "Erro ao abrir o canal.", Toast.LENGTH_SHORT).show()
    }
}

private fun bindNotice(h: VH) {
    val n = notice ?: return
    val ctx = h.itemView.context
    val container = h.itemView.findViewById<ViewGroup>(R.id.noticeButtons)
    val iconView = h.itemView.findViewById<TextView>(R.id.noticeIcon)
    val msgView = h.itemView.findViewById<TextView>(R.id.noticeMessage)

    iconView.text = n.icone ?: "ℹ️"
    msgView.text = n.mensagem ?: ""

    container.removeAllViews()
    val d = ctx.resources.displayMetrics.density

    val buttons = listOf(
        n.botao1_name to n.link1,
        n.botao2_name to n.link2,
        n.botao3_name to n.link3,
        n.botao4_name to n.link4
    )

    buttons.forEach { (name, link) ->
        if (!name.isNullOrBlank() && !link.isNullOrBlank()) {
            val b = Button(ctx).apply {
                text = name
                setAllCaps(false)
                setTextColor(android.graphics.Color.parseColor("#222222"))
                textSize = 14f
                background = ctx.getDrawable(R.drawable.bg_channel_button)
                setPadding((14 * d).toInt(), (8 * d).toInt(), (14 * d).toInt(), (8 * d).toInt())
                setOnClickListener {
                    val i = Intent(Intent.ACTION_VIEW, Uri.parse(link))
                    ctx.startActivity(i)
                }
            }
            container.addView(b)
        }
    }
}


}
/** Opcional: tipo forte para botões */
data class ButtonInfo(
    val name: String?,
    val url: String?
)