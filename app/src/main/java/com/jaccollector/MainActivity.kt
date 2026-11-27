package com.jaccollector

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.PorterDuff
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.net.Inet4Address
import java.net.NetworkInterface

/**
 * The whole app on one screen with a side menu: Support, Active servers, Load (shared list and
 * its on/off switch), Request log, Updates.
 * Built for a TV remote: everything is a focusable row or button with a clear white frame.
 * Sizes are in dp, so a 1080p and a 4K TV look the same. On a narrow (phone) screen the layout
 * is a phone one: app bar with "Support" on top, the menu at the bottom, tables become cards,
 * hints say "tap" instead of "OK".
 */
class MainActivity : Activity() {
    companion object {
        const val BOOSTY = "https://boosty.to/steklorez"
        const val KOFI = "https://ko-fi.com/I3I0273OYI"

        val BG = Color.rgb(11, 13, 16)
        val PANEL = Color.rgb(18, 21, 26)
        val PANEL2 = Color.rgb(25, 30, 37)
        val LINE = Color.rgb(34, 40, 49)
        val TEXT = Color.rgb(230, 233, 238)
        val MUTED = Color.rgb(138, 147, 160)
        val DIM = Color.rgb(92, 101, 112)
        val ACCENT = Color.rgb(79, 140, 255)
        val OK = Color.rgb(60, 207, 142)
        val WARN = Color.rgb(240, 180, 41)
        val BAD = Color.rgb(239, 93, 93)

        private const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
        private const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
    }

    private enum class Page { SOURCES, CATALOG, LOG, UPDATES }

