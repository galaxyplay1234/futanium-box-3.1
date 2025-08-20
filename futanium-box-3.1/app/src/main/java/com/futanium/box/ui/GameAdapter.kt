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

    /** guarda qual posição está expandida; -1 = nenhuma */
    private var expandedPos: Int = -1

    fun submit(newItems: List<Game>) {
        items.clear()
        items.addAll(newItems)
        expandedPos = -1
        notifyDataSetChanged()
    }

    inner class VH(v: View) : RecyclerView.ViewHolder(v) {
        val txtChampionship: TextView  = v.findViewById(R.id.txtChampionship)
        val imgHomeTeam: ImageView     = v.findViewById(R.id.imgHomeTeam)
        val txtHomeTeam: TextView      = v.findViewById(R.id.txtHomeTeam)
        val txtVs: TextView            = v.findViewById(R.id.txtVs)
        val txtVisitingTeam: TextView  = v.findViewById(R.id.txtVisitingTeam)
        val imgVisitingTeam: ImageView = v.findViewById(R.id.imgVisitingTeam)
        val txtTime: TextView          = v.findViewById(R.id.txtTime)

        // área de expansão
        val divider: View              = v.findViewById(R.id.divider)
        val groupButtons: LinearLayout = v.findViewById(R.id.groupButtons)
        val root: View                 = v // clique no card inteiro
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_game, parent, false)
        return VH(view)
    }

    override fun getItemCount() = items.size

    override fun onBindViewHolder(h: VH, position: Int) {
        val g = items[position]

        // topo
        h.txtChampionship.text = g.championship.orEmpty()

        // times
        h.txtHomeTeam.text = g.homeName.orEmpty()
        h.txtVisitingTeam.text = g.awayName.orEmpty()

        // hora: só o início (já vem pronto do parse)
        h.txtTime.text = g.time.orEmpty()

        // se você quiser esconder o "vs" e usar só a hora, comente a linha abaixo:
        h.txtVs.text = "vs"

        // logos
        h.imgHomeTeam.load(g.homeLogo) {
            crossfade(true)
            placeholder(android.R.drawable.stat_sys_download)
            error(android.R.drawable.ic_menu_report_image)
        }
        h.imgVisitingTeam.load(g.awayLogo) {
            crossfade(true)
            placeholder(android.R.drawable.stat_sys_download)
            error(android.R.drawable.ic_menu_report_image)
        }

        // ====== BOTÕES (expansão) ======
        val btns = g.buttons ?: emptyList()
        val hasButtons = btns.isNotEmpty()

        // estado expandido/fechado
        val isExpanded = (position == expandedPos) && hasButtons
        h.divider.visibility = if (isExpanded) View.VISIBLE else View.GONE
        h.groupButtons.visibility = if (isExpanded) View.VISIBLE else View.GONE

        // recria botões quando expandido
        if (isExpanded) {
            h.groupButtons.removeAllViews()
            btns.forEachIndexed { idx, anyBtn ->
                val (title, link) = extractTitleAndLink(anyBtn, idx)
                val b = Button(h.itemView.context).apply {
                    text = title
                    // estilo simples; você pode trocar por MaterialButton se quiser
                    setOnClickListener {
                        openLink(h.itemView, link)
                    }
                }
                h.groupButtons.addView(b)
            }
        } else {
            // fechado: não precisa manter botões prontos
            h.groupButtons.removeAllViews()
        }

        // clique no card: abre/fecha apenas se tiver botões
        h.root.setOnClickListener {
            if (!hasButtons) return@setOnClickListener

            val old = expandedPos
            expandedPos = if (position == expandedPos) -1 else position

            if (old != -1) notifyItemChanged(old)
            notifyItemChanged(position)
        }
    }

    /** Tenta extrair (nome, url) de um item de botão vindo da API
     *
     * Aceita formatos:
     *  - data class com props 'name' e 'url'
     *  - Map<String, Any?> com chaves 'name' e 'url'
     *  - org.json.JSONObject com 'name' e 'url'
     *  - Se 'name' vier como "Canal 1 go:espn1", transforma:
     *        título -> "Canal 1"
     *        link   -> "go:espn1"
     */
    @Suppress("UNCHECKED_CAST")
    private fun extractTitleAndLink(anyBtn: Any, index: Int): Pair<String, String> {
        var rawName: String? = null
        var rawUrl: String? = null

        when (anyBtn) {
            is ButtonInfo -> {
                rawName = anyBtn.name
                rawUrl = anyBtn.url
            }
            is Map<*, *> -> {
                rawName = anyBtn["name"]?.toString()
                rawUrl  = anyBtn["url"]?.toString()
            }
            is org.json.JSONObject -> {
                rawName = anyBtn.optString("name", null)
                rawUrl  = anyBtn.optString("url", null)
            }
            else -> {
                // fallback desconhecido: usa toString no name
                rawName = anyBtn.toString()
            }
        }

        // regra "Canal 1 go:espn1" => ("Canal 1", "go:espn1")
        var title = rawName?.trim().orEmpty()
        var link = rawUrl?.trim().orEmpty()

        // extrai "go:xyz" do final do nome, se existir
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

/** Opcional: se você tiver um modelo forte para botão. */
data class ButtonInfo(
    val name: String?,
    val url: String?
)