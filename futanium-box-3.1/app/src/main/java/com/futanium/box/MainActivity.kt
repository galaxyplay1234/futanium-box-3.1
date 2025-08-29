package com.futanium.box

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.animation.AnimatorListenerAdapter
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.InsetDrawable
import android.graphics.drawable.RippleDrawable
import android.graphics.drawable.ShapeDrawable
import android.graphics.drawable.shapes.OvalShape
import android.util.TypedValue
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.view.animation.LinearInterpolator
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.AppCompatImageView
import androidx.core.content.FileProvider
import androidx.core.view.MenuItemCompat
import androidx.recyclerview.widget.LinearLayoutManager
import com.futanium.box.databinding.ActivityMainBinding
import com.futanium.box.model.Game
import com.futanium.box.ui.GameAdapter
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import android.os.Bundle
import android.content.pm.PackageManager
import androidx.appcompat.app.AlertDialog
import android.widget.ProgressBar
import android.widget.LinearLayout

class MainActivity : AppCompatActivity() {

    private lateinit var vb: ActivityMainBinding
    private val client = OkHttpClient()
    private val adapter = GameAdapter()

    private val API_URL = "http://91.108.124.236:8080/games/api"

    // referências do botão de refresh
    private var refreshItem: MenuItem? = null
    private var refreshView: AppCompatImageView? = null

    // controle do giro
    private val SPIN_TAG_KEY = 0x13572468
    private var spinCompletedOne = false
    private var spinPendingStop = false

    // animator contínuo e contador de ciclos
    private var spinAnimator: ObjectAnimator? = null
    private var spinRepeats: Int = 0

    // --- Auto-update (fora da Play) ---
    private var pendingApkUri: Uri? = null
    private var downloadingDialog: AlertDialog? = null