    private val ui = Handler(Looper.getMainLooper())
    private var page = Page.SOURCES
    /** Phone-like screen: menu on top, rows in two lines. */
    private var narrow = false
    private val medium: Typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)

    private lateinit var rail: ViewGroup
    private lateinit var content: FrameLayout
    private val navItems = HashMap<Page, View>()
    private val navIcons = HashMap<Page, ImageView>()
    private val navCounts = HashMap<Page, TextView>()
    private lateinit var updateCard: View
    private lateinit var updateTitle: TextView

    // views of the page on screen (null when another page is shown)
    private var serverDot: View? = null
    private var serverAddr: TextView? = null
    private var serverLan: TextView? = null
    private var workValue: TextView? = null
    private var foundValue: TextView? = null
    private var deadlineValue: TextView? = null
    private var checkedText: TextView? = null
    private var sourcesList: LinearLayout? = null
    private var logText: TextView? = null
    private var syncInfo: TextView? = null
    private var switchPill: TextView? = null
    private var switchRow: View? = null
    private var shareOnText: TextView? = null
    private var shareOffText: TextView? = null
    private var installRow: View? = null
    private var installTitle: TextView? = null
    private var catalogList: LinearLayout? = null
    private var catalogChips: List<TextView> = emptyList()
    private var catalogFooter: TextView? = null

    private class Row(
        val dot: View, val name: TextView, val sub: TextView, val langs: LinearLayout?,
        val count: TextView?, val barFill: View?, val lat: TextView?, val state: TextView,
    ) { var langKey = "" }

    private val rows = HashMap<String, Row>()

    private var catalog: Community.Catalog? = null
    private var catalogLoading = false
    private var catalogFilter = 0

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
        window.decorView.setBackgroundColor(BG)
        narrow = resources.configuration.screenWidthDp < 640

        val root = RootLayout(this).apply {
            orientation = if (narrow) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL
            setBackgroundColor(if (narrow) PANEL else BG)
            // Android 15 draws apps under the status and gesture bars: keep the content out of them
            setOnApplyWindowInsetsListener { v, ins ->
                @Suppress("DEPRECATION")
                v.setPadding(ins.systemWindowInsetLeft, ins.systemWindowInsetTop,
                    ins.systemWindowInsetRight, ins.systemWindowInsetBottom)
                ins
            }
        }
        if (narrow) {
            window.statusBarColor = PANEL
            window.navigationBarColor = PANEL
        }
        content = FrameLayout(this).apply {
            setBackgroundColor(BG)
            layoutParams = if (narrow) LinearLayout.LayoutParams(MATCH, 0, 1f) else LinearLayout.LayoutParams(0, MATCH, 1f)
        }
        if (narrow) {
            // phone: app bar on top, the update banner under it, the menu at the bottom
            root.addView(buildTopBar())
            updateCard = buildUpdateCard()
            root.addView(updateCard)
            root.addView(hline())
            root.addView(content)
            root.addView(hline())
            rail = buildBottomNav()
            root.addView(rail)
        } else {
            rail = buildRail()
            root.addView(rail)
            root.addView(View(this).apply {
                setBackgroundColor(LINE)
                layoutParams = LinearLayout.LayoutParams(dp(1), MATCH)
            })
            root.addView(content)
        }
        setContentView(root)
        showPage(Page.SOURCES)
        // "Support" is the first menu item, but the remote starts on the active servers
        navItems[Page.SOURCES]?.requestFocus()
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

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (page != Page.SOURCES) {
            showPage(Page.SOURCES)
            navItems[Page.SOURCES]?.requestFocus()
        } else {
            @Suppress("DEPRECATION")
            super.onBackPressed()
        }
    }

    /** Leaving the page towards the menu lands on the current page's item, not on the nearest one. */
    private inner class RootLayout(c: Context) : LinearLayout(c) {
        override fun focusSearch(focused: View?, direction: Int): View? {
            val r = super.focusSearch(focused, direction)
            val towardsMenu = if (narrow) View.FOCUS_DOWN else View.FOCUS_LEFT
            if (direction == towardsMenu && r != null && focused != null && inRail(r) && !inRail(focused)) {
                return navItems[page] ?: r
            }
            return r
        }

        private fun inRail(v: View): Boolean {
            var p: Any? = v
            while (p is View) {
                if (p === rail) return true
                p = p.parent
            }
            return false
        }
    }

    // ---------- phone: app bar and bottom menu ----------

    private fun hline() = View(this).apply {
        setBackgroundColor(LINE)
        layoutParams = LinearLayout.LayoutParams(MATCH, dp(1))
    }

    private fun brandBadge() = TextView(this).apply {
        text = "JC"
        textSize = 13f
        typeface = Typeface.DEFAULT_BOLD
        setTextColor(Color.WHITE)
        gravity = Gravity.CENTER
        background = rounded(ACCENT, dp(8))
        layoutParams = LinearLayout.LayoutParams(dp(32), dp(32)).apply { rightMargin = dp(10) }
    }

    private fun buildTopBar(): View {
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(10), dp(16), dp(10))
        }
        bar.addView(brandBadge())
        bar.addView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, WRAP, 1f)
            addView(tv("JacCollector", 16f, TEXT, medium))
            addView(tv("версия ${BuildConfig.VERSION_NAME}", 11f, DIM))
        })
        // "Support" is a heart button in the corner, like in most phone apps
        bar.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            isFocusable = true
            isClickable = true
            setPadding(dp(12), dp(8), dp(12), dp(8))
            background = states(focused = shape(PANEL2, Color.WHITE, dp(2), dp(18)),
                normal = shape(Color.TRANSPARENT, LINE, dp(1), dp(18)))
            addView(ImageView(this@MainActivity).apply {
                setImageResource(R.drawable.ic_heart)
                setColorFilter(Color.rgb(255, 120, 140), PorterDuff.Mode.SRC_IN)
                layoutParams = LinearLayout.LayoutParams(dp(16), dp(16)).apply { rightMargin = dp(8) }
            })
            addView(tv("Поддержать", 13f, TEXT))
            setOnClickListener { donateDialog() }
        })
        return bar
    }

    private fun buildBottomNav(): ViewGroup {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(4), dp(4), dp(4), dp(4))
        }
        fun item(p: Page, icon: Int, label: String) {
            val item = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                isFocusable = true
                isClickable = true
                setPadding(0, dp(6), 0, dp(6))
                background = states(focused = shape(PANEL2, Color.WHITE, dp(2), dp(12)),
                    normal = shape(Color.TRANSPARENT, 0, 0, dp(12)))
                layoutParams = LinearLayout.LayoutParams(0, WRAP, 1f)
                setOnClickListener { showPage(p) }
            }
            // the selected item gets a soft pill behind its icon, as in Material bottom bars
            val pill = FrameLayout(this).apply {
                setPadding(dp(18), dp(4), dp(18), dp(4))
                background = StateListDrawable().apply {
                    addState(intArrayOf(android.R.attr.state_selected), rounded(Color.rgb(26, 43, 74), dp(14)))
                    addState(intArrayOf(), rounded(Color.TRANSPARENT, dp(14)))
                }
                isDuplicateParentStateEnabled = true
                layoutParams = LinearLayout.LayoutParams(WRAP, WRAP)
            }
            val img = ImageView(this).apply {
                setImageResource(icon)
                setColorFilter(MUTED, PorterDuff.Mode.SRC_IN)
                layoutParams = FrameLayout.LayoutParams(dp(22), dp(22))
            }
            pill.addView(img)
            item.addView(pill)
            item.addView(tv(label, 12f, 0).apply {
                setTextColor(ColorStateList(arrayOf(intArrayOf(android.R.attr.state_selected), intArrayOf()), intArrayOf(TEXT, MUTED)))
                isDuplicateParentStateEnabled = true
                setPadding(0, dp(4), 0, 0)
                layoutParams = LinearLayout.LayoutParams(WRAP, WRAP)
                singleLine()
            })
            box.addView(item)
            navItems[p] = item
            navIcons[p] = img
        }
        item(Page.SOURCES, R.drawable.ic_sources, "Активные")
        item(Page.CATALOG, R.drawable.ic_globe, "Загрузить")
        item(Page.LOG, R.drawable.ic_log, "Журнал")
        item(Page.UPDATES, R.drawable.ic_refresh, "Обновления")
        return box
    }

    // ---------- side menu ----------

    private fun buildRail(): ViewGroup {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(PANEL)
            setPadding(dp(14), dp(24), dp(14), dp(18))
            layoutParams = LinearLayout.LayoutParams(dp(200), MATCH)
        }
        run {
            val brand = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(6), 0, 0, dp(24))
            }
            brand.addView(TextView(this).apply {
                text = "JC"
                textSize = 13f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(Color.WHITE)
                gravity = Gravity.CENTER
                background = rounded(ACCENT, dp(8))
                layoutParams = LinearLayout.LayoutParams(dp(32), dp(32)).apply { rightMargin = dp(10) }
            })
            brand.addView(LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                addView(tv("JacCollector", 15f, TEXT, medium))
                addView(tv("версия ${BuildConfig.VERSION_NAME}", 11f, DIM))
            })
            box.addView(brand)
        }
        box.addView(navItem(null, R.drawable.ic_heart, "Поддержать") { donateDialog() })
        box.addView(navItem(Page.SOURCES, R.drawable.ic_sources, "Активные"))
        box.addView(navItem(Page.CATALOG, R.drawable.ic_globe, "Загрузить"))
        box.addView(navItem(Page.LOG, R.drawable.ic_log, "Журнал"))
        box.addView(navItem(Page.UPDATES, R.drawable.ic_refresh, "Обновления"))
        box.addView(View(this).apply { layoutParams = LinearLayout.LayoutParams(1, 0, 1f) })
        updateCard = buildUpdateCard()
        box.addView(updateCard)
        return box
    }

    private fun navItem(p: Page?, icon: Int, label: String, onClick: (() -> Unit)? = null): View {
        val item = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            isFocusable = true
            isClickable = true
            setPadding(dp(12), dp(9), dp(12), dp(9))
            background = states(
                focused = shape(PANEL2, Color.WHITE, dp(2), dp(8)),
                selected = shape(PANEL2, 0, 0, dp(8)),
                normal = shape(Color.TRANSPARENT, 0, 0, dp(8)))
            layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).apply { bottomMargin = dp(4) }
            setOnClickListener { if (p != null) showPage(p) else onClick?.invoke() }
        }
        val img = ImageView(this).apply {
            setImageResource(icon)
            setColorFilter(MUTED, PorterDuff.Mode.SRC_IN)
            layoutParams = LinearLayout.LayoutParams(dp(18), dp(18)).apply { rightMargin = dp(12) }
        }
        item.addView(img)
        item.addView(tv(label, 14f, 0).apply {
            setTextColor(ColorStateList(
                arrayOf(intArrayOf(android.R.attr.state_selected), intArrayOf(android.R.attr.state_focused), intArrayOf()),
                intArrayOf(TEXT, TEXT, MUTED)))
            isDuplicateParentStateEnabled = true
            layoutParams = LinearLayout.LayoutParams(0, WRAP, 1f)
        })
        if (p != null) {
            val count = tv("", 11f, DIM).apply { setPadding(dp(8), 0, 0, 0) }
            item.addView(count)
            navCounts[p] = count
            navItems[p] = item
            navIcons[p] = img
        }
        return item
    }

    /** Calm card at the bottom of the menu while a downloaded update waits to be installed. */
    private fun buildUpdateCard(): View {
        val card = LinearLayout(this).apply {
            orientation = if (narrow) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
            gravity = if (narrow) Gravity.CENTER_VERTICAL else Gravity.NO_GRAVITY
            isFocusable = true
            isClickable = true
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = states(
                focused = shape(Color.rgb(20, 33, 58), Color.WHITE, dp(2), dp(10)),
                normal = shape(Color.rgb(20, 33, 58), Color.rgb(44, 74, 124), dp(1), dp(10)))
            layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).apply {
                if (narrow) { leftMargin = dp(12); rightMargin = dp(12); bottomMargin = dp(10) } else topMargin = dp(8)
            }
            visibility = View.GONE
            setOnClickListener { onUpdatePressed() }
        }
        updateTitle = tv("", 13f, Color.rgb(207, 224, 255), medium)
        if (narrow) {
            card.addView(updateTitle.apply { layoutParams = LinearLayout.LayoutParams(0, WRAP, 1f) })
            card.addView(tv("Установить", 13f, Color.WHITE, medium).apply {
                background = rounded(ACCENT, dp(8))
                setPadding(dp(14), dp(6), dp(14), dp(6))
            })
        } else {
            card.addView(updateTitle)
            card.addView(tv("OK — установить", 11f, Color.rgb(143, 169, 214)))
        }
        return card
    }

    private fun showPage(p: Page) {
        page = p
        for ((k, v) in navItems) {
            v.isSelected = k == p
            navIcons[k]?.setColorFilter(if (k == p) ACCENT else MUTED, PorterDuff.Mode.SRC_IN)
        }
        serverDot = null; serverAddr = null; serverLan = null; workValue = null; foundValue = null
        deadlineValue = null; checkedText = null; sourcesList = null; logText = null; syncInfo = null
        switchPill = null; catalogList = null; catalogChips = emptyList(); catalogFooter = null
        switchRow = null; shareOnText = null; shareOffText = null; installRow = null; installTitle = null
        rows.clear()
        content.removeAllViews()
        content.addView(when (p) {
            Page.SOURCES -> sourcesPage()
            Page.CATALOG -> catalogPage()
            Page.LOG -> logPage()
            Page.UPDATES -> updatesPage()
        })
        refreshStatus()
    }

    /** Page frame: title, one line of explanation, buttons on the right. */
    private fun pageFrame(title: String, subtitle: String, actions: List<View> = emptyList()): LinearLayout {
        val outer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            if (narrow) setPadding(dp(16), dp(16), dp(16), dp(10)) else setPadding(dp(28), dp(18), dp(28), dp(14))
            layoutParams = FrameLayout.LayoutParams(MATCH, MATCH)
        }
        val head = LinearLayout(this).apply {
            orientation = if (narrow) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL
            gravity = if (narrow) Gravity.NO_GRAVITY else Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, dp(12))
        }
        head.addView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(if (narrow) MATCH else 0, WRAP, if (narrow) 0f else 1f)
            addView(tv(title, if (narrow) 20f else 24f, TEXT, medium))
            addView(tv(subtitle, 12f, MUTED).apply { setPadding(0, dp(4), 0, 0) })
        })
        if (actions.isNotEmpty()) {
            val btns = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                if (narrow) setPadding(0, dp(10), 0, 0)
            }
            actions.forEachIndexed { i, b ->
                if (narrow) {
                    // phone: the buttons share the full width
                    b.layoutParams = LinearLayout.LayoutParams(0, WRAP, 1f).apply { leftMargin = if (i == 0) 0 else dp(10) }
                    (b as? TextView)?.apply { singleLine(); centerContent() }
                    (b as? LinearLayout)?.gravity = Gravity.CENTER_VERTICAL
                } else {
                    (b.layoutParams as? LinearLayout.LayoutParams)?.leftMargin = dp(10)
                }
                btns.addView(b)
            }
            head.addView(btns)
        }
        outer.addView(head)
        return outer
    }

    // ---------- page: sources ----------

    private fun sourcesPage(): View {
        val outer = pageFrame("Активные серверы",
            "Все JacRed — в одном поиске для плееров",
            listOf(button(if (narrow) "Проверить" else "Проверить связь", R.drawable.ic_pulse) { probe(it) },
                button(if (narrow) "Добавить" else "Добавить источник", R.drawable.ic_plus, primary = true) { addSourceDialog() }))

        // strip of figures: server address, working sources, results, waiting time
        val strip = LinearLayout(this).apply {
            orientation = if (narrow) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL
            background = shape(PANEL, LINE, dp(1), dp(12))
            layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).apply { bottomMargin = dp(14) }
        }
        val t1 = tile("Сервер Jackett", 2.2f)
        val addrRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        serverDot = dot(DIM).also { addrRow.addView(it) }
        serverAddr = tv("127.0.0.1:${Store.PORT}", 17f, TEXT).apply {
            typeface = Typeface.MONOSPACE
            setPadding(dp(10), 0, 0, 0)
        }.also { addrRow.addView(it) }
        // the home-network address sits on the same line, to keep the strip low
        serverLan = tv("", 11f, DIM).apply { setPadding(dp(12), 0, 0, 0); singleLine() }.also { addrRow.addView(it) }
        t1.addView(addrRow)
        if (narrow) {
            // on a phone the address is copied with a tap, to paste it into the player
            t1.addView(tv("В плеере: Jackett — этот адрес, ключ любой. Нажмите, чтобы скопировать", 11f, MUTED)
                .apply { setPadding(0, dp(4), 0, 0) })
            t1.isClickable = true
            t1.isFocusable = true
            t1.background = states(focused = shape(PANEL2, Color.WHITE, dp(2), dp(12)), normal = shape(Color.TRANSPARENT, 0, 0, dp(12)))
            t1.setOnClickListener {
                val cm = getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                cm.setPrimaryClip(android.content.ClipData.newPlainText("Jackett", "127.0.0.1:${Store.PORT}"))
                Toast.makeText(this, "Скопировано: 127.0.0.1:${Store.PORT}", Toast.LENGTH_SHORT).show()
            }
        } else {
            t1.addView(tv("В плеере: Jackett — этот адрес, ключ любой", 11f, MUTED).apply { setPadding(0, dp(4), 0, 0); singleLine() })
        }
        strip.addView(t1)
        val t2 = tile("Работают", 1f)
        workValue = tv("—", 18f, TEXT, medium).also { t2.addView(it) }
        val t3 = tile("Раздач", 1f)
        foundValue = tv("—", 18f, TEXT, medium).also { t3.addView(it) }
        val t4 = tile("Ждать", 1f).apply {
            isFocusable = true
            isClickable = true
            background = states(focused = shape(PANEL2, Color.WHITE, dp(2), dp(12)), normal = shape(Color.TRANSPARENT, 0, 0, dp(12)))
            setOnClickListener { nextDeadline() }
        }
        deadlineValue = tv("", 18f, TEXT, medium).also { t4.addView(it) }
        // thin lines between the tiles, drawn by the strip itself (a MATCH_PARENT line view
        // inside a wrap_content strip would stretch it to the whole screen)
        strip.dividerDrawable = GradientDrawable().apply { setColor(LINE); setSize(dp(1), dp(1)) }
        strip.showDividers = LinearLayout.SHOW_DIVIDER_MIDDLE
        if (narrow) {
            // phone: the address on its own line, the three figures side by side under it
            val figures = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                dividerDrawable = GradientDrawable().apply { setColor(LINE); setSize(dp(1), dp(1)) }
                showDividers = LinearLayout.SHOW_DIVIDER_MIDDLE
            }
            for (t in listOf(t2, t3, t4)) {
                t.layoutParams = LinearLayout.LayoutParams(0, WRAP, 1f)
                t.setPadding(dp(14), dp(10), dp(8), dp(10))
                figures.addView(t)
            }
            strip.addView(figures)
        } else {
            for (t in listOf(t2, t3, t4)) strip.addView(t)
        }
        outer.addView(strip)

        val sec = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, dp(8))
        }
        sec.addView(tv("Подключены сейчас", 16f, TEXT, medium).apply {
            layoutParams = LinearLayout.LayoutParams(0, WRAP, 1f)
        })
        checkedText = tv("", 12f, MUTED).also { sec.addView(it) }
        outer.addView(sec)

        val table = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = shape(Color.TRANSPARENT, LINE, dp(1), dp(12))
            setPadding(dp(1), dp(1), dp(1), dp(1))
            layoutParams = LinearLayout.LayoutParams(MATCH, 0, 1f)
        }
        if (!narrow) table.addView(headerRow(listOf(
            "" to dp(22), "Сервер" to -12, "Языки" to -12, "Раздач" to dp(64), "Отклик" to dp(150), "Состояние" to dp(150))))
        sourcesList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        table.addView(ScrollView(this).apply {
            isVerticalScrollBarEnabled = false
            layoutParams = LinearLayout.LayoutParams(MATCH, 0, 1f)
            addView(sourcesList)
        })
        outer.addView(table)
        outer.addView(if (narrow)
            tv("Нажмите на сервер — подробности, выключить или удалить. «Ждать» — сколько ждать ответа источников", 11f, DIM).apply { setPadding(0, dp(8), 0, 0) }
        else
            tv("OK на сервере — подробности, выключить или удалить  ·  OK на «Ждать» — сколько ждать ответа источников", 11f, DIM).apply { setPadding(0, dp(8), 0, 0); singleLine() })
        buildSources()
        updateDeadline()
        return outer
    }

    private fun tile(label: String, weight: Float) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(16), dp(9), dp(16), dp(9))
        layoutParams = if (narrow) LinearLayout.LayoutParams(MATCH, WRAP) else LinearLayout.LayoutParams(0, MATCH, weight)
        addView(caption(label).apply { setPadding(0, 0, 0, dp(4)); singleLine() })
    }

    /** Column titles; a negative width means a weight (x10). */
    private fun headerRow(cols: List<Pair<String, Int>>) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(14), dp(10), dp(14), dp(10))
        background = shape(PANEL, 0, 0, dp(11))
        for ((t, w) in cols) addView(caption(t).apply { layoutParams = col(w) })
    }

    private fun col(w: Int) = if (w < 0) LinearLayout.LayoutParams(0, WRAP, -w / 10f) else LinearLayout.LayoutParams(w, WRAP)

    private fun buildSources() {
        val box = sourcesList ?: return
        box.removeAllViews()
        rows.clear()
        val list = Store.sources(this)
        list.forEachIndexed { index, src ->
            if (index > 0) box.addView(View(this).apply {
                setBackgroundColor(LINE)
                layoutParams = LinearLayout.LayoutParams(MATCH, dp(1))
            })
            box.addView(sourceRow(index, src))
        }
        if (list.isEmpty()) box.addView(tv(
            if (Store.shareEnabled(this)) "Список пуст — добавьте источник или выберите серверы в общем списке."
            else "Список пуст — добавьте личный сервер или включите общий список в разделе «Загрузить».",
            14f, MUTED).apply { setPadding(dp(16), dp(20), dp(16), dp(20)) })
        refreshStatus()
    }

    private fun sourceRow(index: Int, src: Source): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            isFocusable = true
            isClickable = true
            minimumHeight = dp(54)
            setPadding(dp(14), dp(8), dp(14), dp(8))
            background = rowBg()
            setOnClickListener { sourceDialog(index) }
        }
        val d = dot(DIM).apply { (layoutParams as LinearLayout.LayoutParams).rightMargin = dp(14) }
        row.addView(LinearLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(22), WRAP)
            addView(d)
        })
        val nameCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = col(-12)
        }
        val name = tv(src.name, 15f, TEXT, medium).apply { singleLine() }
        val sub = tv(src.url, 11f, DIM).apply { typeface = Typeface.MONOSPACE; singleLine() }
        nameCol.addView(name)
        nameCol.addView(sub)
        row.addView(nameCol)
        if (narrow) {
            // phone card: name and state on top, address under it, then languages · results · response time
            nameCol.removeAllViews()
            row.gravity = Gravity.TOP
            row.minimumHeight = dp(64)
            row.setPadding(dp(14), dp(12), dp(14), dp(12))
            (d.parent as View).setPadding(0, dp(6), 0, 0)
            val top =LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            top.addView(name.apply { layoutParams = LinearLayout.LayoutParams(0, WRAP, 1f) })
            val state = tv("", 12f, MUTED).apply { setPadding(dp(10), 0, 0, 0); singleLine() }
            top.addView(state)
            nameCol.addView(top)
            nameCol.addView(sub.apply { setPadding(0, dp(2), 0, 0) })
            val meta = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(6), 0, 0)
            }
            val langs = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            meta.addView(langs)
            val count = tv("", 12f, TEXT).apply { setPadding(dp(4), 0, 0, 0) }
            meta.addView(count)
            val lat = tv("", 12f, MUTED).apply { setPadding(dp(10), 0, 0, 0) }
            meta.addView(lat)
            nameCol.addView(meta)
            rows[src.name] = Row(d, name, sub, langs, count, null, lat, state)
            return row
        }
        val langs = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = col(-12)
        }
        row.addView(langs)
        val count = tv("—", 14f, TEXT).apply { layoutParams = col(dp(64)) }
        row.addView(count)
        val latBox = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = col(dp(150))
        }
        val bar = FrameLayout(this).apply {
            background = rounded(LINE, dp(2))
            layoutParams = LinearLayout.LayoutParams(dp(64), dp(4)).apply { rightMargin = dp(10) }
        }
        val fill = View(this).apply {
            background = rounded(OK, dp(2))
            layoutParams = FrameLayout.LayoutParams(0, MATCH)
        }
        bar.addView(fill)
        latBox.addView(bar)
        val lat = tv("—", 13f, TEXT)
        latBox.addView(lat)
        row.addView(latBox)
        val state = tv("", 13f, MUTED).apply { layoutParams = col(dp(150)); singleLine() }
        row.addView(state)
        rows[src.name] = Row(d, name, sub, langs, count, fill, lat, state)
        return row
    }

    /** Languages seen in the server's answers; until it answered, the ones from the public list. */
    private fun langCodes(src: Source): List<String> {
        val seen = Aggregator.states[src.name]?.langs?.toList().orEmpty()
        val langs = seen.ifEmpty { src.lang.split(',').filter { it.isNotBlank() } }
        return langs.sortedBy { listOf("ru", "uk", "en").indexOf(it).let { i -> if (i < 0) 9 else i } }
    }

    private fun langLabel(code: String) = when (code.trim()) {
        "ru" -> "РУС"; "uk" -> "УКР"; "en" -> "АНГЛ"; else -> code.trim().uppercase()
    }

    private fun refreshSources() {
        val srv = CollectorService.server
        when {
            srv?.error != null -> { serverDot?.background = circle(BAD); serverAddr?.text = "Ошибка: ${srv.error}" }
            srv?.running == true -> { serverDot?.background = circle(OK); serverAddr?.text = "127.0.0.1:${Store.PORT}" }
            else -> { serverDot?.background = circle(WARN); serverAddr?.text = "запускается…" }
        }
        serverLan?.text = lanIp()?.let { "$it:${Store.PORT}" } ?: ""
        val now = System.currentTimeMillis()
        val list = Store.sources(this)
        var working = 0
        var found = 0
        var lastCheck = 0L
        for (src in list) {
            val st = Aggregator.states[src.name]
            lastCheck = maxOf(lastCheck, st?.lastAt ?: 0L)
            val good = src.enabled && st != null && st.lastAt > 0 && st.lastError == null &&
                st.rateLimitedUntil <= now && st.benchedUntil <= now
            if (good) { working++; found += maxOf(st!!.lastCount, 0) }
            val r = rows[src.name] ?: continue
            val (msg, color) = when {
                src.communityOff -> "Не работает у других" to BAD
                !src.enabled -> "Выключен" to DIM
                st != null && st.rateLimitedUntil > now ->
                    "Пауза ${(st.rateLimitedUntil - now) / 60000 + 1} мин · 429" to WARN
                st == null || st.lastAt == 0L -> "Не проверялся" to MUTED
                st.benchedUntil > now -> "Отдыхает ${(st.benchedUntil - now) / 60000 + 1} мин" to WARN
                st.lastError != null -> "Ошибка" to WARN
                else -> "Работает" to OK
            }
            r.state.text = msg
            r.state.setTextColor(color)
            r.dot.background = circle(if (color == MUTED) DIM else color)
            r.name.setTextColor(if (src.enabled) TEXT else DIM)
            val extra = buildList {
                if (st?.lastError != null && (st.benchedUntil > now || msg == "Ошибка")) add(st.lastError!!)
                val gap = st?.gapMs ?: 0
                if (gap > 0) add("бережно: раз в ${(gap + 999) / 1000} с")
            }
            r.sub.text = (listOf(src.url) + extra).joinToString("  ·  ")
            val answered = good && st!!.lastCount >= 0
            val countText = if (answered) "%,d".format(st!!.lastCount).replace(',', ' ') else "—"
            if (narrow) {
                // phone card: "1 234 раздач   420 мс", nothing until the server answered
                r.count?.text = if (answered) "$countText раздач" else ""
                r.lat?.text = if (answered) "${st!!.lastMs} мс" else ""
                r.lat?.setTextColor(when { !answered -> DIM; st!!.lastMs < 600 -> OK; st.lastMs < 1200 -> WARN; else -> BAD })
            } else {
                r.count?.text = countText
                r.lat?.text = if (answered) "${st!!.lastMs} мс" else "—"
            }
            r.count?.setTextColor(if (answered) TEXT else DIM)
            r.barFill?.let { f ->
                val ms = if (answered) st!!.lastMs else 0L
                val lp = f.layoutParams
                lp.width = (dp(64) * minOf(ms / 1500.0, 1.0)).toInt()
                f.layoutParams = lp
                f.background = rounded(when { ms < 600 -> OK; ms < 1200 -> WARN; else -> BAD }, dp(2))
            }
            r.langs?.let { box ->
                val tags = langCodes(src).map(::langLabel) + listOfNotNull(
                    if (src.apiKey.isNotBlank()) "КЛЮЧ" else null,
                    if (!src.isPublic && src.apiKey.isBlank() && Community.isPersonal(src)) "ДОМ" else null)
                val key = tags.joinToString()
                if (key != r.langKey) {
                    r.langKey = key
                    box.removeAllViews()
                    tags.forEach { box.addView(tag(it)) }
                }
            }
        }
        workValue?.text = if (list.isEmpty()) "—" else "$working из ${list.size}"
        foundValue?.text = if (working == 0) "—" else "%,d".format(found).replace(',', ' ')
        checkedText?.text = when {
            lastCheck == 0L -> ""
            now - lastCheck < 60_000 -> "Проверено только что"
            else -> "Проверено ${(now - lastCheck) / 60_000} мин назад"
        }
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
        val shareNote = TextView(this).apply {
            text = "Публичные адреса попадают в общий список (отключить — в разделе «Загрузить»)."
            textSize = 12f
            setTextColor(Color.GRAY)
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
            box.addView(TextView(this).apply {
                text = "Общий список выключен: можно добавить только личный сервер — " +
                    "домашний адрес или сервер с API-ключом."
                textSize = 12f
                setTextColor(Color.GRAY)
            })
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
                                "Включите общий список в разделе «Загрузить».")
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

    private fun probe(btn: View) {
        // anti-spam: every 30 s at most, button off while checking
        if (!Store.allow(this, "probe_btn", 30_000)) {
            Toast.makeText(this, "Проверка только что была, можно снова через " +
                "${Store.waitSeconds(this, "probe_btn", 30_000)} с", Toast.LENGTH_SHORT).show()
            return
        }
        btn.isEnabled = false
        btn.alpha = 0.5f
        checkedText?.text = "Проверяю источники…"
        Thread {
            Aggregator.probe(applicationContext)
            ui.post { btn.isEnabled = true; btn.alpha = 1f; refreshStatus() }
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
        // on a phone a chevron hints that the tile is tapped to change it
        deadlineValue?.text = "%.1f сек".format(Store.deadlineMs(this) / 1000.0) + if (narrow) "  ›" else ""
    }

    // ---------- page: shared list ----------

    private fun catalogPage(): View {
        val outer = pageFrame("Загрузить из общего списка",
            "Серверы, которые нашли и проверили другие пользователи. " +
                (if (narrow) "Нажмите на сервер — подключить или убрать" else "OK — подключить или убрать"),
            listOf(shareToggle()))
        if (!Store.shareEnabled(this)) {
            outer.addView(shareExplain())
            return outer
        }
        val chips = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 0, 0, dp(14))
        }
        catalogChips = listOf("Все", "Подключены", "Не подключены", "Рекомендуемые", "Не отвечают").mapIndexed { i, t ->
            chip(t).apply {
                isSelected = i == catalogFilter
                setOnClickListener {
                    catalogFilter = i
                    catalogChips.forEachIndexed { j, c -> c.isSelected = j == i }
                    renderCatalog()
                }
            }.also { chips.addView(it) }
        }
        outer.addView(if (narrow) HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            addView(chips)
        } else chips)

        val table = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = shape(Color.TRANSPARENT, LINE, dp(1), dp(12))
            setPadding(dp(1), dp(1), dp(1), dp(1))
            layoutParams = LinearLayout.LayoutParams(MATCH, 0, 1f)
        }
        if (!narrow) table.addView(headerRow(listOf(
            "Оценка" to dp(84), "Сервер" to -15, "Языки" to -11, "Состояние" to dp(130), "" to dp(140))))
        catalogList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        table.addView(ScrollView(this).apply {
            isVerticalScrollBarEnabled = false
            layoutParams = LinearLayout.LayoutParams(MATCH, 0, 1f)
            addView(catalogList)
        })
        // the legend stays put at the bottom, however long the list is
        val legend = LinearLayout(this).apply {
            // a phone has no room for both on one line
            orientation = if (narrow) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL
            gravity = if (narrow) Gravity.NO_GRAVITY else Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(10), dp(16), dp(10))
            background = shape(PANEL, 0, 0, dp(11))
        }
        legend.addView(tv("Оценка 0–10 по проверкам приставок, в скобках — сколько их было", 11f, MUTED).apply {
            layoutParams = if (narrow) LinearLayout.LayoutParams(MATCH, WRAP) else LinearLayout.LayoutParams(0, WRAP, 1f)
        })
        catalogFooter = tv("", 11f, DIM).also { legend.addView(it) }
        table.addView(View(this).apply { setBackgroundColor(LINE); layoutParams = LinearLayout.LayoutParams(MATCH, dp(1)) })
        table.addView(legend)
        outer.addView(table)
        if (catalog != null) renderCatalog()
        loadCatalog()
        return outer
    }

    private fun loadCatalog() {
        // one load at a time; the catalog itself is downloaded at most every 10 minutes
        if (catalogLoading) return
        catalogLoading = true
        if (catalog == null) catalogList?.addView(tv("Загружаю общий список…", 14f, MUTED).apply {
            setPadding(dp(16), dp(20), dp(16), dp(20))
        })
        Thread {
            // opening the catalog also applies the public list (new recommended servers,
            // dead ones switched off); the download itself happens at most every 10 minutes
            val fresh = System.currentTimeMillis() - Store.catalogTime(applicationContext) >= 10 * 60 * 1000L
            if (fresh) Community.syncNow(applicationContext)
            val cat = Community.catalog(applicationContext)
            ui.post {
                catalogLoading = false
                if (cat != null && cat.entries.isNotEmpty()) catalog = cat
                if (page != Page.CATALOG) return@post
                if (catalog == null) {
                    catalogList?.removeAllViews()
                    catalogList?.addView(tv("Общий список недоступен, попробуйте позже", 14f, BAD).apply {
                        setPadding(dp(16), dp(20), dp(16), dp(20))
                    })
                } else {
                    renderCatalog()
                }
                refreshStatus()
            }
        }.start()
    }

    private fun catalogEntries(): List<Community.Entry> {
        val cat = catalog ?: return emptyList()
        val myLang = Community.deviceLang()
        val ratings = Community.ratings(this)
        fun score(e: Community.Entry) = ratings[e.url.lowercase()]?.takeIf { it.boxes >= Community.MIN_BOXES }
        // language first (own language on top), then the best rated; dead and unrated last
        return cat.entries
            .filter { it.url.lowercase() !in cat.removed }
            .sortedWith(compareBy<Community.Entry>(
                { Store.priority(Source(it.name, it.url, lang = it.lang), myLang) },
                { if (it.dead) 1 else 0 },
                { -(score(it)?.score ?: -1) },
                { -(score(it)?.boxes ?: 0) }))
    }

    private fun haveUrls() = Store.sources(this).map { Store.normalizeUrl(it.url).lowercase() }.toMutableSet()

    private fun renderCatalog() {
        val box = catalogList ?: return
        val all = catalogEntries()
        val have = haveUrls()
        val filters: List<(Community.Entry) -> Boolean> = listOf(
            { _ -> true }, { e -> e.url.lowercase() in have }, { e -> e.url.lowercase() !in have },
            { e -> e.recommended }, { e -> e.dead })
        val names = listOf("Все", "Подключены", "Не подключены", "Рекомендуемые", "Не отвечают")
        catalogChips.forEachIndexed { i, c -> c.text = "${names[i]}  ${all.count(filters[i])}" }
        val shown = all.filter(filters[catalogFilter])
        catalogFooter?.text = "${shown.size} из ${all.size}" + (syncShort()?.let { "  ·  список обновлён $it" } ?: "")
        val ratings = Community.ratings(this)
        box.removeAllViews()
        shown.forEachIndexed { i, e ->
            if (i > 0) box.addView(View(this).apply { setBackgroundColor(LINE); layoutParams = LinearLayout.LayoutParams(MATCH, dp(1)) })
            box.addView(catalogRow(e, ratings[e.url.lowercase()]?.takeIf { it.boxes >= Community.MIN_BOXES }, have))
        }
        if (shown.isEmpty()) box.addView(tv("Здесь пусто", 14f, MUTED).apply { setPadding(dp(16), dp(20), dp(16), dp(20)) })
    }

    private fun catalogRow(e: Community.Entry, r: Community.Rating?, have: MutableSet<String>): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            isFocusable = true
            isClickable = true
            minimumHeight = dp(52)
            setPadding(dp(14), dp(8), dp(14), dp(8))
        }
        val scoreBox = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.BOTTOM
            layoutParams = col(if (narrow) dp(54) else dp(84))
        }
        val scoreColor = when { r == null -> DIM; e.dead -> DIM; r.score >= 7 -> TEXT; r.score >= 4 -> WARN; else -> BAD }
        scoreBox.addView(tv(r?.score?.toString() ?: "—", 18f, scoreColor, medium))
        if (r != null) scoreBox.addView(tv(" (${r.boxes})", 11f, DIM).apply { setPadding(0, 0, 0, dp(2)) })
        row.addView(scoreBox)

        val nameCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = col(-15)
        }
        val nameLine = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        nameLine.addView(tv(e.name, 15f, if (e.dead) DIM else TEXT, medium).apply { singleLine() })
        // on a phone the badge goes to the second line, so a long name is not squeezed
        val badgeLine = if (narrow) LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(3), 0, 0)
        } else nameLine
        if (e.recommended) badgeLine.addView(TextView(this).apply {
            text = "РЕКОМЕНДУЕМ"
            textSize = 9f
            letterSpacing = 0.08f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.rgb(207, 224, 255))
            background = rounded(Color.rgb(26, 43, 74), dp(4))
            setPadding(dp(6), dp(2), dp(6), dp(2))
            layoutParams = LinearLayout.LayoutParams(WRAP, WRAP).apply { if (narrow) rightMargin = dp(8) else leftMargin = dp(10) }
        })
        nameCol.addView(nameLine)
        val langs = e.lang.split(',').filter { it.isNotBlank() }.map(::langLabel)
        if (narrow) {
            badgeLine.addView(tv(langs.joinToString(" · ") + if (e.dead) "  ·  не отвечает" else "",
                11f, if (e.dead) BAD else DIM).apply { singleLine() })
            nameCol.addView(badgeLine)
        }
        row.addView(nameCol)
        if (!narrow) {
            row.addView(LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = col(-11)
                langs.forEach { addView(tag(it)) }
            })
            row.addView(LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                layoutParams = col(dp(130))
                addView(dot(if (e.dead) BAD else OK).apply { (layoutParams as LinearLayout.LayoutParams).rightMargin = dp(10) })
                addView(tv(if (e.dead) "Не отвечает" else "Работает", 13f, MUTED))
            })
        }
        val action = tv("", 12f, 0, medium).apply {
            gravity = Gravity.CENTER
            setPadding(dp(12), dp(5), dp(12), dp(5))
        }
        row.addView(LinearLayout(this).apply {
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
            layoutParams = if (narrow) LinearLayout.LayoutParams(WRAP, WRAP).apply { leftMargin = dp(8) } else col(dp(140))
            addView(action)
        })
        fun showAction() {
            val on = e.url.lowercase() in have
            // connected: green-tinted row and a filled green badge; not connected: an outlined "+" button
            row.background = if (on) states(
                focused = shape(Color.rgb(22, 46, 37), Color.WHITE, dp(2), dp(8)),
                normal = shape(Color.rgb(14, 31, 25), 0, 0, dp(8)))
            else rowBg()
            action.text = if (on) "✓  Подключён" else "+  Подключить"
            action.setTextColor(if (on) OK else if (e.dead) MUTED else ACCENT)
            action.background = if (on) rounded(Color.rgb(20, 58, 44), dp(14))
            else shape(Color.TRANSPARENT, if (e.dead) LINE else ACCENT, dp(1), dp(14))
        }
        showAction()
        row.setOnClickListener {
            if (e.url.lowercase() in have) {
                val cur = Store.sources(this).toMutableList()
                cur.removeAll { Store.normalizeUrl(it.url).equals(e.url, true) }
                Store.dismiss(this, e.url)
                Store.saveSources(this, cur)
                have.remove(e.url.lowercase())
            } else {
                Community.addFromCatalog(this, e)
                have.add(e.url.lowercase())
            }
            showAction()
            refreshStatus()
            // chip counters follow the change; the list itself stays, so the remote keeps its place
            val all = catalogEntries()
            val counts = listOf(all.size, all.count { it.url.lowercase() in have },
                all.count { it.url.lowercase() !in have }, all.count { it.recommended }, all.count { it.dead })
            val names = listOf("Все", "Подключены", "Не подключены", "Рекомендуемые", "Не отвечают")
            catalogChips.forEachIndexed { i, c -> c.text = "${names[i]}  ${counts[i]}" }
        }
        return row
    }

    // ---------- page: request log ----------

    private fun logPage(): View {
        val outer = pageFrame("Журнал запросов", "Последние поиски плееров и ответы источников")
        logText = tv("", 12f, MUTED).apply {
            typeface = Typeface.MONOSPACE
            setLineSpacing(0f, 1.25f)
        }
        outer.addView(ScrollView(this).apply {
            isFocusable = true // the remote scrolls it
            background = states(focused = shape(PANEL, Color.WHITE, dp(2), dp(12)), normal = shape(PANEL, LINE, dp(1), dp(12)))
            setPadding(dp(18), dp(14), dp(18), dp(14))
            layoutParams = LinearLayout.LayoutParams(MATCH, 0, 1f)
            addView(logText)
        })
        return outer
    }

    // ---------- page: updates ----------

    private fun updatesPage(): View {
        val outer = pageFrame("Обновления",
            "Новые версии приходят с GitHub, без Google Play. Android всегда спрашивает перед установкой")
        val checkBtn = button("Проверить сейчас", R.drawable.ic_refresh) { btn ->
            if (!Store.allow(this, "update-check", 10_000)) return@button
            btn.isEnabled = false
            btn.alpha = 0.5f
            Toast.makeText(this, "Проверяю обновления…", Toast.LENGTH_SHORT).show()
            Updater.checkNow(this) { msg ->
                ui.post {
                    btn.isEnabled = true
                    btn.alpha = 1f
                    refreshStatus()
                    Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
                }
            }
        }
        outer.addView(settingRow("Установлена версия ${BuildConfig.VERSION_NAME}",
            tv("Проверяется само раз в сутки и при открытии приложения", 12f, MUTED), checkBtn))
        installTitle = tv("", 15f, TEXT, medium)
        installRow = LinearLayout(this).apply {
            orientation = if (narrow) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL
            gravity = if (narrow) Gravity.NO_GRAVITY else Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(14))
            background = shape(Color.rgb(20, 33, 58), Color.rgb(44, 74, 124), dp(1), dp(12))
            layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(10) }
            visibility = View.GONE
            addView(LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = if (narrow) LinearLayout.LayoutParams(MATCH, WRAP).apply { bottomMargin = dp(12) }
                else LinearLayout.LayoutParams(0, WRAP, 1f).apply { rightMargin = dp(16) }
                addView(installTitle)
                addView(tv("Уже скачана и проверена: это JacCollector с той же подписью", 12f,
                    Color.rgb(143, 169, 214)).apply { setPadding(0, dp(3), 0, 0) })
            })
            addView(button("Установить", R.drawable.ic_plus, primary = true) { onUpdatePressed() }
                .apply { layoutParams = LinearLayout.LayoutParams(WRAP, WRAP) })
        }
        outer.addView(installRow)
        return outer
    }

    /** Compact "Shared list  ON/OFF" button next to the page title; OK asks before switching. */
    private fun shareToggle(): View {
        switchPill = TextView(this).apply {
            textSize = 10f
            typeface = Typeface.DEFAULT_BOLD
            letterSpacing = 0.08f
            gravity = Gravity.CENTER
            setPadding(dp(10), dp(3), dp(10), dp(3))
            layoutParams = LinearLayout.LayoutParams(WRAP, WRAP).apply { leftMargin = dp(10) }
        }
        val t = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            isFocusable = true
            isClickable = true
            setPadding(dp(16), dp(9), dp(12), dp(9))
            background = states(focused = shape(PANEL2, Color.WHITE, dp(2), dp(8)), normal = shape(PANEL2, LINE, dp(1), dp(8)))
            layoutParams = LinearLayout.LayoutParams(WRAP, WRAP).apply { leftMargin = dp(10) }
            // on a phone the button spans the width: label on the left, ON/OFF on the right
            addView(tv("Общий список", 14f, TEXT, medium).apply {
                if (narrow) layoutParams = LinearLayout.LayoutParams(0, WRAP, 1f)
            })
            addView(switchPill)
            setOnClickListener { onCommunityToggle(!Store.shareEnabled(this@MainActivity)) }
        }
        switchRow = t
        updateSwitch()
        return t
    }

    /** While the shared list is off there is no table, so the page explains both positions. */
    private fun shareExplain(): View {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = shape(PANEL, LINE, dp(1), dp(12))
            setPadding(dp(18), dp(14), dp(18), dp(16))
        }
        box.addView(tv("Общий список выключен", 16f, TEXT, medium))
        shareOnText = tv("ВКЛ — серверы из общего списка приходят сами; взамен уходят рабочие публичные " +
            "адреса, которые вы добавили: адрес, число русскоязычных раздач на нём, язык системы и анонимный номер установки.",
            13f, DIM).apply { setPadding(0, dp(10), 0, 0); setLineSpacing(0f, 1.15f) }
        shareOffText = tv("ВЫКЛ — ничего не отправляется и не скачивается; работают только личные серверы " +
            "(домашние или с API-ключом). Поисковые запросы и ключи не уходят никогда.",
            13f, DIM).apply { setPadding(0, dp(8), 0, 0); setLineSpacing(0f, 1.15f) }
        box.addView(shareOnText)
        box.addView(shareOffText)
        box.addView(tv(if (narrow) "Включить — кнопка «Общий список» выше." else "Включить — кнопка «Общий список» справа вверху.",
            12f, MUTED).apply { setPadding(0, dp(12), 0, 0) })
        updateSwitch()
        return box
    }

    private fun syncShort(): String? = Store.lastSync(this).takeIf { it > 0 }?.let {
        java.text.SimpleDateFormat("dd.MM в HH:mm", java.util.Locale.US).format(java.util.Date(it))
    }

    /** One settings card: title and explanation on the left, a control on the right. */
    private fun settingRow(title: String, sub: TextView, control: View, onClick: (() -> Unit)? = null): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(14))
            background = if (onClick != null) states(
                focused = shape(PANEL2, Color.WHITE, dp(2), dp(12)), normal = shape(PANEL, LINE, dp(1), dp(12)))
            else shape(PANEL, LINE, dp(1), dp(12))
            if (onClick != null) {
                isFocusable = true
                isClickable = true
                setOnClickListener { onClick() }
            }
        }
        row.addView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = if (narrow) LinearLayout.LayoutParams(MATCH, WRAP)
            else LinearLayout.LayoutParams(0, WRAP, 1f).apply { rightMargin = dp(16) }
            addView(tv(title, 15f, TEXT, medium))
            addView(sub.apply { setPadding(0, dp(3), 0, 0) })
        })
        if (narrow) {
            // phone: the button goes under the text
            row.orientation = LinearLayout.VERTICAL
            row.gravity = Gravity.NO_GRAVITY
            control.layoutParams = LinearLayout.LayoutParams(WRAP, WRAP).apply { topMargin = dp(12) }
        } else {
            (control.layoutParams as? LinearLayout.LayoutParams)?.leftMargin = 0
        }
        row.addView(control)
        return row
    }

    private fun updateSwitch() {
        val on = Store.shareEnabled(this)
        switchPill?.apply {
            text = if (on) "ВКЛ" else "ВЫКЛ"
            setTextColor(if (on) Color.WHITE else MUTED)
            background = rounded(if (on) ACCENT else LINE, dp(12))
        }
        shareOnText?.setTextColor(if (on) TEXT else DIM)
        shareOffText?.setTextColor(if (on) DIM else TEXT)
    }

    private fun showSync() {
        val t = Store.lastSync(this)
        syncInfo?.text = if (!Store.shareEnabled(this)) "Выключено — только личные серверы"
        else "Обновляется сам раз в сутки" + if (t > 0)
            ", последний раз " + java.text.SimpleDateFormat("dd.MM в HH:mm", java.util.Locale.US)
                .format(java.util.Date(t)) else ""
    }

    /** Either-or: switching off removes everything public, switching on brings it back. */
    private fun onCommunityToggle(on: Boolean) {
        if (on) {
            // switching on also starts sending, so it is confirmed just like switching off
            AlertDialog.Builder(this)
                .setTitle("Включить общий список?")
                .setMessage("Серверы из общего списка будут приходить сами. Взамен в общий список " +
                    "уйдут рабочие публичные адреса, которые вы добавили: адрес, число русскоязычных " +
                    "раздач на нём, язык системы и анонимный номер установки. Поисковые запросы, домашние " +
                    "адреса и ключи не уходят никогда.")
                .setPositiveButton("Включить") { _, _ ->
                    Store.setShareEnabled(this, true)
                    reopenLoadPage()
                    syncInfo?.text = "Загружаю общий список…"
                    Thread {
                        Community.restorePublic(applicationContext)
                        ui.post { buildSources(); showSync(); renderCatalog(); refreshStatus() }
                    }.start()
                }
                .setNegativeButton("Отмена", null)
                .show()
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
                reopenLoadPage()
            }
            .setNegativeButton("Отмена", null)
            .show()
    }

    private fun reopenLoadPage() {
        if (page != Page.CATALOG) return
        showPage(Page.CATALOG)
        switchRow?.requestFocus()
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
            if (narrow) setPadding(dp(8), 0, dp(8), 0) else setPadding(dp(24), 0, dp(24), 0)
            addView(tv(title, 18f, Color.WHITE, medium).apply { gravity = Gravity.CENTER })
            val side = if (narrow) dp(110) else dp(180)
            addView(ImageView(this@MainActivity).apply {
                setImageResource(qr)
                layoutParams = LinearLayout.LayoutParams(side, side).apply { topMargin = dp(8); bottomMargin = dp(8) }
            })
            addView(tv(url.removePrefix("https://"), 13f, Color.LTGRAY).apply { gravity = Gravity.CENTER })
            addView(button("Открыть", null) { openLink(url) }.apply {
                (layoutParams as LinearLayout.LayoutParams).apply { leftMargin = 0; topMargin = dp(8) }
            })
        }
        box.addView(column("Boosty", BOOSTY, R.drawable.qr_boosty))
        box.addView(column("Ko-fi", KOFI, R.drawable.qr_kofi))
        val wrap = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(tv(if (narrow) "Нажмите «Открыть» под нужным сервисом. Спасибо!"
                else "Наведите камеру телефона на QR-код. Спасибо!", 14f, Color.LTGRAY).apply {
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

    // ---------- status ----------

    private fun refreshStatus() {
        showUpdate()
        navCounts[Page.SOURCES]?.text = Store.sources(this).size.let { if (it > 0) "$it" else "" }
        navCounts[Page.CATALOG]?.text = if (Store.shareEnabled(this)) catalogEntries().size.let { if (it > 0) "$it" else "" } else ""
        when (page) {
            Page.SOURCES -> refreshSources()
            Page.UPDATES -> Updater.ready(this).let { r ->
                installRow?.visibility = if (r != null) View.VISIBLE else View.GONE
                if (r != null) installTitle?.text = "Доступна версия ${r.version}"
            }
            Page.LOG -> logText?.text = RequestLog.all().joinToString("\n").ifEmpty { "Пока ничего — запросы появятся, когда плеер начнёт искать" }
            else -> {}
        }
    }

    private fun lanIp(): String? = try {
        NetworkInterface.getNetworkInterfaces().toList()
            .flatMap { it.inetAddresses.toList() }
            .firstOrNull { it is Inet4Address && !it.isLoopbackAddress && it.isSiteLocalAddress }
            ?.hostAddress
    } catch (e: Exception) {
        null
    }

    // ---------- self-update ----------

    private fun showUpdate() {
        val r = Updater.ready(this)
        if (r == null) {
            updateCard.visibility = View.GONE
            return
        }
        updateTitle.text = "Доступна версия ${r.version}"
        updateCard.visibility = View.VISIBLE
    }

    private fun onUpdatePressed() {
        val hint = try { Updater.install(this) } catch (e: Exception) { "Не удалось начать установку: ${e.message}" }
        if (hint != null) Toast.makeText(this, hint, Toast.LENGTH_LONG).show()
    }

    // ---------- building blocks ----------

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun tv(s: CharSequence, size: Float, color: Int, face: Typeface? = null) = TextView(this).apply {
        text = s
        textSize = size
        setTextColor(color)
        if (face != null) typeface = face
        includeFontPadding = true
    }

    /** Icon and text together in the middle of a stretched button (gravity alone leaves the icon at the edge). */
    private fun TextView.centerContent() {
        addOnLayoutChangeListener { v, l, _, r, _, _, _, _, _ ->
            val tvw = v as TextView
            val icon = tvw.compoundDrawables[0]?.bounds?.width()?.plus(tvw.compoundDrawablePadding) ?: 0
            val side = maxOf(dp(8), ((r - l) - icon - tvw.paint.measureText(tvw.text.toString()).toInt()) / 2)
            if (tvw.paddingLeft != side) v.post { tvw.setPadding(side, tvw.paddingTop, side, tvw.paddingBottom) }
        }
    }

    private fun TextView.singleLine() {
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
    }

    /** Small uppercase column / tile caption. */
    private fun caption(s: String) = tv(s.uppercase(), 10f, DIM).apply { letterSpacing = 0.1f }

    private fun tag(s: String) = TextView(this).apply {
        text = s
        textSize = 10f
        letterSpacing = 0.03f
        maxLines = 1
        setTextColor(MUTED)
        background = shape(Color.TRANSPARENT, LINE, dp(1), dp(4))
        setPadding(dp(5), dp(2), dp(5), dp(2))
        layoutParams = LinearLayout.LayoutParams(WRAP, WRAP).apply { rightMargin = dp(6) }
    }

    private fun dot(color: Int) = View(this).apply {
        background = circle(color)
        layoutParams = LinearLayout.LayoutParams(dp(8), dp(8))
    }

    private fun circle(color: Int) = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(color) }

    private fun rounded(color: Int, radius: Int) = GradientDrawable().apply { cornerRadius = radius.toFloat(); setColor(color) }

    private fun shape(fill: Int, stroke: Int, strokeW: Int, radius: Int) = GradientDrawable().apply {
        cornerRadius = radius.toFloat()
        setColor(fill)
        if (strokeW > 0) setStroke(strokeW, stroke)
    }

    private fun states(focused: GradientDrawable, normal: GradientDrawable, selected: GradientDrawable? = null) =
        StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_focused), focused)
            addState(intArrayOf(android.R.attr.state_pressed), focused)
            if (selected != null) addState(intArrayOf(android.R.attr.state_selected), selected)
            addState(intArrayOf(), normal)
        }

    /** Table row: invisible until the remote is on it, then a lighter fill with a white frame. */
    private fun rowBg() = states(focused = shape(PANEL2, Color.WHITE, dp(2), dp(8)), normal = shape(Color.TRANSPARENT, 0, 0, dp(8)))

    private fun button(label: String, icon: Int?, primary: Boolean = false, onClick: (View) -> Unit) = TextView(this).apply {
        text = label
        textSize = 14f
        typeface = medium
        setTextColor(if (primary) Color.WHITE else TEXT)
        gravity = Gravity.CENTER_VERTICAL
        isFocusable = true
        isClickable = true
        setPadding(dp(16), dp(10), dp(16), dp(10))
        val fill = if (primary) ACCENT else PANEL2
        background = states(focused = shape(fill, Color.WHITE, dp(2), dp(8)), normal = shape(fill, if (primary) ACCENT else LINE, dp(1), dp(8)))
        if (icon != null) {
            val d = getDrawable(icon)!!.mutate()
            d.setBounds(0, 0, dp(16), dp(16))
            d.setTint(if (primary) Color.WHITE else TEXT)
            setCompoundDrawables(d, null, null, null)
            compoundDrawablePadding = dp(8)
        }
        layoutParams = LinearLayout.LayoutParams(WRAP, WRAP).apply { leftMargin = dp(10) }
        setOnClickListener(onClick)
    }

    private fun chip(label: String) = TextView(this).apply {
        text = label
        textSize = 13f
        isFocusable = true
        isClickable = true
        setPadding(dp(14), dp(7), dp(14), dp(7))
        setTextColor(ColorStateList(arrayOf(intArrayOf(android.R.attr.state_selected), intArrayOf()), intArrayOf(BG, MUTED)))
        background = StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_focused, android.R.attr.state_selected), shape(TEXT, ACCENT, dp(2), dp(16)))
            addState(intArrayOf(android.R.attr.state_focused), shape(PANEL2, Color.WHITE, dp(2), dp(16)))
            addState(intArrayOf(android.R.attr.state_selected), shape(TEXT, TEXT, dp(1), dp(16)))
            addState(intArrayOf(), shape(Color.TRANSPARENT, LINE, dp(1), dp(16)))
        }
        layoutParams = LinearLayout.LayoutParams(WRAP, WRAP).apply { rightMargin = dp(8) }
    }
}
