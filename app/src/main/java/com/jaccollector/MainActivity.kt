package com.jaccollector

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ImageView
import android.widget.Toast
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.net.Inet4Address
import java.net.NetworkInterface

/** Settings screen, works with a TV remote (every control is a focusable button/checkbox). */
class MainActivity : Activity() {
    companion object {
        const val BOOSTY = "https://boosty.to/steklorez"
        const val KOFI = "https://ko-fi.com/I3I0273OYI"
    }

    private val ui = Handler(Looper.getMainLooper())
    private lateinit var serverText: TextView
    private lateinit var deadlineBtn: Button
    private lateinit var sourcesBox: LinearLayout
    private lateinit var logText: TextView
    private lateinit var catalogBtn: Button
    private lateinit var syncInfo: TextView
    private lateinit var communityBox: CheckBox
    private lateinit var updateBtn: Button
    private var updatePulse: android.animation.ValueAnimator? = null
    private val statusViews = HashMap<String, TextView>()
    private val titleViews = HashMap<String, TextView>()

    private val ticker = object : Runnable {
        override fun run() {
            refreshStatus()
            ui.postDelayed(this, 2000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Tls.init(applicationContext) // Let's Encrypt root for Android < 7.1.1
        CollectorService.start(this)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(32), dp(24), dp(32), dp(24))
            setBackgroundColor(Color.rgb(18, 24, 33))
        }
        // title on the left, the "update" badge in the right corner (hidden until there is one)
        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        top.addView(text(SpannableStringBuilder("JacCollector").append("  ${BuildConfig.VERSION_NAME}").also {
            it.setSpan(ForegroundColorSpan(Color.rgb(140, 150, 160)), 12, it.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            it.setSpan(android.text.style.RelativeSizeSpan(0.6f), 12, it.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }, 26f, Color.WHITE).apply {
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        updateBtn = updateBadge()
        top.addView(updateBtn)
        root.addView(top)
        root.addView(text(
            "Собирает раздачи с публичных JacRed и отдаёт их плеерам на этой приставке.\n" +
                "В NUM: «Адрес Jackett» = http://127.0.0.1:${Store.PORT}, «API Key» — любой.",
            15f, Color.rgb(190, 200, 210)))
        serverText = text("", 15f, Color.rgb(120, 220, 140))
        root.addView(serverText)

        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        row.addView(button("♥ Поддержать автора") { donateDialog() })
        row.addView(button("➕ Добавить источник") { addSourceDialog() })
        catalogBtn = button("🌐 Общий список серверов") { catalogDialog() }
        row.addView(catalogBtn)
        root.addView(row)

        // "Sources" header with the connection test right next to the list it tests
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(16), 0, dp(4))
        }
        header.addView(text("Используемые источники", 20f, Color.WHITE).apply { setPadding(0, 0, dp(24), 0) })
        header.addView(button("📶 Проверить связь") { probe(it) })
        deadlineBtn = button("") { nextDeadline() }
        header.addView(deadlineBtn)
        root.addView(header)
        sourcesBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(sourcesBox)

        // ---------- settings ----------
        root.addView(text("Настройки", 20f, Color.WHITE).apply { setPadding(0, dp(24), 0, dp(4)) })
        communityBox = CheckBox(this).apply {
            text = "🌐 Пользоваться общим списком серверов"
            textSize = 15f
            setTextColor(Color.WHITE)
            isChecked = Store.shareEnabled(this@MainActivity)
            setOnClickListener { onCommunityToggle(isChecked) }
            styleCheckBox(this)
        }
        root.addView(communityBox)
        root.addView(text(
            "Включено: вы получаете серверы из общего списка, а взамен в общий список " +
                "уходят публичные адреса, которые вы добавляете (только рабочие), и отметки о том, " +
                "что сервер из общего списка перестал отвечать. Уходит только адрес сервера, сколько " +
                "на нём русскоязычных раздач, язык системы и анонимный номер установки от Firebase (без " +
                "регистрации и личных данных, нужен, чтобы каждую приставку учитывать один раз).\n" +
                "Выключено: ничего не отправляется и не скачивается, но и пользоваться можно только " +
                "личными серверами (домашними или с API-ключом).\n" +
                "Поисковые запросы, домашние адреса и ключи не отправляются никогда.\n" +
                "Обновления приложения проверяются на GitHub раз в сутки независимо от этой настройки.",
            12f, Color.GRAY))
        syncInfo = text("", 13f, Color.rgb(150, 160, 170))
        root.addView(syncInfo)
        // version and a manual update check (otherwise it happens by itself once a day)
        val ver = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(8), 0, 0)
        }
        ver.addView(text("Версия ${BuildConfig.VERSION_NAME}", 15f, Color.WHITE).apply { setPadding(0, 0, dp(24), 0) })
        ver.addView(button("🔄 Проверить обновления") { btn ->
            if (!Store.allow(this, "update-check", 10_000)) return@button
            btn.isEnabled = false
            Toast.makeText(this, "Проверяю обновления…", Toast.LENGTH_SHORT).show()
            Updater.checkNow(this) { msg ->
                ui.post {
                    btn.isEnabled = true
                    showUpdate()
                    Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
                }
            }
        })
        root.addView(ver)
        showSync()
        applyCommunityUi()

        // ---------- request log (very bottom) ----------
        root.addView(text("Последние запросы", 20f, Color.WHITE).apply { setPadding(0, dp(24), 0, dp(4)) })
        logText = text("", 13f, Color.rgb(170, 180, 190))
        root.addView(logText)

        setContentView(ScrollView(this).apply { addView(root) })
        buildSources()
        updateDeadline()
        // servers never checked in this session get a quick check, so status and languages show up
        Aggregator.probeNew(this, Store.sources(this).filter {
            (it.enabled || it.communityOff) && (Aggregator.states[it.name]?.lastAt ?: 0L) == 0L
        })
    }