    // Resultado da tela de permitir “Apps desconhecidos”
    private val unknownSourcesLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { _ ->
        pendingApkUri?.let { uri ->
            if (canInstallUnknownSources()) {
                startApkInstall(uri)
            } else {
                Toast.makeText(this, "Permita instalar apps deste fonte para atualizar.", Toast.LENGTH_LONG).show()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        vb = ActivityMainBinding.inflate(layoutInflater)
        setContentView(vb.root)

        setSupportActionBar(vb.toolbar)

        // Status bar #10131C
        window.statusBarColor = Color.parseColor("#10131C")

        // Sombra leve na toolbar
        vb.toolbar.elevation = 6f

        // Ícone da direita afastado da borda
        vb.toolbar.contentInsetEndWithActions = dp(44)

        // Título menor e em negrito
        vb.toolbar.post {
            for (i in 0 until vb.toolbar.childCount) {
                val child = vb.toolbar.getChildAt(i)
                if (child is TextView) {
                    child.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
                    child.typeface = Typeface.DEFAULT_BOLD
                    break
                }
            }
        }

        // Texto "Jogos de Hoje - dd/MM" no chip
        val df = java.text.SimpleDateFormat("dd/MM", java.util.Locale.getDefault())
        vb.todayChipText.text = "Jogos de Hoje - ${df.format(java.util.Date())}"

        // Garante que o rail fica por cima e a lista passa por baixo
        vb.todayRail.bringToFront()
        vb.root.post {
            val topSpace = vb.todayRail.bottom + dp(4)
            vb.rvGames.setPadding(
                vb.rvGames.paddingLeft,
                topSpace,
                vb.rvGames.paddingRight,
                vb.rvGames.paddingBottom
            )

            val start = vb.todayRail.bottom + dp(6)
            val end   = start + dp(44)
            vb.swipe.setProgressViewOffset(true, start, end)
        }

        vb.rvGames.layoutManager = LinearLayoutManager(this)
        vb.rvGames.adapter = adapter

        adapter.onOpenLink = { url, title, referer, ua ->
            LinkHelper.openLinkSmart(this, url, title, referer, ua)
        }

        vb.swipe.setOnRefreshListener {
            if (!vb.swipe.isRefreshing) vb.swipe.isRefreshing = true
            startRefreshSpin()
            fetchGames(onFinally = { stopRefreshSpin() })
        }

        fetchGames()

        // Verifica se há update disponível (fora da Play)
        checkAppUpdateExternal(
            metaUrl = "https://raw.githubusercontent.com/galaxyplay1234/futanium-box-3.1/refs/heads/main/update.json",
            showNoUpdateToast = false
        )
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.main_menu, menu)
        refreshItem = menu.findItem(R.id.action_refresh)

        val size = obtainActionBarSize()
        val iv = AppCompatImageView(this).apply {
            layoutParams = ViewGroup.LayoutParams(size, size)
            setImageDrawable(refreshItem!!.icon)
            scaleType = ImageView.ScaleType.CENTER

            val rippleColor = ColorStateList.valueOf(Color.parseColor("#33FFFFFF"))
            val inset = dp(5)
            val mask = InsetDrawable(ShapeDrawable(OvalShape()), inset, inset, inset, inset)
            background = RippleDrawable(rippleColor, null, mask)
            setPadding(dp(8), dp(8), dp(8), dp(8))

            contentDescription = refreshItem!!.title
            isClickable = true
            isFocusable = true

            setOnClickListener { onOptionsItemSelected(refreshItem!!) }
        }
        MenuItemCompat.setActionView(refreshItem, iv)
        refreshView = iv

        refreshView?.apply {
            setLayerType(View.LAYER_TYPE_HARDWARE, null)
            postOnAnimation { setLayerType(View.LAYER_TYPE_NONE, null) }
        }

        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_refresh -> {
                startRefreshSpin()
                if (!vb.swipe.isRefreshing) vb.swipe.isRefreshing = true
                fetchGames(onFinally = { stopRefreshSpin() })
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    private fun fetchGames(onFinally: (() -> Unit)? = null) {
        if (!vb.swipe.isRefreshing) vb.swipe.isRefreshing = true

        Thread {
            try {
                val req = Request.Builder().url(API_URL).build()
                val res = client.newCall(req).execute()
                val body = res.body?.string() ?: "[]"

                val games = parseGames(body)
                runOnUiThread {
                    (vb.rvGames.adapter as GameAdapter).submit(games)

                    if (games.isEmpty()) {
                        vb.rvGames.visibility = View.GONE
                        vb.emptyView.visibility = View.VISIBLE
                    } else {
                        vb.rvGames.visibility = View.VISIBLE
                        vb.emptyView.visibility = View.GONE
                    }
                }
            } catch (e: Exception) {
                runOnUiThread {
                    Toast.makeText(this, "Erro ao carregar: ${e.message}", Toast.LENGTH_LONG).show()
                }
            } finally {
                runOnUiThread {
                    vb.swipe.isRefreshing = false
                    onFinally?.invoke()
                }
            }
        }.start()
    }

    /** Verifica JSON remoto e, se houver versão maior, mostra popup e permite baixar */
    private fun checkAppUpdateExternal(metaUrl: String, showNoUpdateToast: Boolean = false) {
        Thread {
            try {
                val req = Request.Builder().url(metaUrl).build()
                val res = OkHttpClient().newCall(req).execute()
                val body = res.body?.string().orEmpty()
                if (body.isBlank()) return@Thread

                val obj = JSONObject(body)
val remoteCode = obj.optInt("versionCode", -1)
val apkUrl     = obj.optString("apkUrl", "")
val title      = obj.optString("title", "Nova versão disponível")
val changelog  = obj.optString("changelog", "")

if (remoteCode > currentVersionCode()) {
    runOnUiThread { showUpdateAvailableDialog(title, changelog, apkUrl) }
} else if (showNoUpdateToast) {
    runOnUiThread {
        Toast.makeText(this, "Você já está na última versão.", Toast.LENGTH_SHORT).show()
    }
}
            } catch (_: Exception) {
                if (showNoUpdateToast) {
                    runOnUiThread {
                        Toast.makeText(this, "Falha ao checar atualização.", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }.start()
    }

    private fun showUpdateAvailableDialog(title: String, changelog: String, apkUrl: String) {
    val msg = if (changelog.isNotBlank()) changelog else "Há uma nova versão disponível."

    val dialog = AlertDialog.Builder(this)
        .setTitle(title.ifBlank { "Nova versão disponível" })
        .setMessage(msg)
        .setIcon(applicationInfo.icon) // <<< ícone do app
        .setNegativeButton("CANCELAR", null)
        .setPositiveButton("BAIXAR") { _, _ ->
            downloadAndPromptInstall(apkUrl)
        }
        .create()

    dialog.setOnShowListener {
        val c = getColor(R.color.menuColor)

        dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.setTextColor(c)
        dialog.getButton(AlertDialog.BUTTON_NEGATIVE)?.setTextColor(c)
    }

    dialog.show()
}

    // --- Diálogo indeterminado "Baixando..." ---
    private fun showDownloadingDialog(): AlertDialog {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(20), dp(24), dp(8))
        }
        val pb = ProgressBar(this).apply { isIndeterminate = true }
        val tv = TextView(this).apply {
            text = "Baixando atualização…"
            setPadding(0, dp(12), 0, 0)
        }
        container.addView(pb)
        container.addView(tv)

        return AlertDialog.Builder(this)
            .setTitle("Atualizando")
            .setView(container)
            .setCancelable(false)
            .create()
            .also { it.show() }
    }

    private fun downloadAndPromptInstall(apkUrl: String) {
        // Mostra diálogo "Baixando..."
        downloadingDialog?.dismiss()
        downloadingDialog = showDownloadingDialog()

        Thread {
            try {
                val client = OkHttpClient()
                val res = client.newCall(Request.Builder().url(apkUrl).build()).execute()
                val body = res.body ?: throw IllegalStateException("Sem corpo na resposta")

                val dir = File(cacheDir, "apks").apply { mkdirs() }
                val file = File(dir, "update.apk")
                body.byteStream().use { input ->
                    file.outputStream().use { output ->
                        input.copyTo(output)
                    }
                }

                val uri = FileProvider.getUriForFile(
                    this, "$packageName.fileprovider", file
                )
                runOnUiThread {
                    downloadingDialog?.dismiss()
                    downloadingDialog = null
                    prepareInstall(uri)
                }
            } catch (e: Exception) {
                runOnUiThread {
                    downloadingDialog?.dismiss()
                    downloadingDialog = null
                    Toast.makeText(this, "Erro ao baixar atualização.", Toast.LENGTH_LONG).show()
                }
            }
        }.start()
    }

    private fun prepareInstall(uri: Uri) {
        pendingApkUri = uri
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (!canInstallUnknownSources()) {
                try {
                    val intent = Intent(
                        Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                        Uri.parse("package:$packageName")
                    )
                    unknownSourcesLauncher.launch(intent)
                } catch (e: ActivityNotFoundException) {
                    val i = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                    i.data = Uri.parse("package:$packageName")
                    startActivity(i)
                    Toast.makeText(this, "Habilite 'Apps desconhecidos' para atualizar.", Toast.LENGTH_LONG).show()
                }
                return
            }
        }
        startApkInstall(uri)
    }

    private fun startApkInstall(uri: Uri) {
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        try {
            startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(this, "Não foi possível iniciar a instalação.", Toast.LENGTH_LONG).show()
        }
    }

    private fun canInstallUnknownSources(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            try {
                packageManager.canRequestPackageInstalls()
            } catch (_: SecurityException) {
                false
            }
        } else {
            true
        }
    }

    private fun isOnline(): Boolean {
    val cm = getSystemService(android.content.Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
    val net = cm.activeNetwork ?: return false
    val caps = cm.getNetworkCapabilities(net) ?: return false
    return caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI)
            || caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_CELLULAR)
            || caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_ETHERNET)
}

private fun showOfflineDialog(onRetry: (() -> Unit)? = null) {
    val d = androidx.appcompat.app.AlertDialog.Builder(this)
        .setTitle("Sem conexão")
        .setMessage("Verifique sua internet e tente novamente.")
        .setNegativeButton("Configurar Wi-Fi") { _, _ ->
            startActivity(android.content.Intent(android.provider.Settings.ACTION_WIFI_SETTINGS))
        }
        .setPositiveButton("Tentar novamente") { _, _ ->
            if (isOnline()) onRetry?.invoke() else showOfflineDialog(onRetry)
        }
        .create()

    d.setOnShowListener {
        val c = getColor(R.color.menuColor)
        d.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE)?.setTextColor(c)
        d.getButton(androidx.appcompat.app.AlertDialog.BUTTON_NEGATIVE)?.setTextColor(c)
    }
    d.show()
}

