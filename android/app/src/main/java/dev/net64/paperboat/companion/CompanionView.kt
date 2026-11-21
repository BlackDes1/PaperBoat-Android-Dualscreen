package dev.net64.paperboat.companion

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * The bottom screen: Star Haven's 3DS touch menu, redrawn for Android.
 *
 * Everything is laid out on the 3DS's 320x240 grid and scaled to the view, so
 * the proportions match the 3DS whatever the display. With [fill] the panel
 * stretches its width to the view instead of letterboxing (second displays
 * whose shape is far from 4:3).
 *
 * A rail of tabs runs down the left; the panel beside it shows the tab's page.
 * Data comes from [CompanionBridge] as JSON, polled while the view is shown.
 */
class CompanionView(
    context: Context,
    private val settings: CompanionSettings,
    private val onOptionsChanged: () -> Unit,
) : View(context) {

    var fill = false
        set(value) {
            field = value
            invalidate()
        }

    // ---- tabs ----------------------------------------------------------------
    private enum class Tab(val label: String, val short: String, val page: Int) {
        STATS("Stats", "Stats", CompanionBridge.PAGE_STATS),
        MAP("Map", "Map", CompanionBridge.PAGE_STATS),
        PARTY("Party", "Party", CompanionBridge.PAGE_STATS),
        TATTLE("Tattle", "Tattle", CompanionBridge.PAGE_TATTLE),
        RECIPES("Recipes", "Cook", CompanionBridge.PAGE_RECIPES),
        BADGES("Badges", "Badge", CompanionBridge.PAGE_BADGES),
        ITEMS("Items", "Items", CompanionBridge.PAGE_ITEMS),
        OPTIONS("Options", "Opt.", -1),
    }

    private var tab = Tab.entries.getOrElse(settings.lastTab) { Tab.STATS }

    // ---- data ----------------------------------------------------------------
    private var stats = JSONObject()
    private var pageJson = JSONObject()
    private var lastStatsText = ""
    private var lastPageText = ""
    private var wasBattle = false

    private var tattleOpen = -1
    private var tattleName = ""
    private var tattleText: String? = null

    private var scroll = 0f
    private var maxScroll = 0f

    // ---- geometry (3DS bottom-screen pixels) ----------------------------------
    private val gridW = 320f
    private val gridH = 240f
    private val railW = 46f
    private val tabH = gridH / Tab.entries.size
    private val panel = RectF(railW + 4f, 4f, gridW - 4f, gridH - 4f)
    private val buttonsH = 26f

    private var scaleX = 1f
    private var scaleY = 1f
    private var offX = 0f
    private var offY = 0f

    // ---- paints ----------------------------------------------------------------
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.5f
    }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        textSize = 11f
        color = COL_INK
    }
    private val small = Paint(text).apply {
        typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL)
        textSize = 9f
    }
    private val bg = Paint()
    private var bgHeight = -1f

    // Hit regions of the current frame, in grid pixels.
    private data class Hit(val rect: RectF, val action: () -> Unit)
    private val hits = ArrayList<Hit>()

    // ---- polling ---------------------------------------------------------------
    private val poll = object : Runnable {
        override fun run() {
            refresh()
            postDelayed(this, if (stats.optInt("battle") == 1) POLL_FAST_MS else POLL_MS)
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        removeCallbacks(poll)
        post(poll)
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(poll)
        super.onDetachedFromWindow()
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        removeCallbacks(poll)
        if (visibility == VISIBLE) post(poll)
    }

    private fun refresh() {
        var dirty = false
        val s = CompanionBridge.page(CompanionBridge.PAGE_STATS)
        if (s.isNotEmpty() && s != lastStatsText) {
            lastStatsText = s
            stats = runCatching { JSONObject(s) }.getOrDefault(JSONObject())
            dirty = true
            val battle = stats.optInt("battle") == 1
            // Like the 3DS: a battle brings up the status page.
            if (battle && !wasBattle && tab != Tab.OPTIONS) selectTab(Tab.STATS)
            wasBattle = battle
        }
        if (tab.page > CompanionBridge.PAGE_STATS) {
            val p = CompanionBridge.page(tab.page)
            if (p.isNotEmpty() && p != lastPageText) {
                lastPageText = p
                pageJson = runCatching { JSONObject(p) }.getOrDefault(JSONObject())
                dirty = true
            }
        }
        if (tattleOpen >= 0 && tattleText == null) {
            tattleText = CompanionBridge.tattleText(tattleOpen)
            if (tattleText == null) CompanionBridge.wantTattle(tattleOpen) else dirty = true
        }
        if (dirty) invalidate()
    }

    private fun selectTab(t: Tab) {
        if (t == tab) return
        tab = t
        settings.lastTab = t.ordinal
        scroll = 0f
        tattleOpen = -1
        lastPageText = ""
        pageJson = JSONObject()
        refresh()
        invalidate()
    }

    // ---- drawing -----------------------------------------------------------------

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        bgHeight = -1f
    }

    private fun computeTransform() {
        val sx = width / gridW
        val sy = height / gridH
        if (fill) {
            scaleX = sx
            scaleY = sy
            offX = 0f
            offY = 0f
        } else {
            val s = min(sx, sy)
            scaleX = s
            scaleY = s
            offX = (width - gridW * s) / 2f
            offY = (height - gridH * s) / 2f
        }
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(Color.BLACK)
        computeTransform()
        canvas.save()
        canvas.translate(offX, offY)
        canvas.scale(scaleX, scaleY)
        canvas.clipRect(0f, 0f, gridW, gridH)

        if (bgHeight != gridH) {
            bg.shader = LinearGradient(0f, 0f, 0f, gridH, COL_BG_TOP, COL_BG_BOTTOM, Shader.TileMode.CLAMP)
            bgHeight = gridH
        }
        canvas.drawRect(0f, 0f, gridW, gridH, bg)

        hits.clear()
        drawRail(canvas)
        drawPanel(canvas)
        canvas.restore()
    }

    private fun drawRail(c: Canvas) {
        val inGame = stats.optInt("inGame") == 1
        for (t in Tab.entries) {
            val top = t.ordinal * tabH
            val r = RectF(2f, top + 1.5f, railW + (if (t == tab) 4f else 0f), top + tabH - 1.5f)
            fillPaint.color = if (t == tab) COL_PANEL else COL_TAB
            c.drawRoundRect(r, 5f, 5f, fillPaint)
            val enabled = inGame || t == Tab.OPTIONS
            small.color = if (enabled) COL_INK else COL_INK_DIM
            small.textAlign = Paint.Align.CENTER
            c.drawText(t.short, (r.left + railW) / 2f, r.centerY() + 3f, small)
            small.textAlign = Paint.Align.LEFT
            small.color = COL_INK
            if (enabled) hits += Hit(r) { selectTab(t) }
        }
    }

    private fun drawPanel(c: Canvas) {
        fillPaint.color = COL_SHADOW
        c.drawRoundRect(RectF(panel.left + 2f, panel.top + 2f, panel.right + 2f, panel.bottom + 2f), 8f, 8f, fillPaint)
        fillPaint.color = COL_PANEL
        c.drawRoundRect(panel, 8f, 8f, fillPaint)
        strokePaint.color = COL_PANEL_EDGE
        c.drawRoundRect(panel, 8f, 8f, strokePaint)

        val inGame = stats.optInt("inGame") == 1
        if (tab == Tab.OPTIONS) {
            drawOptions(c)
            return
        }
        if (!inGame) {
            text.textAlign = Paint.Align.CENTER
            c.drawText("Paperboat", panel.centerX(), panel.centerY() - 8f, text)
            small.textAlign = Paint.Align.CENTER
            c.drawText("Load a file to fill this screen.", panel.centerX(), panel.centerY() + 8f, small)
            small.textAlign = Paint.Align.LEFT
            text.textAlign = Paint.Align.LEFT
            return
        }
        val body = RectF(panel.left + 6f, panel.top + 4f, panel.right - 6f, panel.bottom - buttonsH - 2f)
        when (tab) {
            Tab.STATS -> drawStats(c, body)
            Tab.MAP -> drawMap(c, body)
            Tab.PARTY -> drawParty(c, body)
            Tab.TATTLE -> if (tattleOpen >= 0) drawTattleText(c, body) else drawTattle(c, body)
            Tab.RECIPES -> drawRecipes(c, body)
            Tab.BADGES -> drawBadges(c, body)
            Tab.ITEMS -> drawItems(c, body)
            Tab.OPTIONS -> Unit
        }
        drawButtons(c)
    }

    /** Items / Partner / Pause: the shortcuts the 3DS screen offers in the world. */
    private fun drawButtons(c: Canvas) {
        val battle = stats.optInt("battle") == 1
        val labels = listOf(
            "Items" to CompanionBridge.REQ_ITEM_MENU,
            "Partner" to CompanionBridge.REQ_PARTNER_MENU,
            "Pause" to CompanionBridge.REQ_PAUSE,
        )
        val y0 = panel.bottom - buttonsH + 2f
        val w = (panel.width() - 16f) / labels.size
        labels.forEachIndexed { i, (label, req) ->
            val r = RectF(panel.left + 6f + i * (w + 2f), y0, panel.left + 6f + i * (w + 2f) + w, panel.bottom - 4f)
            fillPaint.color = if (battle) COL_BTN_OFF else COL_BTN
            c.drawRoundRect(r, 6f, 6f, fillPaint)
            small.color = Color.WHITE
            small.textAlign = Paint.Align.CENTER
            c.drawText(label, r.centerX(), r.centerY() + 3f, small)
            small.textAlign = Paint.Align.LEFT
            small.color = COL_INK
            if (!battle) hits += Hit(r) { CompanionBridge.request(req) }
        }
    }

    private fun bar(c: Canvas, x: Float, y: Float, w: Float, cur: Int, max: Int, color: Int) {
        val r = RectF(x, y, x + w, y + 7f)
        fillPaint.color = COL_BAR_BACK
        c.drawRoundRect(r, 3f, 3f, fillPaint)
        if (max > 0) {
            fillPaint.color = color
            c.drawRoundRect(RectF(x, y, x + w * (cur.coerceIn(0, max) / max.toFloat()), y + 7f), 3f, 3f, fillPaint)
        }
    }

    private fun drawStats(c: Canvas, b: RectF) {
        val battle = stats.optInt("battle") == 1
        var y = b.top + 10f
        val chapter = stats.optInt("chapter")
        val chapterText = when (chapter) {
            0 -> "Prologue"
            9 -> "Epilogue"
            else -> "Chapter $chapter"
        }
        c.drawText(if (battle) "Battle!" else stats.optString("location"), b.left, y, text)
        small.textAlign = Paint.Align.RIGHT
        c.drawText(chapterText, b.right, y, small)
        small.textAlign = Paint.Align.LEFT
        y += 14f

        val hp = stats.optInt("hp")
        val maxHp = stats.optInt("maxHp")
        val fp = stats.optInt("fp")
        val maxFp = stats.optInt("maxFp")
        c.drawText("HP", b.left, y + 6f, text)
        bar(c, b.left + 20f, y, b.width() - 70f, hp, maxHp, COL_HP)
        c.drawText("$hp/$maxHp", b.right - 46f, y + 7f, small)
        y += 14f
        c.drawText("FP", b.left, y + 6f, text)
        bar(c, b.left + 20f, y, b.width() - 70f, fp, maxFp, COL_FP)
        c.drawText("$fp/$maxFp", b.right - 46f, y + 7f, small)
        y += 16f

        // Star Power: one star per 256, partial star as a bar like the HUD.
        val power = stats.optInt("starPower")
        val maxPower = stats.optInt("maxStarPower")
        c.drawText("Star", b.left, y + 6f, small)
        for (i in 0 until maxPower) {
            val cx = b.left + 34f + i * 14f
            val level = ((power - i * 256) / 256f).coerceIn(0f, 1f)
            fillPaint.color = COL_BAR_BACK
            c.drawCircle(cx, y + 3f, 5.5f, fillPaint)
            if (level > 0f) {
                fillPaint.color = COL_STAR
                c.drawCircle(cx, y + 3f, 5.5f * level, fillPaint)
            }
        }
        y += 18f

        val playSeconds = stats.optInt("playSeconds")
        val rows = listOf(
            "Level" to stats.optInt("level").toString(),
            "Star Points" to stats.optInt("starPoints").toString(),
            "BP" to stats.optInt("bp").toString(),
            "Coins" to stats.optInt("coins").toString(),
            "Star Pieces" to stats.optInt("starPieces").toString(),
            "Boots / Hammer" to "${stats.optInt("boots") + 1} / ${stats.optInt("hammer") + 1}",
            "Play time" to "%d:%02d".format(playSeconds / 3600, (playSeconds / 60) % 60),
        )
        val colW = b.width() / 2f
        rows.forEachIndexed { i, (k, v) ->
            val x = b.left + (i % 2) * colW
            val yy = y + (i / 2) * 15f
            c.drawText(k, x, yy, small)
            small.textAlign = Paint.Align.RIGHT
            c.drawText(v, x + colW - 8f, yy, small)
            small.textAlign = Paint.Align.LEFT
        }
    }

    private fun drawMap(c: Canvas, b: RectF) {
        val spaces = stats.optJSONArray("spaces") ?: JSONArray()
        val current = stats.optInt("space", -1)
        // PauseMapSpaces live on the pause menu's 320x320 world map.
        val s = min(b.width() / 260f, (b.height() - 12f) / 200f)
        val ox = b.left - 20f * s
        val oy = b.top - 4f * s
        fun px(o: JSONObject) = ox + o.optInt("x") * s
        fun py(o: JSONObject) = oy + o.optInt("y") * s
        strokePaint.color = COL_PANEL_EDGE
        for (i in 0 until spaces.length()) {
            val o = spaces.getJSONObject(i)
            if (o.optInt("visited") == 0) continue
            val parent = spaces.optJSONObject(o.optInt("parent")) ?: continue
            if (parent.optInt("visited") == 0) continue
            c.drawLine(px(o), py(o), px(parent), py(parent), strokePaint)
        }
        val blink = (SystemClock.uptimeMillis() / 400) % 2 == 0L
        for (i in 0 until spaces.length()) {
            val o = spaces.getJSONObject(i)
            val visited = o.optInt("visited") == 1
            fillPaint.color = when {
                i == current -> if (blink) COL_HP else COL_STAR
                visited -> COL_FP
                else -> COL_BAR_BACK
            }
            val r = if (i == current) 5f else 3.5f
            c.drawCircle(px(o), py(o), r, fillPaint)
            if (visited) {
                val name = o.optString("name")
                hits += Hit(RectF(px(o) - 8f, py(o) - 8f, px(o) + 8f, py(o) + 8f)) { mapLabel = name; invalidate() }
            }
        }
        val label = if (current >= 0 && mapLabel.isEmpty()) spaces.optJSONObject(current)?.optString("name") ?: "" else mapLabel
        c.drawText(label, b.left, b.bottom - 2f, small)
        if (current >= 0) postInvalidateDelayed(400)
    }

    private var mapLabel = ""

    private fun drawParty(c: Canvas, b: RectF) {
        val partners = stats.optJSONArray("partners") ?: JSONArray()
        val curPartner = stats.optInt("partner")
        val cols = 2
        val rows = 4
        val cw = b.width() / cols
        val ch = b.height() / rows
        for (i in 0 until min(partners.length(), cols * rows)) {
            val p = partners.getJSONObject(i)
            val r = RectF(
                b.left + (i % cols) * cw + 2f, b.top + (i / cols) * ch + 2f,
                b.left + (i % cols + 1) * cw - 2f, b.top + (i / cols + 1) * ch - 2f,
            )
            val owned = p.optInt("owned") == 1
            val id = p.optInt("id")
            fillPaint.color = when {
                id == curPartner -> COL_SELECTED
                owned -> COL_CARD
                else -> COL_CARD_OFF
            }
            c.drawRoundRect(r, 6f, 6f, fillPaint)
            text.color = if (owned) COL_INK else COL_INK_DIM
            c.drawText(if (owned) p.optString("name") else "???", r.left + 6f, r.centerY() + 1f, text)
            text.color = COL_INK
            if (owned) {
                val rank = p.optInt("rank")
                val rankText = when (rank) { 1 -> "Super"; 2 -> "Ultra"; else -> "" }
                if (rankText.isNotEmpty()) c.drawText(rankText, r.left + 6f, r.bottom - 4f, small)
                if (id != curPartner) {
                    hits += Hit(r) { CompanionBridge.request(CompanionBridge.REQ_PARTNER_BASE + id) }
                }
            }
        }
    }

    /** A scrolling list of rows; returns nothing, registers taps per row. */
    private fun drawList(
        c: Canvas, b: RectF, header: String, count: Int, rowH: Float,
        row: (Canvas, Int, RectF) -> Unit, tap: ((Int) -> Unit)? = null,
    ) {
        c.drawText(header, b.left, b.top + 9f, text)
        val list = RectF(b.left, b.top + 14f, b.right, b.bottom)
        maxScroll = max(0f, count * rowH - list.height())
        scroll = scroll.coerceIn(0f, maxScroll)
        c.save()
        c.clipRect(list)
        val first = (scroll / rowH).toInt()
        val last = min(count - 1, ((scroll + list.height()) / rowH).toInt())
        for (i in first..last) {
            val top = list.top + i * rowH - scroll
            val r = RectF(list.left, top, list.right, top + rowH)
            if (i % 2 == 0) {
                fillPaint.color = COL_ROW
                c.drawRect(r, fillPaint)
            }
            row(c, i, r)
            if (tap != null) {
                val visible = RectF(r.left, max(r.top, list.top), r.right, min(r.bottom, list.bottom))
                hits += Hit(visible) { tap(i) }
            }
        }
        c.restore()
        if (maxScroll > 0f) {
            val trackH = list.height()
            val thumbH = max(12f, trackH * trackH / (count * rowH))
            val thumbY = list.top + (trackH - thumbH) * (scroll / maxScroll)
            fillPaint.color = COL_PANEL_EDGE
            c.drawRoundRect(RectF(list.right - 3f, thumbY, list.right, thumbY + thumbH), 1.5f, 1.5f, fillPaint)
        }
    }

    private fun drawTattle(c: Canvas, b: RectF) {
        val list = pageJson.optJSONArray("list") ?: JSONArray()
        drawList(c, b, "Tattle log  ${pageJson.optInt("known")}/${pageJson.optInt("total")}", list.length(), 13f,
            row = { cv, i, r -> cv.drawText(list.getJSONObject(i).optString("name"), r.left + 4f, r.bottom - 3f, small) },
            tap = { i ->
                val o = list.getJSONObject(i)
                tattleOpen = o.optInt("type")
                tattleName = o.optString("name")
                tattleText = null
                CompanionBridge.wantTattle(tattleOpen)
                invalidate()
            })
    }

    private fun drawTattleText(c: Canvas, b: RectF) {
        c.drawText(tattleName, b.left, b.top + 9f, text)
        val back = RectF(b.right - 40f, b.top, b.right, b.top + 12f)
        fillPaint.color = COL_BTN
        c.drawRoundRect(back, 4f, 4f, fillPaint)
        small.color = Color.WHITE
        small.textAlign = Paint.Align.CENTER
        c.drawText("Back", back.centerX(), back.bottom - 3f, small)
        small.textAlign = Paint.Align.LEFT
        small.color = COL_INK
        hits += Hit(back) { tattleOpen = -1; invalidate() }
        val body = tattleText ?: "…"
        var y = b.top + 26f
        for (line in wrap(body, b.width() - 4f, small)) {
            if (y > b.bottom) break
            c.drawText(line, b.left + 2f, y, small)
            y += 11f
        }
    }

    private fun drawRecipes(c: Canvas, b: RectF) {
        val list = pageJson.optJSONArray("list") ?: JSONArray()
        drawList(c, b, "Recipes  ${pageJson.optInt("cooked")}/${pageJson.optInt("total")}", list.length(), 13f,
            row = { cv, i, r ->
                val o = list.getJSONObject(i)
                val done = o.optInt("done") == 1
                small.color = if (done) COL_INK else COL_INK_DIM
                cv.drawText(if (done) o.optString("name") else "? ? ?", r.left + 14f, r.bottom - 3f, small)
                small.color = COL_INK
                if (done) cv.drawText("✓", r.left + 3f, r.bottom - 3f, small)
            })
    }

    private fun drawBadges(c: Canvas, b: RectF) {
        val list = pageJson.optJSONArray("list") ?: JSONArray()
        drawList(c, b, "Badges  BP ${pageJson.optInt("bpUsed")}/${pageJson.optInt("bpMax")}", list.length(), 13f,
            row = { cv, i, r ->
                val o = list.getJSONObject(i)
                if (o.optInt("on") == 1) {
                    fillPaint.color = COL_SELECTED
                    cv.drawRect(r, fillPaint)
                }
                cv.drawText(o.optString("name"), r.left + 4f, r.bottom - 3f, small)
                small.textAlign = Paint.Align.RIGHT
                cv.drawText("${o.optInt("bp")} BP", r.right - 6f, r.bottom - 3f, small)
                small.textAlign = Paint.Align.LEFT
            })
    }

    private fun drawItems(c: Canvas, b: RectF) {
        val rows = ArrayList<Pair<String, Boolean>>()
        fun add(title: String, key: String) {
            val a = pageJson.optJSONArray(key) ?: return
            rows += title to true
            for (i in 0 until a.length()) rows += a.getJSONObject(i).optString("name") to false
        }
        add("Items", "items")
        add("Key items", "keyItems")
        add("Stored", "stored")
        drawList(c, b, "Inventory", rows.size, 13f,
            row = { cv, i, r ->
                val (name, header) = rows[i]
                cv.drawText(name, r.left + (if (header) 2f else 10f), r.bottom - 3f, if (header) text else small)
            })
    }

    private fun drawOptions(c: Canvas) {
        val b = RectF(panel.left + 6f, panel.top + 4f, panel.right - 6f, panel.bottom - 4f)
        c.drawText("Dual screen", b.left, b.top + 10f, text)
        var y = b.top + 18f
        fun option(label: String, value: String, onTap: () -> Unit) {
            val r = RectF(b.left, y, b.right, y + 24f)
            fillPaint.color = COL_CARD
            c.drawRoundRect(r, 5f, 5f, fillPaint)
            c.drawText(label, r.left + 6f, r.centerY() + 3f, small)
            text.textAlign = Paint.Align.RIGHT
            c.drawText(value, r.right - 6f, r.centerY() + 4f, text)
            text.textAlign = Paint.Align.LEFT
            hits += Hit(r) { onTap(); onOptionsChanged(); invalidate() }
            y += 28f
        }
        option("Screens", settings.mode.label) {
            val all = CompanionSettings.Mode.entries
            settings.mode = all[(settings.mode.ordinal + 1) % all.size]
        }
        option("Orientation", settings.orientation.label) {
            val all = CompanionSettings.Orientation.entries
            settings.orientation = all[(settings.orientation.ordinal + 1) % all.size]
        }
        option("Size", if (settings.original3ds) "3DS (400x240 + 320x240)" else "Large game screen") {
            settings.original3ds = !settings.original3ds
        }
        option("Bottom screen side", if (settings.swapSides) "Left" else "Right") {
            settings.swapSides = !settings.swapSides
        }
        small.color = COL_INK_DIM
        c.drawText("Layout after Star Haven's 3DS bottom screen.", b.left, b.bottom - 4f, small)
        small.color = COL_INK
    }

    private fun wrap(s: String, width: Float, p: Paint): List<String> {
        val out = ArrayList<String>()
        var line = StringBuilder()
        for (word in s.split(' ')) {
            val candidate = if (line.isEmpty()) word else "$line $word"
            if (p.measureText(candidate) > width && line.isNotEmpty()) {
                out += line.toString()
                line = StringBuilder(word)
            } else {
                line = StringBuilder(candidate)
            }
        }
        if (line.isNotEmpty()) out += line.toString()
        return out
    }

    // ---- touch ---------------------------------------------------------------------
    private var downX = 0f
    private var downY = 0f
    private var lastY = 0f
    private var dragging = false

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        val gx = (e.x - offX) / scaleX
        val gy = (e.y - offY) / scaleY
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = gx
                downY = gy
                lastY = gy
                dragging = false
            }
            MotionEvent.ACTION_MOVE -> {
                if (!dragging && abs(gy - downY) > DRAG_SLOP) dragging = true
                if (dragging && maxScroll > 0f) {
                    scroll = (scroll - (gy - lastY)).coerceIn(0f, maxScroll)
                    invalidate()
                }
                lastY = gy
            }
            MotionEvent.ACTION_UP -> {
                if (!dragging) {
                    // Last drawn hits win: rows draw over the panel.
                    hits.lastOrNull { it.rect.contains(downX, downY) }?.let {
                        performClick()
                        it.action()
                    }
                }
            }
        }
        return true
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    private companion object {
        const val POLL_MS = 250L
        const val POLL_FAST_MS = 100L
        const val DRAG_SLOP = 6f

        val COL_BG_TOP = Color.rgb(250, 196, 110)
        val COL_BG_BOTTOM = Color.rgb(222, 130, 60)
        val COL_PANEL = Color.rgb(255, 247, 222)
        val COL_PANEL_EDGE = Color.rgb(116, 72, 40)
        val COL_SHADOW = Color.argb(90, 80, 40, 10)
        val COL_TAB = Color.rgb(205, 160, 105)
        val COL_HP = Color.rgb(232, 56, 48)
        val COL_FP = Color.rgb(64, 176, 232)
        val COL_STAR = Color.rgb(250, 210, 60)
        val COL_BAR_BACK = Color.rgb(150, 120, 90)
        val COL_BTN = Color.argb(230, 116, 72, 40)
        val COL_BTN_OFF = Color.argb(120, 116, 72, 40)
        val COL_INK = Color.rgb(60, 36, 20)
        val COL_INK_DIM = Color.rgb(160, 130, 100)
        val COL_CARD = Color.rgb(245, 225, 185)
        val COL_CARD_OFF = Color.rgb(225, 210, 185)
        val COL_SELECTED = Color.rgb(255, 225, 120)
        val COL_ROW = Color.argb(40, 116, 72, 40)
    }
}