    override fun onResume() {
        super.onResume()
        ui.post(ticker)
        Updater.checkInBackground(this, everyMs = 6 * 60 * 60 * 1000L) // also when the app is opened
    }

    override fun onPause() {
        ui.removeCallbacks(ticker)
        super.onPause()
    }

    // ---------- sources ----------

    private fun buildSources() {
        sourcesBox.removeAllViews()
        statusViews.clear()
        titleViews.clear()
        val list = Store.sources(this)
        list.forEachIndexed { index, src ->
            // One focusable card per server; OK opens the actions for exactly this server.
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                isFocusable = true
                isClickable = true
                setPadding(dp(16), dp(10), dp(16), dp(10))
                background = focusBackground(Color.rgb(30, 38, 48), dp(8))
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { setMargins(0, dp(4), 0, dp(4)) }
                setOnClickListener { sourceDialog(index) }
            }
            val mark = when {
                src.communityOff -> "⚠"
                src.enabled -> "✅"
                else -> "⛔"
            }
            val title = text(cardTitle(src, mark), 18f, if (src.enabled) Color.WHITE else Color.GRAY)
            titleViews[src.name] = title
            card.addView(title)
            val st = text(src.url, 13f, Color.rgb(150, 160, 170)).apply { setPadding(dp(36), 0, 0, 0) }
            statusViews[src.name] = st
            card.addView(st)
            sourcesBox.addView(card)
        }
        if (list.isEmpty()) sourcesBox.addView(text(
            if (Store.shareEnabled(this)) "Список пуст — добавьте источник или выберите серверы в общем списке."
            else "Список пуст — добавьте личный сервер или включите «Пользоваться общим списком» в настройках.",
            14f, Color.GRAY))
        refreshStatus()
    }

    /**
     * "✅  jacred.su   🇷🇺 🇺🇦": languages seen in the server's answers; until it answered,
     * the languages from the public list. 🔑 marks a server with an API key, 🏠 a personal one.
     */
    /**
     * Shared rating as a fixed-width column, so that it lines up from row to row:
     * "██████████  8/10  (35)". Bar green 7-10, orange 4-6, red 0-3; dark bar with "–" until
     * enough different boxes have reported. Numbers are monospace and padded.
     */
    private fun ratingText(r: Community.Rating?): CharSequence {
        val sb = SpannableStringBuilder()
        val known = r != null && r.boxes >= Community.MIN_BOXES
        val score = if (known) r!!.score else 0
        val color = when {
            !known -> Color.TRANSPARENT // keeps the column width, draws nothing
            score >= 7 -> Color.rgb(110, 210, 120)
            score >= 4 -> Color.rgb(240, 160, 60)
            else -> Color.rgb(230, 90, 80)
        }
        // solid blocks touch each other: a short even bar, drawn a bit smaller than the text
        sb.append("█".repeat(score))
        if (score > 0) sb.setSpan(ForegroundColorSpan(color), 0, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        val gray = sb.length
        sb.append("█".repeat(10 - score))
        sb.setSpan(ForegroundColorSpan(if (known) Color.rgb(70, 75, 80) else Color.TRANSPARENT),
            gray, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        sb.setSpan(android.text.style.RelativeSizeSpan(0.6f), 0, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        val num = sb.length
        sb.append((if (known) "$score/10" else "").padStart(6))
        sb.setSpan(ForegroundColorSpan(color), num, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        val cnt = sb.length
        sb.append((if (known) " (${r!!.boxes})" else "").padEnd(6))
        sb.setSpan(ForegroundColorSpan(Color.rgb(150, 155, 160)), cnt, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        sb.setSpan(android.text.style.TypefaceSpan("monospace"), num, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        return sb
    }

    /** "✅  ██████████  8/10  (35)  jacred.su  [рус]": the rating column right after the mark. */
    private fun cardTitle(src: Source, mark: String): CharSequence {
        val name = cardTitleText(src)
        if (!src.isPublic) return "$mark  $name"
        // no column at all until at least one server in the list has a rating
        val ratings = Community.ratings(this)
        val anyRated = Store.sources(this).any { s ->
            s.isPublic && (ratings[Store.normalizeUrl(s.url).lowercase()]?.boxes ?: 0) >= Community.MIN_BOXES
        }
        if (!anyRated) return "$mark  $name"
        val r = ratings[Store.normalizeUrl(src.url).lowercase()]
        return SpannableStringBuilder("$mark  ").append(ratingText(r)).append(" ").append(name)
    }

    private fun cardTitleText(src: Source): String {
        val seen = Aggregator.states[src.name]?.langs?.toList().orEmpty()
        val langs = seen.ifEmpty { src.lang.split(',').filter { it.isNotBlank() } }
        val tag = when {
            src.apiKey.isNotBlank() -> "  🔑"
            !src.isPublic && Community.isPersonal(src) -> "  🏠"
            else -> ""
        }
        val f = Store.flags(langs.sortedBy { listOf("ru", "uk", "en").indexOf(it).let { i -> if (i < 0) 9 else i } })
        return "${src.name}$tag" + if (f.isNotEmpty()) "     [$f]" else ""
    }

    /** Actions for one server; its name is in the title and on the delete button. */
    private fun sourceDialog(index: Int) {
        val cur = Store.sources(this).toMutableList()
        if (index >= cur.size) return
        val src = cur[index]
        val st = Aggregator.states[src.name]
        val info = buildString {
            append(src.url)
            if (st?.lastError != null) append("\nПоследняя ошибка: ${st.lastError}")
            else if (st != null && st.lastCount >= 0) append("\nПоследний ответ: ${st.lastCount} шт за ${st.lastMs} мс")
        }
        AlertDialog.Builder(this)
            .setTitle(src.name)
            .setMessage(info)
            .setPositiveButton(if (src.enabled) "Выключить" else "Включить") { _, _ ->
                // a manual choice overrides the community mark
                cur[index] = src.copy(enabled = !src.enabled, communityOff = false)
                Store.saveSources(this, cur)
                buildSources()
            }
            .setNegativeButton("Удалить ${src.name}") { _, _ ->
                Store.dismiss(this, src.url)
                cur.removeAt(index)
                Store.saveSources(this, cur)
                buildSources()
            }
            .setNeutralButton("Отмена", null)
            .show()
    }

    private fun addSourceDialog() {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(8), dp(16), 0)
        }
        val url = EditText(this).apply {
            hint = "адрес, например jacred.su"
            inputType = InputType.TYPE_TEXT_VARIATION_URI
        }
        // The API key is needed only for one's own Jackett, so it is hidden behind a checkbox.
        val key = EditText(this).apply {
            hint = "API key Jackett (можно пусто)"
            visibility = View.GONE
        }
        val shareNote = text("Публичные адреса попадают в общий список (отключить — в настройках внизу).",
            12f, Color.GRAY).apply {
            visibility = if (Store.shareEnabled(this@MainActivity)) View.VISIBLE else View.GONE
        }
        val own = CheckBox(this).apply {
            text = "Свой сервер (например Jackett дома)"
            textSize = 15f
            setOnCheckedChangeListener { _, checked ->
                key.visibility = if (checked) View.VISIBLE else View.GONE
                url.hint = if (checked) "адрес, например http://192.168.1.10:9117" else "адрес, например jacred.su"
                // own servers are never shared
                shareNote.visibility =
                    if (!checked && Store.shareEnabled(this@MainActivity)) View.VISIBLE else View.GONE
            }
        }
        box.addView(url)
        box.addView(own)
        box.addView(key)
        box.addView(shareNote)
        val community = Store.shareEnabled(this)
        if (!community) {
            // shared list is off: only personal servers
            own.isChecked = true
            own.visibility = View.GONE
            box.addView(text("Общий список выключен: можно добавить только личный сервер — " +
                "домашний адрес или сервер с API-ключом.", 12f, Color.GRAY))
        }
        AlertDialog.Builder(this)
            .setTitle("Новый источник")
            .setView(box)
            .setPositiveButton("Добавить") { _, _ ->
                val u = url.text.toString().trim()
                if (u.isNotEmpty()) {
                    val base = Store.normalizeUrl(u)
                    val name = base.substringAfter("://")
                    val cur = Store.sources(this).toMutableList()
                    val src = Source(name, base, true, if (own.isChecked) key.text.toString().trim() else "")
                    if (!community && !Community.isPersonal(src)) {
                        AlertDialog.Builder(this)
                            .setTitle("Это публичный сервер")
                            .setMessage("${src.name} — публичный адрес. Без общего списка можно " +
                                "добавлять только личные серверы (домашние или с API-ключом). " +
                                "Включите «Пользоваться общим списком» в настройках внизу.")
                            .setPositiveButton("Понятно", null)
                            .show()
                        return@setPositiveButton
                    }
                    cur.add(src)
                    Store.undismiss(this, src.url) // added again by hand: no longer "deleted"
                    Store.saveSources(this, cur)
                    buildSources()
                    Aggregator.probeNew(this, listOf(src))
                    if (!own.isChecked) Community.shareInBackground(this, src)
                }
            }
            .setNegativeButton("Отмена", null)
            .show()
    }

    // ---------- shared list ----------

    private fun showSync() {
        val t = Store.lastSync(this)
        syncInfo.text = "Общий список обновляется автоматически раз в сутки и когда вы его открываете" + if (t > 0)
            ", последний раз: " + java.text.SimpleDateFormat("dd.MM HH:mm", java.util.Locale.US)
                .format(java.util.Date(t)) else ""
    }

    /** Catalog, update and "standard list" exist only while the shared list is used. */
    private fun applyCommunityUi() {
        val on = Store.shareEnabled(this)
        val v = if (on) View.VISIBLE else View.GONE
        catalogBtn.visibility = v
        syncInfo.visibility = v
    }

    /** Either-or: switching off removes everything public, switching on brings it back. */
    private fun onCommunityToggle(on: Boolean) {
        if (on) {
            Store.setShareEnabled(this, true)
            applyCommunityUi()
            syncInfo.text = "Загружаю общий список…"
            Thread {
                Community.restorePublic(applicationContext)
                ui.post { buildSources(); showSync() }
            }.start()
            return
        }
        val publicCount = Store.sources(this).count { it.isPublic || !Community.isPersonal(it) }
        AlertDialog.Builder(this)
            .setTitle("Отключить общий список?")
            .setMessage("Публичные серверы ($publicCount шт) будут убраны из вашего списка. Останутся " +
                "только личные: домашние адреса и серверы с API-ключом. Новые публичные серверы " +
                "добавлять будет нельзя, пока общий список выключен.")
            .setPositiveButton("Отключить") { _, _ ->
                Store.setShareEnabled(this, false)
                Community.keepOnlyPersonal(this)
                applyCommunityUi()
                buildSources()
            }
            .setNegativeButton("Отмена") { _, _ -> communityBox.isChecked = true }
            .setOnCancelListener { communityBox.isChecked = true }
            .show()
    }

    /** The whole public list: add or remove any server. */
    private var catalogLoading = false

    private fun catalogDialog() {
        // one load at a time; the catalog itself is downloaded at most every 10 minutes
        if (catalogLoading) return
        catalogLoading = true
        Thread {
            // opening the catalog also applies the public list (new recommended servers,
            // dead ones switched off); the download itself happens at most every 10 minutes
            val fresh = System.currentTimeMillis() - Store.catalogTime(applicationContext) >= 10 * 60 * 1000L
            if (fresh) Community.syncNow(applicationContext)
            val cat = Community.catalog(applicationContext)
            ui.post {
                catalogLoading = false
                buildSources()
                showSync()
                if (cat == null || cat.entries.isEmpty()) {
                    Toast.makeText(this, "Общий список недоступен, попробуйте позже", Toast.LENGTH_LONG).show()
                    return@post
                }
                val myLang = Community.deviceLang()
                val ratings = Community.ratings(this)
                fun rating(e: Community.Entry) = ratings[e.url.lowercase()]?.takeIf { it.boxes >= Community.MIN_BOXES }
                // language first (own language on top), then the best rated; dead and unrated last
                val entries = cat.entries
                    .filter { it.url.lowercase() !in cat.removed }
                    .sortedWith(compareBy<Community.Entry>(
                        { Store.priority(Source(it.name, it.url, lang = it.lang), myLang) },
                        { if (it.dead) 1 else 0 },
                        { -(rating(it)?.score ?: -1) },
                        { -(rating(it)?.boxes ?: 0) }))
                val have = Store.sources(this).map { Store.normalizeUrl(it.url).lowercase() }.toSet()
                val anyRated = entries.any { rating(it) != null }
                val labels: Array<CharSequence> = entries.map { e ->
                    val mark = if (e.url.lowercase() in have) "✅" else "➕"
                    val star = if (e.recommended) "  ⭐" else ""
                    val dead = if (e.dead) "  💀" else ""
                    SpannableStringBuilder("$mark  ")
                        .append(if (anyRated) SpannableStringBuilder(ratingText(rating(e))).append(" ") else "")
                        .append("${e.name}   [${Store.flags(e.lang.split(','))}]$star$dead")
                }.toTypedArray()
                val dlg = AlertDialog.Builder(this)
                    .setItems(labels) { _, i ->
                        val e = entries[i]
                        if (e.url.lowercase() in have) {
                            val cur = Store.sources(this).toMutableList()
                            cur.removeAll { Store.normalizeUrl(it.url).equals(e.url, true) }
                            Store.dismiss(this, e.url)
                            Store.saveSources(this, cur)
                        } else {
                            Community.addFromCatalog(this, e)
                        }
                        buildSources()
                    }
                    .setNegativeButton("Закрыть", null)
                    .create()
                // just the list; a small legend at the bottom explains the marks
                dlg.listView.addFooterView(text(
                    "✅ используется · ➕ добавить · ⭐ рекомендуем · 💀 не работает · " +
                        "оценка 0–10, в скобках — сколько приставок отметилось",
                    12f, Color.rgb(150, 160, 170)).apply { setPadding(dp(24), dp(12), dp(24), dp(4)) },
                    null, false)
                dlg.show()
            }
        }.start()
    }

    // ---------- donations ----------

    /** TV boxes usually have no browser, so each link also comes as a QR code for a phone. */
    private fun donateDialog() {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(dp(16), dp(16), dp(16), dp(8))
        }
        fun column(title: String, url: String, qr: Int) = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(24), 0, dp(24), 0)
            addView(text(title, 18f, Color.WHITE).apply { gravity = Gravity.CENTER })
            addView(ImageView(this@MainActivity).apply {
                setImageResource(qr)
                layoutParams = LinearLayout.LayoutParams(dp(180), dp(180))
            })
            addView(text(url.removePrefix("https://"), 13f, Color.LTGRAY).apply { gravity = Gravity.CENTER })
            addView(button("Открыть") { openLink(url) })
        }
        box.addView(column("Boosty", BOOSTY, R.drawable.qr_boosty))
        box.addView(column("Ko-fi", KOFI, R.drawable.qr_kofi))
        val wrap = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(text("Наведите камеру телефона на QR-код. Спасибо!", 14f, Color.LTGRAY).apply {
                gravity = Gravity.CENTER
                setPadding(dp(16), dp(8), dp(16), 0)
            })
            addView(box)
        }
        AlertDialog.Builder(this)
            .setTitle("Поддержать автора")
            .setView(wrap)
            .setNegativeButton("Закрыть", null)
            .show()
    }

    private fun openLink(url: String) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (e: Exception) {
            Toast.makeText(this, "На этом устройстве нет браузера — отсканируйте QR-код телефоном",
                Toast.LENGTH_LONG).show()
        }
    }

    private fun probe(btn: View) {
        // anti-spam: every 30 s at most, button off while checking
        if (!Store.allow(this, "probe_btn", 30_000)) {
            Toast.makeText(this, "Проверка только что была, можно снова через " +
                "${Store.waitSeconds(this, "probe_btn", 30_000)} с", Toast.LENGTH_SHORT).show()
            return
        }
        btn.isEnabled = false
        serverText.text = "Проверяю источники…"
        Thread {
            Aggregator.probe(applicationContext)
            ui.post { btn.isEnabled = true; refreshStatus() }
        }.start()
    }

    private fun nextDeadline() {
        val all = Store.DEADLINES
        val cur = Store.deadlineMs(this)
        val next = all[(all.indexOf(cur) + 1) % all.size]
        Store.setDeadlineMs(this, next)
        updateDeadline()
    }

    private fun updateDeadline() {
        deadlineBtn.text = "⏱ Ждать источники: %.1f с".format(Store.deadlineMs(this) / 1000.0)
    }

    // ---------- status ----------

    private fun refreshStatus() {
        showUpdate()
        val srv = CollectorService.server
        serverText.text = when {
            srv?.error != null -> "Ошибка сервера: ${srv.error}"
            srv?.running == true -> "Сервер работает: http://127.0.0.1:${Store.PORT}" +
                (lanIp()?.let { "   ·   в домашней сети: http://$it:${Store.PORT}" } ?: "")
            else -> "Сервер запускается…"
        }
        val now = System.currentTimeMillis()
        for (src in Store.sources(this)) {
            val tv = statusViews[src.name] ?: continue
            val st = Aggregator.states[src.name]
            val (msg, color) = when {
                src.communityOff -> "не работает по данным других пользователей" to Color.rgb(200, 110, 100)
                !src.enabled -> "выключен" to Color.rgb(120, 120, 120)
                st != null && st.rateLimitedUntil > now ->
                    "отдыхает ${(st.rateLimitedUntil - now) / 60000 + 1} мин — сервер просит подождать (HTTP 429)" to
                        Color.rgb(230, 120, 100)
                st == null || st.lastAt == 0L -> "ещё не опрашивался" to Color.rgb(150, 160, 170)
                st.benchedUntil > now ->
                    "отдыхает ${(st.benchedUntil - now) / 60000 + 1} мин — ${st.lastError}" to Color.rgb(230, 120, 100)
                st.lastError != null -> "ошибка: ${st.lastError}" to Color.rgb(230, 170, 90)
                else -> "работает · ${st.lastCount} шт · ${st.lastMs} мс" to Color.rgb(120, 220, 140)
            }.let { (m, c) ->
                // careful mode: this server dislikes bursts, so requests to it are spaced out
                val gap = st?.gapMs ?: 0
                (if (gap > 0) "$m · бережно: не чаще раза в ${(gap + 999) / 1000} с" else m) to c
            }
            tv.text = "${src.url}  —  $msg"
            tv.setTextColor(color)
            val mark = when { src.communityOff -> "⚠"; src.enabled -> "✅"; else -> "⛔" }
            titleViews[src.name]?.text = cardTitle(src, mark)
        }
        logText.text = RequestLog.all().joinToString("\n").ifEmpty { "пока ничего" }
    }

    private fun lanIp(): String? = try {
        NetworkInterface.getNetworkInterfaces().toList()
            .flatMap { it.inetAddresses.toList() }
            .firstOrNull { it is Inet4Address && !it.isLoopbackAddress && it.isSiteLocalAddress }
            ?.hostAddress
    } catch (e: Exception) {
        null
    }

    // ---------- helpers ----------

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun text(s: CharSequence, size: Float, color: Int) = TextView(this).apply {
        text = s
        textSize = size
        setTextColor(color)
        setPadding(0, dp(4), 0, dp(4))
    }

    private fun button(label: String, onClick: (View) -> Unit) = Button(this).apply {
        text = label
        isAllCaps = false
        textSize = 16f
        setTextColor(Color.WHITE)
        setPadding(dp(20), dp(10), dp(20), dp(10))
        background = focusBackground(Color.rgb(58, 66, 78), dp(6))
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { setMargins(0, dp(6), dp(12), dp(6)) }
        setOnClickListener(onClick)
        growOnFocus(this)
    }

    /**
     * TV remotes need an obvious focus: bright blue with a white frame when focused,
     * [normal] otherwise.
     */
    private fun focusBackground(normal: Int, radius: Int): StateListDrawable {
        fun shape(fill: Int, stroke: Int, strokeW: Int) = GradientDrawable().apply {
            cornerRadius = radius.toFloat()
            setColor(fill)
            if (strokeW > 0) setStroke(strokeW, stroke)
        }
        val focused = shape(Color.rgb(30, 136, 229), Color.WHITE, dp(3))
        return StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_focused), focused)
            addState(intArrayOf(android.R.attr.state_pressed), focused)
            addState(intArrayOf(), shape(normal, 0, 0))
        }
    }

    // ---------- self-update ----------

    /** Blinking badge "⬆ Обновить до X": colors flow between orange and green. */
    private fun updateBadge(): Button {
        val fill = GradientDrawable().apply { cornerRadius = dp(18).toFloat(); setColor(Color.rgb(245, 124, 0)) }
        val focused = GradientDrawable().apply {
            cornerRadius = dp(18).toFloat()
            setColor(Color.rgb(30, 136, 229))
            setStroke(dp(3), Color.WHITE)
        }
        val b = button("") { onUpdatePressed() }
        b.background = StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_focused), focused)
            addState(intArrayOf(android.R.attr.state_pressed), focused)
            addState(intArrayOf(), fill)
        }
        b.visibility = View.GONE
        updatePulse = android.animation.ValueAnimator.ofArgb(Color.rgb(245, 124, 0), Color.rgb(67, 160, 71)).apply {
            duration = 900
            repeatMode = android.animation.ValueAnimator.REVERSE
            repeatCount = android.animation.ValueAnimator.INFINITE
            addUpdateListener { fill.setColor(it.animatedValue as Int) }
        }
        return b
    }

    private fun showUpdate() {
        val r = Updater.ready(this)
        if (r == null) {
            if (updateBtn.visibility != View.GONE) { updateBtn.visibility = View.GONE; updatePulse?.cancel() }
            return
        }
        updateBtn.text = "⬆ Обновить до ${r.version}"
        if (updateBtn.visibility != View.VISIBLE) {
            updateBtn.visibility = View.VISIBLE
            updatePulse?.start()
        }
    }

    private fun onUpdatePressed() {
        val hint = try { Updater.install(this) } catch (e: Exception) { "Не удалось начать установку: ${e.message}" }
        if (hint != null) Toast.makeText(this, hint, Toast.LENGTH_LONG).show()
    }

    private fun growOnFocus(v: View) {
        v.setOnFocusChangeListener { view, has ->
            val s = if (has) 1.06f else 1f
            view.animate().scaleX(s).scaleY(s).setDuration(120).start()
        }
    }

    /** Checkbox rows get the same visible focus as buttons. */
    private fun styleCheckBox(cb: CheckBox) {
        cb.background = focusBackground(Color.TRANSPARENT, dp(6))
        cb.setPadding(dp(8), dp(6), dp(16), dp(6))
    }
}