    private fun parseGames(json: String): List<Game> {
        val arr = JSONArray(json)
        val list = ArrayList<Game>(arr.length())

        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            if (o.has("header")) continue

            val championship         = o.optString("championship", "")
            val championshipImageUrl = o.optString("championship_image_url", null)
            val startTime            = o.optString("start_time", "")
            val homeTeam             = o.optString("home_team", "")
            val visitingTeam         = o.optString("visiting_team", "")
            val homeLogo             = o.optString("home_team_image_url", null)
            val visitingLogo         = o.optString("visiting_team_image_url", null)
            val isLive               = o.optBoolean("is_live", false)
            val isFinished           = o.optBoolean("is_finished", false)

            val buttonsList: List<Any>? = o.optJSONArray("buttons")?.let { ja ->
                val tmp = ArrayList<Any>(ja.length())
                for (j in 0 until ja.length()) tmp += ja.get(j)
                tmp
            }

            list += Game(
                championship = championship,
                championshipImageUrl = championshipImageUrl,
                homeName = homeTeam,
                homeLogo = homeLogo,
                awayName = visitingTeam,
                awayLogo = visitingLogo,
                time = startTime,
                isLive = isLive,
                isFinished = isFinished,
                buttons = buttonsList
            )
        }
        return list
    }

    // -------- animação suave do refresh (mín. 1 volta, sem “voltar”) --------

    private fun startRefreshSpin() {
        val v = refreshView ?: return
        if (v.getTag(SPIN_TAG_KEY) == true) return

        v.animate().cancel()
        spinAnimator?.cancel()
        spinAnimator = null
        spinRepeats = 0
        spinCompletedOne = false
        spinPendingStop = false

        v.setTag(SPIN_TAG_KEY, true)
        v.setLayerType(View.LAYER_TYPE_HARDWARE, null)

        val base = ((v.rotation % 360f) + 360f) % 360f
        v.rotation = base

        spinAnimator = ObjectAnimator.ofFloat(v, View.ROTATION, base, base + 360f).apply {
            duration = 1100
            interpolator = LinearInterpolator()
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.RESTART
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationRepeat(animation: android.animation.Animator) {
                    spinRepeats++
                    spinCompletedOne = true
                    if (spinPendingStop && spinRepeats >= 1) {
                        animation.cancel()
                        finishToSnap(v)
                    }
                }
            })
            start()
        }
    }

    private fun stopRefreshSpin() {
        val v = refreshView ?: return
        if (v.getTag(SPIN_TAG_KEY) != true) return

        if (!spinCompletedOne) {
            spinPendingStop = true
            return
        }

        spinAnimator?.cancel()
        spinAnimator = null
        finishToSnap(v)
    }

    private fun finishToSnap(v: View) {
        v.setTag(SPIN_TAG_KEY, false)

        val current = ((v.rotation % 360f) + 360f) % 360f
        val remaining = if (current == 0f) 0f else 360f - current
        if (remaining > 0f) {
            val dur = (remaining / 360f * 240).toLong().coerceAtLeast(100L)
            v.animate()
                .rotationBy(remaining)
                .setDuration(dur)
                .setInterpolator(LinearInterpolator())
                .withEndAction {
                    v.rotation = 0f
                    v.setLayerType(View.LAYER_TYPE_NONE, null)
                }
                .start()
        } else {
            v.rotation = 0f
            v.setLayerType(View.LAYER_TYPE_NONE, null)
        }

        spinRepeats = 0
        spinCompletedOne = false
        spinPendingStop = false
    }

    // -----------------------------------

    private fun obtainActionBarSize(): Int {
        val tv = TypedValue()
        var size = dp(48)
        if (theme.resolveAttribute(android.R.attr.actionBarSize, tv, true)) {
            size = TypedValue.complexToDimensionPixelSize(tv.data, resources.displayMetrics)
        }
        return size
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    // -------- helper para obter o versionCode sem BuildConfig --------
    private fun currentVersionCode(): Int {
        return try {
            val pm = packageManager
            val pi = if (Build.VERSION.SDK_INT >= 33) {
                pm.getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION")
                pm.getPackageInfo(packageName, 0)
            }
            if (Build.VERSION.SDK_INT >= 28) {
                pi.longVersionCode.toInt()
            } else {
                @Suppress("DEPRECATION")
                pi.versionCode
            }
        } catch (_: Exception) { 0 }
    }
}