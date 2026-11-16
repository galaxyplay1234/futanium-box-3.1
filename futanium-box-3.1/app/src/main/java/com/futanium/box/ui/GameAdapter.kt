
package com.futanium.box.ui

import android.content.Intent
import android.net.Uri
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.recyclerview.widget.RecyclerView
import coil.dispose
import coil.load
import com.futanium.box.R
import com.futanium.box.model.Game
import com.futanium.box.MainActivity
import org.json.JSONObject
import androidx.browser.customtabs.CustomTabsIntent
import android.text.TextUtils
import com.google.android.material.card.MaterialCardView

class GameAdapter(
    private val items: MutableList<Game> = mutableListOf()
) : RecyclerView.Adapter<GameAdapter.VH>() {

    var isTv: Boolean = false

    var onOpenLink: ((url: String, title: String?, referer: String?, ua: String?) -> Unit)? = null

    private var expandedPos: Int = -1

    private var monetagEnabledRemote = true
    private var monetagLinkRemote: String = "https://otieu.com/4/9902033"
    private var adsConfigLoaded = false

    private val focusBlue = Color.parseColor("#168BFF")
    private val normalStroke = Color.parseColor("#DDDDDD")
    private val buttonGray = Color.parseColor("#E6E8EB")
    private val buttonText = Color.parseColor("#333333")

    private var pendingInitialFocus = false

    fun submit(newItems: List<Game>) {

        items.clear()

        val aviso = newItems.find {
            it.homeLogo == "Aviso"
        }

        val restantes = newItems.filter {
            it.homeLogo != "Aviso"
        }

        if (aviso != null) items.add(aviso)

        items.addAll(restantes)

        expandedPos = -1

        notifyDataSetChanged()

        if (isTv) {
            val firstGame = items.indexOfFirst {
                it.homeLogo != "Aviso"
            }

            if (firstGame >= 0) {
                pendingInitialFocus = true
            }
        }
    }

    fun isNoticeAt(position: Int): Boolean {
        return items.getOrNull(position)?.homeLogo == "Aviso"
    }

    fun updateLiveData(newItems: List<Game>) {

        items.clear()

        val aviso = newItems.find {
            it.homeLogo == "Aviso"
        }

        val restantes = newItems.filter {
            it.homeLogo != "Aviso"
        }

        if (aviso != null) items.add(aviso)

        items.addAll(restantes)

        notifyDataSetChanged()
    }

    inner class VH(v: View) : RecyclerView.ViewHolder(v) {

        val imgChamp: ImageView =
            v.findViewById(R.id.imgChamp)

        val tvChamp: TextView =
            v.findViewById(R.id.tvChamp)

        val tvHome: TextView =
            v.findViewById(R.id.tvHomeName)

        val ivHome: ImageView =
            v.findViewById(R.id.imgHome)

        val tvTime: TextView =
            v.findViewById(R.id.tvTime)

        val ivAway: ImageView =
            v.findViewById(R.id.imgAway)

        val tvAway: TextView =
            v.findViewById(R.id.tvAwayName)

        val gameStatus: TextView =
            v.findViewById(R.id.gameStatus)

        val btnContainer: ViewGroup =
            v.findViewById(R.id.btnContainer)
    }

    override fun onCreateViewHolder(
        parent: ViewGroup,
        viewType: Int
    ): VH {

        val layout = if (isTv) {
            R.layout.item_game_tv
        } else {
            R.layout.item_game
        }

        val view = LayoutInflater.from(parent.context)
            .inflate(layout, parent, false)

        if (isTv) {

            view.layoutParams = RecyclerView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                view.layoutParams?.height
                    ?: ViewGroup.LayoutParams.WRAP_CONTENT
            )

            view.isFocusable = true
            view.isFocusableInTouchMode = true

            view.setOnFocusChangeListener { v, hasFocus ->

                if (v is MaterialCardView) {

                    v.strokeColor =
                        if (hasFocus) focusBlue else normalStroke

                    v.strokeWidth =
                        if (hasFocus) {
                            (3 * v.resources.displayMetrics.density).toInt()
                        } else {
                            (1 * v.resources.displayMetrics.density).toInt()
                        }

                    v.cardElevation =
                        if (hasFocus) {
                            9f * v.resources.displayMetrics.density
                        } else {
                            5f * v.resources.displayMetrics.density
                        }
                }
            }
        }

        return VH(view)
    }

    override fun getItemCount(): Int = items.size

    fun getCurrentGames(): List<Game> {
        return items.toList()
    }

    override fun onBindViewHolder(
        h: VH,
        position: Int
    ) {

        h.ivHome.visibility = View.VISIBLE
        h.ivAway.visibility = View.VISIBLE
        h.tvHome.visibility = View.VISIBLE
        h.tvAway.visibility = View.VISIBLE
        h.tvTime.visibility = View.VISIBLE
        h.gameStatus.visibility = View.GONE
        h.btnContainer.visibility = View.GONE

        h.btnContainer.removeAllViews()

        h.tvChamp.isSingleLine = true
        h.tvChamp.maxLines = 1
        h.tvChamp.ellipsize = TextUtils.TruncateAt.END

        h.imgChamp.setImageDrawable(null)
        h.imgChamp.visibility = View.GONE

        (h.imgChamp.layoutParams as? ViewGroup.MarginLayoutParams)?.apply {
            marginStart = 0
            topMargin = 0
            h.imgChamp.layoutParams = this
        }

        fetchAdsConfig()

        val g = items[position]

        val isAviso = g.homeLogo == "Aviso"

        val d =
            h.itemView.resources.displayMetrics.density

        // =====================================================
        // AVISO
        // =====================================================

        if (isAviso) {

            h.ivHome.visibility = View.GONE
            h.ivAway.visibility = View.GONE
            h.tvHome.visibility = View.GONE
            h.tvAway.visibility = View.GONE
            h.tvTime.visibility = View.GONE
            h.gameStatus.visibility = View.GONE

            val iconValue =
                g.championshipImageUrl.orEmpty()

            h.imgChamp.visibility = View.VISIBLE

            if (iconValue.startsWith("http", true)) {

                h.imgChamp.load(iconValue) {
                    crossfade(true)
                }

                h.tvChamp.text =
                    g.championship.orEmpty()

            } else {

                h.imgChamp.setImageDrawable(null)

                h.tvChamp.text =
                    "$iconValue  ${g.championship.orEmpty()}"

                h.tvChamp.setTextColor(
                    Color.parseColor("#222222")
                )
            }

            (h.imgChamp.layoutParams as? ViewGroup.MarginLayoutParams)?.let { lp ->
                lp.marginStart = (-25 * d).toInt()
                lp.topMargin = 0
                h.imgChamp.layoutParams = lp
            }

            h.tvChamp.isSingleLine = false
            h.tvChamp.maxLines = 3
            h.tvChamp.ellipsize = TextUtils.TruncateAt.END
            h.tvChamp.textAlignment = View.TEXT_ALIGNMENT_TEXT_START
            h.tvChamp.setLineSpacing(0f, 1.1f)
            h.tvChamp.setPadding(0, 0, 0, 0)

            (h.tvChamp.layoutParams as? ViewGroup.MarginLayoutParams)?.let { lp ->
                lp.marginStart = (8 * d).toInt()
                lp.bottomMargin = (3 * d).toInt()
                lp.topMargin = 0
                h.tvChamp.layoutParams = lp
            }

            val btns: List<Any> =
                (g.buttons as? List<*>)?.filterNotNull()
                    ?: emptyList()

            h.btnContainer.removeAllViews()

            h.btnContainer.visibility =
                if (btns.isNotEmpty()) View.VISIBLE
                else View.GONE

            if (btns.isNotEmpty()) {

                btns.forEachIndexed { idx, anyBtn ->

                    val (title, link, _) =
                        extractTitleAndLink(anyBtn, idx)

                    val ctx = h.itemView.context

                    val b = Button(ctx).apply {

                        text = title
                        setAllCaps(false)
                        setTextColor(buttonText)
                        textSize = 14f

                        includeFontPadding = false

                        minHeight = 0
                        minimumHeight = 0
                        minWidth = 0
                        minimumWidth = 0

                        setPadding(
                            (14 * d).toInt(),
                            (6 * d).toInt(),
                            (14 * d).toInt(),
                            (6 * d).toInt()
                        )

                        stateListAnimator = null
                        elevation = 0f

                        isClickable = true
                        isFocusable = isTv
                        isFocusableInTouchMode = isTv

                        if (isTv) {

                            setBackground(
                                createNoticeButtonBackground(false, d)
                            )

                            backgroundTintList = null

                        } else {

                            background =
                                androidx.appcompat.content.res.AppCompatResources.getDrawable(
                                    ctx,
                                    R.drawable.bg_channel_button
                                )
                        }

                        if (isTv) {

                            setOnFocusChangeListener { button, hasFocus ->

                                button.background =
                                    createNoticeButtonBackground(
                                        hasFocus,
                                        d
                                    )
                            }

                            setOnKeyListener { button, keyCode, event ->

                                if (
                                    event.action != KeyEvent.ACTION_DOWN
                                ) {
                                    return@setOnKeyListener false
                                }

                                if (
                                    keyCode == KeyEvent.KEYCODE_DPAD_CENTER ||
                                    keyCode == KeyEvent.KEYCODE_ENTER
                                ) {
                                    button.performClick()
                                    return@setOnKeyListener true
                                }

                                false
                            }
                        }

                        setOnClickListener {

                            it.performHapticFeedback(
                                android.view.HapticFeedbackConstants.VIRTUAL_KEY
                            )

                            openAvisoLink(
                                it,
                                title,
                                link
                            )
                        }
                    }

                    val lp = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    ).apply {

                        marginEnd = (8 * d).toInt()
                        topMargin = (4 * d).toInt()
                    }

                    h.btnContainer.addView(b, lp)
                }

                if (isTv) {

                    h.btnContainer.descendantFocusability =
                        ViewGroup.FOCUS_AFTER_DESCENDANTS

                    h.btnContainer.isFocusable = false
                    h.btnContainer.isFocusableInTouchMode = false
                }
            }

            h.itemView.isFocusable = false
            h.itemView.isFocusableInTouchMode = false
            h.itemView.isClickable = false
            h.itemView.setOnClickListener(null)

            if (expandedPos == position) {
                expandedPos = -1
            }

            return
        }

        // =====================================================
        // JOGOS NORMAIS
        // =====================================================

        h.itemView.isFocusable = isTv
        h.itemView.isFocusableInTouchMode = isTv
        h.itemView.isClickable = true

        h.tvChamp.isSingleLine = true
        h.tvChamp.maxLines = 1
        h.tvChamp.ellipsize = TextUtils.TruncateAt.END

        val champName =
            g.championship.orEmpty()

        h.tvChamp.text = champName

        h.tvChamp.visibility =
            if (champName.isBlank()) View.GONE
            else View.VISIBLE

        h.tvChamp.setTextColor(
            Color.parseColor("#888B96")
        )

        val champLogo =
            g.championshipImageUrl

        h.imgChamp.dispose()

        if (champLogo.isNullOrBlank()) {

            h.imgChamp.setImageDrawable(null)
            h.imgChamp.visibility = View.GONE

        } else {

            h.imgChamp.visibility = View.VISIBLE

            h.imgChamp.load(champLogo) {
                crossfade(false)
                allowHardware(false)
                placeholder(android.R.color.transparent)
                error(android.R.color.transparent)
            }
        }

        h.tvHome.text =
            g.homeName.orEmpty()

        h.tvAway.text =
            g.awayName.orEmpty()

        if (isTv) {

            h.tvHome.maxLines = 1
            h.tvHome.isSingleLine = true
            h.tvHome.ellipsize = TextUtils.TruncateAt.END

            h.tvAway.maxLines = 1
            h.tvAway.isSingleLine = true
            h.tvAway.ellipsize = TextUtils.TruncateAt.END
        }

        h.tvTime.text =
            if (!g.liveScore.isNullOrEmpty()) {
                g.liveScore
            } else {
                g.time.orEmpty()
            }

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

        (h.gameStatus.getTag(R.id.tag_blink_anim)
            as? android.animation.ObjectAnimator)?.let {

            it.cancel()
            h.gameStatus.setTag(R.id.tag_blink_anim, null)
        }

        h.gameStatus.animate().cancel()
        h.gameStatus.alpha = 1f
        h.gameStatus.visibility = View.GONE

        when {

            !g.liveMinute.isNullOrEmpty() -> {

                if (g.liveMinute.equals("encerrado", true)) {

                    h.gameStatus.text = "encerrado"

                    h.gameStatus.setTextColor(
                        Color.parseColor("#A5A5A5")
                    )

                    h.gameStatus.visibility = View.VISIBLE
                    h.gameStatus.alpha = 1f

                } else {

                    h.gameStatus.text =
                        "• ${g.liveMinute}"

                    h.gameStatus.setTextColor(
                        Color.parseColor("#FF3B30")
                    )

                    h.gameStatus.visibility = View.VISIBLE

                    val anim =
                        android.animation.ObjectAnimator.ofFloat(
                            h.gameStatus,
                            View.ALPHA,
                            1f,
                            0.3f
                        ).apply {

                            duration = 500

                            repeatMode =
                                android.animation.ValueAnimator.REVERSE

                            repeatCount =
                                android.animation.ValueAnimator.INFINITE

                            interpolator =
                                android.view.animation.LinearInterpolator()
                        }

                    h.gameStatus.setTag(
                        R.id.tag_blink_anim,
                        anim
                    )

                    anim.start()
                }
            }

            g.isLive == true -> {

                h.gameStatus.text = "ao vivo"

                h.gameStatus.setTextColor(
                    Color.parseColor("#FF3B30")
                )

                h.gameStatus.visibility = View.VISIBLE

                val anim =
                    android.animation.ObjectAnimator.ofFloat(
                        h.gameStatus,
                        View.ALPHA,
                        1f,
                        0.3f
                    ).apply {

                        duration = 500

                        repeatMode =
                            android.animation.ValueAnimator.REVERSE

                        repeatCount =
                            android.animation.ValueAnimator.INFINITE

                        interpolator =
                            android.view.animation.LinearInterpolator()
                    }

                h.gameStatus.setTag(
                    R.id.tag_blink_anim,
                    anim
                )

                anim.start()
            }

            g.isFinished == true -> {

                h.gameStatus.text = "encerrado"

                h.gameStatus.setTextColor(
                    Color.parseColor("#A5A5A5")
                )

                h.gameStatus.visibility = View.VISIBLE
                h.gameStatus.alpha = 1f
            }

            else -> {

                h.gameStatus.visibility = View.GONE
                h.gameStatus.alpha = 1f
            }
        }

        val btns: List<Any> =
            (g.buttons as? List<*>)?.filterNotNull()
                ?: emptyList()

        val hasButtons =
            btns.isNotEmpty()

        val isExpanded =
            position == expandedPos && hasButtons

        h.btnContainer.visibility =
            if (isExpanded) View.VISIBLE else View.GONE

        h.btnContainer.removeAllViews()

        if (isExpanded) {

            btns.forEachIndexed { idx, anyBtn ->

                val (title, link, captureM3u8) =
                    extractTitleAndLink(anyBtn, idx)

                val ctx = h.itemView.context

                val rippleColor = ColorStateList.valueOf(
                    Color.parseColor("#22000000")
                )

                val content =
                    androidx.appcompat.content.res.AppCompatResources.getDrawable(
                        ctx,
                        R.drawable.bg_channel_button
                    )

                val mask = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    cornerRadius = 12f * d
                    setColor(Color.WHITE)
                }

                val ripple = RippleDrawable(
                    rippleColor,
                    content,
                    mask
                )

                val b = Button(ctx).apply {

                    text = title
                    setAllCaps(false)
                    setTextColor(Color.parseColor("#222222"))
                    textSize = 14f
                    background = ripple

                    stateListAnimator = null
                    elevation = 0f
                    backgroundTintList = null

                    minHeight = 0
                    minimumHeight = 0
                    minWidth = 0
                    minimumWidth = 0

                    includeFontPadding = false

                    setPadding(
                        (14 * d).toInt(),
                        (8 * d).toInt(),
                        (14 * d).toInt(),
                        (8 * d).toInt()
                    )

                    setOnClickListener { v ->

                        v.performHapticFeedback(
                            android.view.HapticFeedbackConstants.VIRTUAL_KEY
                        )

                        v.isPressed = true
                        v.refreshDrawableState()
                        v.animate().cancel()

                        v.animate()
                            .scaleX(0.98f)
                            .scaleY(0.98f)
                            .setDuration(90)
                            .withEndAction {

                                v.animate()
                                    .scaleX(1f)
                                    .scaleY(1f)
                                    .setDuration(140)
                                    .start()
                            }
                            .start()

                        v.postDelayed({

                            openLink(
                                h.itemView,
                                title,
                                link,
                                captureM3u8
                            )

                        }, 130)
                    }
                }

                val lp: ViewGroup.MarginLayoutParams =
                    if (
                        h.btnContainer is
                        com.google.android.flexbox.FlexboxLayout
                    ) {

                        com.google.android.flexbox.FlexboxLayout.LayoutParams(
                            ViewGroup.LayoutParams.WRAP_CONTENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT
                        ).apply {

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

        // Ao selecionar o card, apenas expande ou recolhe os players.
        h.itemView.setOnClickListener {

            val current = h.bindingAdapterPosition

            if (current == RecyclerView.NO_POSITION) {
                return@setOnClickListener
            }

            val prev = expandedPos

            if (prev == current) {

                expandedPos = -1
                notifyItemChanged(current)

            } else {

                expandedPos = current

                if (prev != -1) {
                    notifyItemChanged(prev)
                }

                notifyItemChanged(current)
            }
        }
    }

    // =====================================================
    // FUNDO E CONTORNO DOS BOTÕES DO AVISO
    // =====================================================

    private fun createNoticeButtonBackground(
        focused: Boolean,
        density: Float
    ): RippleDrawable {

        val drawable = GradientDrawable().apply {

            shape = GradientDrawable.RECTANGLE

            cornerRadius = 10f * density

            setColor(buttonGray)

            setStroke(
                if (focused) {
                    (2 * density).toInt()
                } else {
                    (1 * density).toInt()
                },
                if (focused) {
                    focusBlue
                } else {
                    Color.parseColor("#D3D6DA")
                }
            )
        }

        val mask = GradientDrawable().apply {

            shape = GradientDrawable.RECTANGLE

            cornerRadius = 10f * density

            setColor(Color.WHITE)
        }

        return RippleDrawable(
            ColorStateList.valueOf(
                Color.parseColor("#33000000")
            ),
            drawable,
            mask
        )
    }

    override fun onViewAttachedToWindow(holder: VH) {

        super.onViewAttachedToWindow(holder)

        if (
            isTv &&
            pendingInitialFocus &&
            holder.bindingAdapterPosition != RecyclerView.NO_POSITION &&
            !isNoticeAt(holder.bindingAdapterPosition)
        ) {

            pendingInitialFocus = false

            holder.itemView.post {
                holder.itemView.requestFocus()
            }
        }
    }

    // =====================================================
    // CONFIGURAÇÃO DE ANÚNCIOS
    // =====================================================

    private fun fetchAdsConfig() {

        if (adsConfigLoaded) return

        adsConfigLoaded = true

        Thread {

            try {

                val url = java.net.URL(
                    "https://raw.githubusercontent.com/galaxyplay1234/futanium-box-3.1/refs/heads/main/ads.json"
                )

                val json = url.readText(Charsets.UTF_8)

                val obj = JSONObject(json)

                val ativo =
                    obj.optString("ativo", "sim")

                monetagEnabledRemote =
                    ativo.equals("sim", ignoreCase = true)

                monetagLinkRemote =
                    obj.optString("link", monetagLinkRemote)

                android.util.Log.d(
                    "FutaniumAds",
                    "Config carregada: ativo=$monetagEnabledRemote link=$monetagLinkRemote"
                )

            } catch (e: Exception) {

                e.printStackTrace()

                android.util.Log.e(
                    "FutaniumAds",
                    "Falha ao carregar ads.json: ${e.message}"
                )
            }

        }.start()
    }

    // =====================================================
    // ABRIR LINK DO AVISO
    // =====================================================

    private fun openAvisoLink(
        view: View,
        title: String?,
        link: String
    ) {

        val ctx = view.context

        try {

            if (link.startsWith("f:", ignoreCase = true)) {

                val real =
                    link.removePrefix("f:")

                val intent = Intent(
                    Intent.ACTION_VIEW,
                    Uri.parse(real)
                )

                ctx.startActivity(intent)

            } else {

                val it = Intent(
                    ctx,
                    com.futanium.box.WebViewActivity::class.java
                )

                it.putExtra(
                    com.futanium.box.WebViewActivity.EXTRA_URL,
                    link
                )

                ctx.startActivity(it)
            }

        } catch (_: Exception) {

            Toast.makeText(
                ctx,
                "Erro ao abrir o link do aviso.",
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    // =====================================================
    // EXTRAIR NOME E LINK
    // =====================================================

    private fun extractTitleAndLink(
        anyBtn: Any,
        index: Int
    ): Triple<String, String, Boolean> {

        var rawName: String? = null
        var rawUrl: String? = null
        var captureM3u8 = false

        when (anyBtn) {

            is ButtonInfo -> {

                rawName = anyBtn.name
                rawUrl = anyBtn.url
                captureM3u8 = anyBtn.captureM3u8
            }

            is Map<*, *> -> {

                rawName =
                    anyBtn["name"]?.toString()

                rawUrl =
                    anyBtn["url"]?.toString()

                captureM3u8 =
                    anyBtn["captureM3u8"]
                        ?.toString()
                        ?.toBoolean() ?: false
            }

            is JSONObject -> {

                rawName =
                    anyBtn.optString("name", null)

                rawUrl =
                    anyBtn.optString("url", null)

                captureM3u8 =
                    anyBtn.optBoolean("captureM3u8", false)
            }

            else -> {
                rawName = anyBtn.toString()
            }
        }

        var title =
            rawName?.trim().orEmpty()

        var link =
            rawUrl?.trim().orEmpty()

        Regex("""\s+(go:\S+)\s*$""")
            .find(title)?.let { m ->

                link = m.groupValues[1]

                title =
                    title.removeRange(m.range).trim()
            }

        if (title.isBlank()) {
            title = "Canal ${index + 1}"
        }

        if (link.isBlank()) {
            link = rawUrl?.takeIf {
                it.isNotBlank()
            } ?: "#"
        }

        return Triple(
            title,
            link,
            captureM3u8
        )
    }

    // =====================================================
    // ABRIR CANAL
    // =====================================================

    private var lastClickTime = 0L

    private fun openLink(
        view: View,
        title: String?,
        link: String,
        captureM3u8: Boolean
    ) {

        val ctx = view.context

        val u = link.trim()

        try {

            val now =
                System.currentTimeMillis()

            if (now - lastClickTime < 3000) {

                Toast.makeText(
                    ctx,
                    "Aguarde um momento...",
                    Toast.LENGTH_SHORT
                ).show()

                return
            }

            lastClickTime = now

            if (u.startsWith("http", ignoreCase = true)) {

                val mediaUrl = u.substringBefore("?")
                    .substringBefore("#")
                    .lowercase()

                val isDirectMedia =
                    mediaUrl.endsWith(".m3u8") ||
                    mediaUrl.endsWith(".mpd") ||
                    mediaUrl.endsWith(".ts") ||
                    mediaUrl.endsWith(".mp4")

                if (isDirectMedia && !captureM3u8) {

                    val it = Intent(
                        ctx,
                        com.futanium.box.PlayerActivity::class.java
                    )

                    it.putExtra(
                        com.futanium.box.PlayerActivity.EXTRA_URL,
                        u
                    )

                    it.putExtra(
                        com.futanium.box.PlayerActivity.EXTRA_TITLE,
                        title ?: ""
                    )

                    MainActivity.navigatingInsideApp = true

                    ctx.startActivity(it)

                } else {

                    val it = Intent(
                        ctx,
                        com.futanium.box.WebViewActivity::class.java
                    )

                    it.putExtra(
                        com.futanium.box.WebViewActivity.EXTRA_URL,
                        u
                    )

                    it.putExtra(
                        "captureM3u8",
                        captureM3u8
                    )

                    MainActivity.navigatingInsideApp = true

                    ctx.startActivity(it)
                }

            } else {

                val it = Intent(
                    Intent.ACTION_VIEW,
                    Uri.parse(u)
                )

                ctx.startActivity(it)
            }

            if (
                monetagEnabledRemote &&
                monetagLinkRemote.isNotBlank()
            ) {

                view.postDelayed({

                    try {

                        val customTabsIntent =
                            CustomTabsIntent.Builder()
                                .setShowTitle(true)
                                .setUrlBarHidingEnabled(false)
                                .setToolbarColor(
                                    Color.parseColor("#202020")
                                )
                                .setColorScheme(
                                    CustomTabsIntent.COLOR_SCHEME_DARK
                                )
                                .build()

                        customTabsIntent.intent.addFlags(
                            Intent.FLAG_ACTIVITY_NO_HISTORY
                        )

                        customTabsIntent.intent.addFlags(
                            Intent.FLAG_ACTIVITY_CLEAR_TOP
                        )

                        customTabsIntent.intent.addFlags(
                            Intent.FLAG_ACTIVITY_SINGLE_TOP
                        )

                        customTabsIntent.launchUrl(
                            ctx,
                            Uri.parse(monetagLinkRemote)
                        )

                        view.postDelayed({

                            Toast.makeText(
                                ctx,
                                "Esta é uma página de anúncio.\nFeche no X ou use o botão Voltar.",
                                Toast.LENGTH_LONG
                            ).show()

                        }, 300)

                    } catch (e: Exception) {

                        e.printStackTrace()

                        Toast.makeText(
                            ctx,
                            "Não foi possível abrir o anúncio.",
                            Toast.LENGTH_SHORT
                        ).show()
                    }

                }, 1000)
            }

        } catch (e: Exception) {

            e.printStackTrace()

            Toast.makeText(
                ctx,
                "Erro ao abrir o canal.",
                Toast.LENGTH_SHORT
            ).show()
        }
    }
}

data class ButtonInfo(
    val name: String?,
    val url: String?,
    val captureM3u8: Boolean = false
)
