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
    val items: MutableList<Game> = mutableListOf()
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
        val tvChamp: TextView = v.findViewById(R.id.tvChamp)
        val tvHome: TextView = v.findViewById(R.id.tvHomeName)
        val ivHome: ImageView = v.findViewById(R.id.imgHome)
        val tvTime: TextView = v.findViewById(R.id.tvTime)
        val ivAway: ImageView = v.findViewById(R.id.imgAway)
        val tvAway: TextView = v.findViewById(R.id.tvAwayName)
        val gameStatus: TextView = v.findViewById(R.id.gameStatus)
        val btnContainer: ViewGroup = v.findViewById(R.id.btnContainer)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val inflater = LayoutInflater.from(parent.context)
        val view = inflater.inflate(R.layout.item_game, parent, false)
        return VH(view)
    }

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(h: VH, position: Int) {
        val g = items[position]
        val isNotice = g.homeLogo == "Aviso" || g.homeName == "Aviso"

        // ======= AVISO =======
        if (isNotice) {
            h.tvChamp.text = "${g.championshipImageUrl ?: ""}  ${g.championship}"
            h.imgChamp.visibility = View.GONE
            h.tvHome.visibility = View.GONE
            h.tvAway.visibility = View.GONE
            h.tvTime.visibility = View.GONE
            h.gameStatus.visibility = View.GONE
            h.ivHome.visibility = View.GONE
            h.ivAway.visibility = View.GONE

            // mostra os botões direto
            h.btnContainer.visibility = View.VISIBLE
            h.btnContainer.removeAllViews()

            val d = h.itemView.resources.displayMetrics.density
            val ctx = h.itemView.context
            val btns: List<Any> = (g.buttons as? List<*>)?.filterNotNull() ?: emptyList()

            btns.forEachIndexed { idx, anyBtn ->
                val (title, link) = extractTitleAndLink(anyBtn, idx)
                if (title.isBlank() || link.isBlank()) return@forEachIndexed

                val b = Button(ctx).apply {
                    text = title
                    setAllCaps(false)
                    textSize = 14f
                    setTextColor(android.graphics.Color.parseColor("#222222"))
                    background = android.graphics.drawable.GradientDrawable().apply {
                        cornerRadius = 12f * d
                        setColor(android.graphics.Color.WHITE)
                        setStroke(1, android.graphics.Color.parseColor("#CCCCCC"))
                    }
                    setPadding((14 * d).toInt(), (8 * d).toInt(), (14 * d).toInt(), (8 * d).toInt())
                    setOnClickListener {
                        try {
                            if (link.startsWith("f:")) {
                                // abre fora do app
                                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(link.removePrefix("f:")))
                                ctx.startActivity(intent)
                            } else {
                                // abre no webview do app
                                onOpenLink?.invoke(link, title, null, null)
                            }
                        } catch (e: Exception) {
                            Toast.makeText(ctx, "Erro ao abrir o link.", Toast.LENGTH_SHORT).show()
                        }
                    }
                }

                val lp = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply {
                    marginEnd = (8 * d).toInt()
                    topMargin = (6 * d).toInt()
                }

                h.btnContainer.addView(b, lp)
            }

            return
        }

        // ======= JOGOS NORMAIS =======

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

        // STATUS
        (h.gameStatus.getTag(R.id.tag_blink_anim) as? android.animation.ObjectAnimator)?.cancel()
        h.gameStatus.setTag(R.id.tag_blink_anim, null)
        h.gameStatus.animate().cancel()
        h.gameStatus.alpha = 1f
        h.gameStatus.visibility = View.GONE

        when {
            g.isLive == true -> {
                h.gameStatus.text = "ao vivo"
                h.gameStatus.setTextColor(android.graphics.Color.parseColor("#FF3B30"))
                h.gameStatus.visibility = View.VISIBLE
                val anim = android.animation.ObjectAnimator.ofFloat(h.gameStatus, View.ALPHA, 1f, 0.3f)
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
            }
        }

        // BOTÕES (expande ao clicar)
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
                    setPadding((14 * d).toInt(), (8 * d).toInt(), (14 * d).toInt(), (8 * d).toInt())

                    setOnClickListener { v ->
                        v.performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY)
                        v.isPressed = true
                        v.animate().scaleX(0.96f).scaleY(0.96f).setDuration(80)
                            .withEndAction { v.animate().scaleX(1f).scaleY(1f).setDuration(100).start() }
                            .start()

                        if (link.startsWith("f:")) {
                            val it = Intent(Intent.ACTION_VIEW, Uri.parse(link.removePrefix("f:")))
                            ctx.startActivity(it)
                        } else {
                            onOpenLink?.invoke(link, title, null, null)
                        }
                    }
                }

                val lp = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply {
                    marginEnd = (8 * d).toInt()
                    topMargin = (6 * d).toInt()
                }

                h.btnContainer.addView(b, lp)
            }
        }

        h.itemView.setOnClickListener {
            if (!hasButtons) return@setOnClickListener
            val old = expandedPos
            expandedPos = if (position == expandedPos) -1 else position
            if (old != -1) notifyItemChanged(old)
            notifyItemChanged(position)
        }
    }

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

        if (title.isBlank()) title = "Canal ${index + 1}"
        if (link.isBlank()) link = rawUrl?.takeIf { it.isNotBlank() } ?: "#"
        return title to link
    }
}

data class ButtonInfo(
    val name: String?,
    val url: String?
)