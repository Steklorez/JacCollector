package com.jaccollector

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.net.Inet4Address
import java.net.NetworkInterface

/** Settings screen, works with a TV remote (every control is a focusable button/checkbox). */
class MainActivity : Activity() {
    private val ui = Handler(Looper.getMainLooper())
    private lateinit var serverText: TextView
    private lateinit var deadlineBtn: Button
    private lateinit var sourcesBox: LinearLayout
    private lateinit var logText: TextView
    private val statusViews = HashMap<String, TextView>()

    private val ticker = object : Runnable {
        override fun run() {
            refreshStatus()
            ui.postDelayed(this, 2000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        CollectorService.start(this)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(32), dp(24), dp(32), dp(24))
            setBackgroundColor(Color.rgb(18, 24, 33))
        }
        root.addView(text("JacCollector", 26f, Color.WHITE))
        root.addView(text(
            "Собирает раздачи с публичных JacRed и отдаёт их плеерам на этой приставке.\n" +
                "В NUM: «Адрес Jackett» = http://127.0.0.1:${Store.PORT}, «API Key» — любой.",
            15f, Color.rgb(190, 200, 210)))
        serverText = text("", 15f, Color.rgb(120, 220, 140))
        root.addView(serverText)

        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        row.addView(button("Проверить источники") { probe() })
        deadlineBtn = button("") { nextDeadline() }
        row.addView(deadlineBtn)
        root.addView(row)

        root.addView(text("Источники", 20f, Color.WHITE).apply { setPadding(0, dp(16), 0, dp(4)) })
        sourcesBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(sourcesBox)

        val row2 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        row2.addView(button("Добавить источник") { addSourceDialog() })
        row2.addView(button("Вернуть стандартный список") {
            Store.saveSources(this, Store.DEFAULT_SOURCES)
            buildSources()
        })
        root.addView(row2)

        root.addView(text("Последние запросы", 20f, Color.WHITE).apply { setPadding(0, dp(16), 0, dp(4)) })
        logText = text("", 13f, Color.rgb(170, 180, 190))
        root.addView(logText)

        setContentView(ScrollView(this).apply { addView(root) })
        buildSources()
        updateDeadline()
    }

    override fun onResume() {
        super.onResume()
        ui.post(ticker)
    }

    override fun onPause() {
        ui.removeCallbacks(ticker)
        super.onPause()
    }

    // ---------- sources ----------

    private fun buildSources() {
        sourcesBox.removeAllViews()
        statusViews.clear()
        val list = Store.sources(this)
        list.forEachIndexed { index, src ->
            val line = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            val cb = CheckBox(this).apply {
                text = src.name + if (src.apiKey.isNotBlank()) "  (с ключом)" else ""
                textSize = 17f
                setTextColor(Color.WHITE)
                isChecked = src.enabled
                setOnCheckedChangeListener { _, checked ->
                    val cur = Store.sources(this@MainActivity).toMutableList()
                    if (index < cur.size) {
                        cur[index] = cur[index].copy(enabled = checked)
                        Store.saveSources(this@MainActivity, cur)
                    }
                }
            }
            line.addView(cb, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            line.addView(button("Удалить") {
                val cur = Store.sources(this).toMutableList()
                if (index < cur.size) {
                    cur.removeAt(index)
                    Store.saveSources(this, cur)
                    buildSources()
                }
            })
            sourcesBox.addView(line)
            val st = text(src.url, 13f, Color.rgb(150, 160, 170)).apply { setPadding(dp(40), 0, 0, dp(8)) }
            statusViews[src.name] = st
            sourcesBox.addView(st)
        }
        refreshStatus()
    }

    private fun addSourceDialog() {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(8), dp(16), 0)
        }
        val url = EditText(this).apply {
            hint = "адрес, например jacred.su или http://192.168.1.10:9117"
            inputType = InputType.TYPE_TEXT_VARIATION_URI
        }
        val key = EditText(this).apply { hint = "API key (только для своего Jackett, можно пусто)" }
        box.addView(url)
        box.addView(key)
        AlertDialog.Builder(this)
            .setTitle("Новый источник")
            .setView(box)
            .setPositiveButton("Добавить") { _, _ ->
                val u = url.text.toString().trim()
                if (u.isNotEmpty()) {
                    val base = Store.normalizeUrl(u)
                    val name = base.substringAfter("://")
                    val cur = Store.sources(this).toMutableList()
                    cur.add(Source(name, base, true, key.text.toString().trim()))
                    Store.saveSources(this, cur)
                    buildSources()
                }
            }
            .setNegativeButton("Отмена", null)
            .show()
    }

    private fun probe() {
        serverText.text = "Проверяю источники…"
        Thread {
            Aggregator.probe(applicationContext)
            ui.post { refreshStatus() }
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
        deadlineBtn.text = "Ждать источники: %.1f с".format(Store.deadlineMs(this) / 1000.0)
    }

    // ---------- status ----------

    private fun refreshStatus() {
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
                !src.enabled -> "выключен" to Color.rgb(120, 120, 120)
                st == null || st.lastAt == 0L -> "ещё не опрашивался" to Color.rgb(150, 160, 170)
                st.benchedUntil > now ->
                    "отдыхает ${(st.benchedUntil - now) / 60000 + 1} мин — ${st.lastError}" to Color.rgb(230, 120, 100)
                st.lastError != null -> "ошибка: ${st.lastError}" to Color.rgb(230, 170, 90)
                else -> "работает · ${st.lastCount} шт · ${st.lastMs} мс" to Color.rgb(120, 220, 140)
            }
            tv.text = "${src.url}  —  $msg"
            tv.setTextColor(color)
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

    private fun text(s: String, size: Float, color: Int) = TextView(this).apply {
        text = s
        textSize = size
        setTextColor(color)
        setPadding(0, dp(4), 0, dp(4))
    }

    private fun button(label: String, onClick: (View) -> Unit) = Button(this).apply {
        text = label
        isAllCaps = false
        setOnClickListener(onClick)
    }
}
