package dev.net64.paperboat.companion

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
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
 * Like Star Haven (port/3ds/source/platform/bottom_screen.c) it is built from
 * the game's own art — pause-menu icons, partner portraits, the world map,
 * item icons and the dialogue font (see [CompanionArt]) — on the pause menu's
 * orange and cream. Everything is laid out on the 3DS's 320x240 grid and
 * scaled to the view, so the proportions match the 3DS on any display; with
 * [fill] the grid stretches to the view instead of letterboxing.
 *
 * A rail of tabs runs down the left, the page beside it, a row of buttons
 * underneath. Data comes from [CompanionBridge] as JSON, polled while shown.
 */
class CompanionView(
    context: Context,
    private val settings: CompanionSettings,
) : View(context) {

    var fill = false
        set(value) {
            field = value
            invalidate()
        }

    private val art = CompanionArt { postInvalidate() }

    // ---- tabs ----------------------------------------------------------------
    private enum class Tab(val label: String, val page: Int) {
        STATS("Stats", CompanionBridge.PAGE_STATS),
        MAP("Map", CompanionBridge.PAGE_STATS),
        PARTY("Party", CompanionBridge.PAGE_STATS),
        TATTLE("Tattle", CompanionBridge.PAGE_TATTLE),
        RECIPES("Recipes", CompanionBridge.PAGE_RECIPES),
        BADGES("Badges", CompanionBridge.PAGE_BADGES),
        ITEMS("Items", CompanionBridge.PAGE_ITEMS),
    }

    private val railTabs = Tab.entries

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
    private var tattlePage = 0

    private var scroll = 0f
    private var maxScroll = 0f

    private var mapX = -1f
    private var mapY = -1f
    private var mapFollow = true
    private var mapLabel = ""

    // ---- geometry (3DS bottom-screen pixels, as Star Haven) ----------------------
    private val gridW = 320f
    private val gridH = 240f
    private val railX1 = 50f
    private val railY0 = 6f
    private val tabH = 200f / railTabs.size
    private val panel = RectF(railX1, 4f, 316f, 206f)
    private val cx0 = 58f
    private val cx1 = 308f
    private val headY = 10f
    private val subY = 28f
    private val bodyY = 46f
    private val btnY0 = 212f
    private val btnY1 = 234f

    private var scaleX = 1f
    private var scaleY = 1f
    private var offX = 0f
    private var offY = 0f

    // ---- paints ------------------------------------------------------------------
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val bg = Paint()
    private var bgReady = false
    private val tmp = RectF()

    // Hit regions of the current frame, in grid pixels.
    private data class Hit(val rect: RectF, val action: () -> Unit)
    private val hits = ArrayList<Hit>()

    // ---- polling -------------------------------------------------------------------
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
            if (battle && !wasBattle) selectTab(Tab.STATS)
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
        mapFollow = true
        mapLabel = ""
        refresh()
        invalidate()
    }

    // ---- drawing primitives (Star Haven's) -------------------------------------------

    private fun round(x0: Float, y0: Float, x1: Float, y1: Float, r: Float, color: Int, c: Canvas) {
        fillPaint.color = color
        tmp.set(x0, y0, x1, y1)
        c.drawRoundRect(tmp, r, r, fillPaint)
    }

    /** Shadow, brown rim, cream face: the pause menu's panels. */
    private fun panelBox(c: Canvas, x0: Float, y0: Float, x1: Float, y1: Float, face: Int) {
        round(x0 + 3, y0 + 3, x1 + 3, y1 + 3, 8f, COL_SHADOW, c)
        round(x0, y0, x1, y1, 8f, COL_PANEL_EDGE, c)
        round(x0 + 2, y0 + 2, x1 - 2, y1 - 2, 6f, face, c)
    }

    private fun card(c: Canvas, x0: Float, y0: Float, x1: Float, y1: Float, face: Int = COL_CARD) {
        round(x0, y0, x1, y1, 6f, COL_CARD_RIM, c)
        round(x0 + 1, y0 + 1, x1 - 1, y1 - 1, 5f, face, c)
    }

    private fun tile(c: Canvas, x0: Float, y0: Float, x1: Float, y1: Float, r: Float, face: Int) {
        round(x0 + 2, y0 + 2, x1 + 2, y1 + 2, r, COL_SHADOW, c)
        round(x0, y0, x1, y1, r, COL_PANEL_EDGE, c)
        round(x0 + 2, y0 + 2, x1 - 2, y1 - 2, r - 2, face, c)
    }

    /** A brown button with a white label. */
    private fun chip(
        c: Canvas, x0: Float, y0: Float, x1: Float, y1: Float, label: String, scale: Float,
        on: Boolean = true, lit: Boolean = false,
    ) {
        round(x0, y0, x1, y1, 7f, if (on) COL_PANEL_EDGE else COL_BTN_OFF, c)
        round(x0 + 2, y0 + 2, x1 - 2, y1 - 2, 5f, if (lit) COL_GOLD else if (on) COL_BTN else COL_BTN_OFF, c)
        val s = art.fit(label, x1 - x0 - 6f, scale, scale * 0.6f)
        art.textCentered(c, (x0 + x1) / 2f, (y0 + y1) / 2f - 8f * s, label, s, white = !lit, alpha = if (on) 255 else 150)
    }

    private fun bar(c: Canvas, x: Float, y: Float, w: Float, h: Float, v: Int, max: Int, col: Int) {
        round(x - 1, y - 1, x + w + 1, y + h + 1, h / 2 + 1, COL_PANEL_EDGE, c)
        round(x, y, x + w, y + h, h / 2, COL_BAR_BACK, c)
        if (max > 0 && v > 0) {
            val fw = max(h, w * min(v, max) / max.toFloat())
            round(x, y, x + fw, y + h, h / 2, col, c)
        }
    }

    private fun image(c: Canvas, id: Int, x: Float, y: Float, scale: Float = 1f, dim: Boolean = false, alpha: Int = 255): Boolean {
        val b = art.image(id) ?: return false
        art.draw(c, b, x, y, scale, dim, alpha)
        return true
    }

    private fun icon(c: Canvas, id: Int, x: Float, y: Float, size: Float, dim: Boolean = false): Boolean {
        val b = art.image(id) ?: return false
        art.drawFit(c, b, x, y, size, dim)
        return true
    }

    private fun itemIcon(c: Canvas, item: Int, x: Float, y: Float, size: Float, dim: Boolean = false, alpha: Int = 255): Boolean {
        val b = art.itemIcon(item) ?: return false
        art.drawFit(c, b, x, y, size, dim, alpha)
        return true
    }

    private fun pageTitle(c: Canvas, title: String, sub: String?) {
        art.text(c, cx0 + 2, headY, title, art.fit(title, 160f, 1.05f, 0.7f))
        if (sub != null) art.text(c, cx0 + 2, subY, sub, art.fit(sub, cx1 - cx0 - 4, 0.8f, 0.55f), alpha = 200)
    }

    // ---- frame ------------------------------------------------------------------------

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        bgReady = false
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

        if (!bgReady) {
            bg.shader = LinearGradient(0f, 0f, 0f, gridH, COL_BG_TOP, COL_BG_BOTTOM, Shader.TileMode.CLAMP)
            bgReady = true
        }
        canvas.drawRect(0f, 0f, gridW, gridH, bg)

        hits.clear()
        art.checkHd()
        panelBox(canvas, panel.left, panel.top, panel.right, panel.bottom, COL_PANEL)
        drawPage(canvas)
        drawRail(canvas)
        drawButtons(canvas)
        canvas.restore()
    }

    private fun inGame() = stats.optInt("inGame") == 1

    /** After the panel: the active tab runs into the panel's rim. */
    private fun drawRail(c: Canvas) {
        val inGame = inGame()
        railTabs.forEachIndexed { i, t ->
            val on = t == tab
            val y0 = railY0 + i * tabH
            val y1 = y0 + tabH - 1f
            val x0 = if (on) 4f else 7f
            val x1 = if (on) railX1 + 4f else railX1 - 2f
            round(x0 + 2, y0 + 2, x1 + 2, y1 + 2, 7f, COL_SHADOW, c)
            round(x0, y0, x1, y1, 7f, COL_PANEL_EDGE, c)
            round(x0 + 2, y0 + 2, if (on) x1 else x1 - 2, y1 - 2, 5f, if (on) COL_PANEL else COL_TAB, c)
            val cx = (x0 + railX1 - 2f) / 2f + if (on) 1f else 0f
            val iconSize = tabH - 13f
            val enabled = inGame
            when (t) {
                Tab.STATS -> icon(c, CompanionArt.MARIO, cx - iconSize / 2, y0 + 2, iconSize, !enabled)
                Tab.MAP -> icon(c, CompanionArt.MAP_LOCATION, cx - iconSize / 2, y0 + 2, iconSize, !enabled)
                Tab.PARTY -> icon(c, CompanionArt.PARTNER + 2, cx - iconSize / 2, y0 + 2, iconSize, !enabled)
                Tab.TATTLE -> icon(c, CompanionArt.PARTNER + 1, cx - iconSize / 2, y0 + 2, iconSize, !enabled)
                Tab.RECIPES -> itemIcon(c, CompanionArt.ITEM_FRYING_PAN, cx - iconSize / 2, y0 + 2, iconSize, !enabled)
                Tab.BADGES -> icon(c, CompanionArt.BP, cx - iconSize / 2, y0 + 2, iconSize, !enabled)
                Tab.ITEMS -> itemIcon(c, CompanionArt.ITEM_MUSHROOM, cx - iconSize / 2, y0 + 2, iconSize, !enabled)
            }
            val s = art.fit(t.label, railX1 - 8f, 0.62f, 0.45f)
            art.textCentered(c, cx, y1 - 2f - 15f * s, t.label, s, alpha = if (enabled) 255 else 120)
            if (enabled) hits += Hit(RectF(0f, y0, railX1 + 2f, y1)) { selectTab(t) }
        }
    }

    /**
     * Fast forward, then the shortcuts the 3DS screen offers in the world:
     * Items, Partner, Pause. Those three are greyed out whenever the game would
     * refuse them (the prologue's intro, cutscenes, battles, menus already open).
     */
    private fun drawButtons(c: Canvas) {
        val menus = inGame() && stats.optInt("menus", 1) == 1 && stats.optInt("battle") == 0
        val gap = 4f
        val w = (gridW - 16f - gap * 3) / 4f
        fun button(i: Int, label: String, on: Boolean, lit: Boolean = false, action: () -> Unit) {
            val x0 = 8f + i * (w + gap)
            chip(c, x0, btnY0, x0 + w, btnY1, label, 0.8f, on, lit)
            if (on) hits += Hit(RectF(x0, btnY0, x0 + w, btnY1), action)
        }
        val ff = CompanionBridge.fastForward
        button(0, if (ff > 1) ">> ${ff}x" else ">> 1x", true, lit = ff > 1) {
            // Off, 2x, 3x, 4x, off
            CompanionBridge.fastForward = if (ff >= 4) 1 else ff + 1
            invalidate()
        }
        button(1, "Items", menus) { CompanionBridge.request(CompanionBridge.REQ_ITEM_MENU) }
        button(2, "Partner", menus) { CompanionBridge.request(CompanionBridge.REQ_PARTNER_MENU) }
        button(3, "Pause", menus) { CompanionBridge.request(CompanionBridge.REQ_PAUSE) }
    }

    private fun drawPage(c: Canvas) {
        if (!inGame()) {
            drawTitle(c)
            return
        }
        when (tab) {
            Tab.STATS -> drawStats(c)
            Tab.MAP -> drawMap(c)
            Tab.PARTY -> drawParty(c)
            Tab.TATTLE -> if (tattleOpen >= 0) drawTattleText(c) else drawTattle(c)
            Tab.RECIPES -> drawRecipes(c)
            Tab.BADGES -> drawBadges(c)
            Tab.ITEMS -> drawItems(c)
        }
    }

    private fun drawTitle(c: Canvas) {
        val mid = (cx0 + cx1) / 2f
        icon(c, CompanionArt.MARIO, mid - 16f, 64f, 32f)
        art.textCentered(c, mid, 104f, "Paper Mario", 1.2f)
        art.textCentered(c, mid, 130f, "Load a file to fill this screen.", 0.75f, alpha = 200)
    }

    // ---- Stats ---------------------------------------------------------------------------

    private fun chapterLabel(ch: Int) = when (ch) {
        0 -> "Prologue"
        9 -> "Epilogue"
        else -> "Chapter $ch"
    }

    private fun drawStats(c: Canvas) {
        val battle = stats.optInt("battle") == 1
        val location = if (battle) "Battle!" else stats.optString("location")
        art.text(c, cx0 + 2, headY, location, art.fit(location, 188f, 1.05f, 0.7f))

        // chapter, then Star Power as the HUD's little stars
        var x = art.text(c, cx0 + 2, subY, chapterLabel(stats.optInt("chapter")), 0.8f, alpha = 200) + 8f
        val power = stats.optInt("starPower")
        val maxPower = stats.optInt("maxStarPower")
        for (i in 0 until min(maxPower, 7)) {
            image(c, if (i < power / 256) CompanionArt.POW_STAR else CompanionArt.POW_EMPTY, x + i * 9f, subY + 4f)
        }

        val sec = stats.optInt("playSeconds")
        val time = "%d:%02d:%02d".format(sec / 3600, (sec / 60) % 60, sec % 60)
        val tw = art.width(time, 0.8f)
        image(c, CompanionArt.TIME, cx1 - tw - 18f, subY - 1f)
        art.text(c, cx1 - tw, subY, time, 0.8f)

        // HP and FP
        for (k in 0..1) {
            val x0 = cx0 + k * 126f
            val v = stats.optInt(if (k == 0) "hp" else "fp")
            val m = stats.optInt(if (k == 0) "maxHp" else "maxFp")
            card(c, x0, bodyY, x0 + 120f, bodyY + 30f)
            image(c, if (k == 0) CompanionArt.HEART else CompanionArt.FLOWER, x0 + 5f, bodyY + 3f)
            art.text(c, x0 + 23f, bodyY + 3f, if (k == 0) "HP" else "FP", 0.9f)
            art.textRight(c, x0 + 114f, bodyY + 3f, "$v/$m", 0.9f)
            bar(c, x0 + 6f, bodyY + 20f, 108f, 5f, v, m, if (k == 0) COL_HP else COL_FP)
        }

        // level, BP, star points, coins, star pieces
        val icons = intArrayOf(CompanionArt.MARIO, CompanionArt.BP, CompanionArt.STAR_POINT, CompanionArt.COIN, CompanionArt.STAR_PIECE)
        val keys = arrayOf("level", "bp", "starPoints", "coins", "starPieces")
        for (k in 0 until 5) {
            val x0 = cx0 + k * 50.4f
            card(c, x0, 81f, x0 + 46f, 101f)
            image(c, icons[k], x0 + 3f, 83f)
            val v = stats.optInt(keys[k]).toString()
            art.textRight(c, x0 + 42f, 84f, v, art.fit(v, 22f, 0.8f, 0.6f))
        }

        // partner and equipment
        card(c, cx0, 106f, cx1, 202f, COL_AREA)
        val partner = stats.optInt("partner")
        val partners = stats.optJSONArray("partners") ?: JSONArray()
        var partnerName = ""
        var rank = 0
        for (i in 0 until partners.length()) {
            val p = partners.getJSONObject(i)
            if (p.optInt("id") == partner) {
                partnerName = p.optString("name")
                rank = p.optInt("rank")
            }
        }
        if (partner > 0 && icon(c, CompanionArt.PARTNER + partner, cx0 + 8f, 114f, 40f)) {
            if (rank > 0) image(c, CompanionArt.RANK, cx0 + 40f, 142f)
        }
        art.text(c, cx0 + 56f, 116f, if (partner > 0) partnerName else "No partner yet", 0.9f)
        if (partner > 0) {
            art.text(c, cx0 + 56f, 134f, when (rank) { 1 -> "Super rank"; 2 -> "Ultra rank"; else -> "Partner" }, 0.7f, alpha = 200)
        }
        val boots = stats.optInt("boots")
        val hammer = stats.optInt("hammer")
        var ex = cx0 + 10f
        if (boots >= 0 && itemIcon(c, CompanionArt.ITEM_BOOTS + boots, ex, 162f, 28f)) ex += 34f
        if (hammer >= 0 && itemIcon(c, CompanionArt.ITEM_HAMMER + hammer, ex, 162f, 28f)) ex += 34f
        art.text(c, ex + 4f, 168f, "Equipment", 0.75f, alpha = 200)
    }

    // ---- Map -----------------------------------------------------------------------------

    private val mapView = RectF(57f, 11f, 309f, 199f)

    private fun drawMap(c: Canvas) {
        val spaces = stats.optJSONArray("spaces") ?: JSONArray()
        val current = stats.optInt("space", -1)
        val v = mapView
        if (mapFollow && current >= 0) {
            spaces.optJSONObject(current)?.let {
                mapX = it.optInt("x").toFloat()
                mapY = it.optInt("y").toFloat()
            }
        }
        if (mapX < 0) {
            mapX = 160f
            mapY = 160f
        }
        // keep the view inside the 320x320 map
        mapX = mapX.coerceIn(v.width() / 2, 320f - v.width() / 2)
        mapY = mapY.coerceIn(v.height() / 2, 320f - v.height() / 2)
        val mx0 = mapX - v.width() / 2
        val my0 = mapY - v.height() / 2

        round(v.left - 2, v.top - 2, v.right + 2, v.bottom + 2, 6f, COL_PANEL_EDGE, c)
        val map = art.image(CompanionArt.WORLD_MAP)
        if (map != null) {
            art.drawPart(c, map, mx0.toInt(), my0.toInt(), v.width().toInt(), v.height().toInt(), v)
        } else {
            round(v.left, v.top, v.right, v.bottom, 4f, COL_CARD, c)
        }
        c.save()
        c.clipRect(v)
        val loc = art.image(CompanionArt.MAP_LOCATION)
        for (i in 0 until spaces.length()) {
            val o = spaces.getJSONObject(i)
            if (o.optInt("visited") == 0) continue
            val x = v.left + o.optInt("x") - mx0
            val y = v.top + o.optInt("y") - my0
            if (loc != null) art.drawTinted(c, loc, x - 8f, y - 8f, 1f, COL_MARKER)
            else round(x - 3, y - 3, x + 3, y + 3, 3f, COL_MARKER, c)
            val name = o.optString("name")
            hits += Hit(RectF(x - 9f, y - 9f, x + 9f, y + 9f)) { mapLabel = name; invalidate() }
        }
        if (current >= 0) {
            spaces.optJSONObject(current)?.let { o ->
                val x = v.left + o.optInt("x") - mx0
                val y = v.top + o.optInt("y") - my0
                val walk = if ((SystemClock.uptimeMillis() / 270) % 2 == 0L) CompanionArt.MAP_WALK0 else CompanionArt.MAP_WALK1
                if (!image(c, walk, x - 12f, y - 20f)) round(x - 4, y - 4, x + 4, y + 4, 4f, COL_HP, c)
            }
            postInvalidateDelayed(270)
        }
        c.restore()

        // where Mario is (or the place tapped)
        val label = mapLabel.ifEmpty { spaces.optJSONObject(current)?.optString("name") ?: stats.optString("location") }
        if (label.isNotEmpty()) {
            val cw = art.width(label, 0.85f) + 14f
            round(v.left + 4, v.top + 4, v.left + 4 + cw, v.top + 22, 7f, COL_DARK_CHIP, c)
            art.text(c, v.left + 10, v.top + 6, label, 0.85f, white = true)
        }
        if (!mapFollow) {
            chip(c, v.right - 62f, v.bottom - 24f, v.right - 4f, v.bottom - 4f, "Centre", 0.75f)
            hits += Hit(RectF(v.right - 62f, v.bottom - 24f, v.right - 4f, v.bottom - 4f)) {
                mapFollow = true
                mapLabel = ""
                invalidate()
            }
        } else {
            round(v.left + 4, v.bottom - 24, v.left + 112, v.bottom - 4, 7f, COL_DARK_CHIP, c)
            art.text(c, v.left + 10, v.bottom - 22, "Drag to look", 0.7f, white = true)
        }
    }

    // ---- Party ---------------------------------------------------------------------------

    private fun drawParty(c: Canvas) {
        val partners = stats.optJSONArray("partners") ?: JSONArray()
        val cur = stats.optInt("partner")
        var curName = ""
        var owned = 0
        for (i in 0 until partners.length()) {
            val p = partners.getJSONObject(i)
            if (p.optInt("owned") == 1) owned++
            if (p.optInt("id") == cur) curName = p.optString("name")
        }
        pageTitle(c, "Party", if (cur > 0) "$curName is with you" else "No partner yet")
        for (i in 0 until min(partners.length(), 8)) {
            val p = partners.getJSONObject(i)
            val id = p.optInt("id")
            val has = p.optInt("owned") == 1
            val x0 = cx0 + (i % 4) * 63.5f
            val y0 = bodyY + (i / 4) * 74f
            val x1 = x0 + 59f
            val y1 = y0 + 68f
            tile(c, x0, y0, x1, y1, 8f, when { id == cur -> COL_SEL; has -> COL_PANEL; else -> COL_OFF })
            if (!icon(c, (if (has) CompanionArt.PARTNER else CompanionArt.PARTNER_DISABLED) + id, (x0 + x1) / 2 - 18f, y0 + 6f, 36f)) {
                round((x0 + x1) / 2 - 14f, y0 + 8f, (x0 + x1) / 2 + 14f, y0 + 38f, 6f, COL_CARD, c)
            }
            if (has && p.optInt("rank") > 0) image(c, CompanionArt.RANK, x1 - 18f, y0 + 30f)
            val name = if (has) p.optString("name") else "???"
            art.textCentered(c, (x0 + x1) / 2f, y0 + 47f, name, art.fit(name, 52f, 0.8f, 0.5f), alpha = if (has) 255 else 140)
            if (has && id != cur) hits += Hit(RectF(x0, y0, x1, y1)) { CompanionBridge.request(CompanionBridge.REQ_PARTNER_BASE + id) }
        }
        if (owned > 1) art.textCentered(c, (cx0 + cx1) / 2f, 191f, "Tap a partner to switch", 0.7f, alpha = 200)
    }

    // ---- lists -------------------------------------------------------------------------------

    /** A scrolling column of rows under the page title. */
    private fun drawList(
        c: Canvas, count: Int, rowH: Float,
        row: (Canvas, Int, RectF) -> Unit, tap: ((Int) -> Unit)? = null,
    ) {
        val list = RectF(cx0, bodyY, cx1, 201f)
        maxScroll = max(0f, count * rowH - list.height())
        scroll = scroll.coerceIn(0f, maxScroll)
        c.save()
        c.clipRect(list)
        val first = (scroll / rowH).toInt()
        val last = min(count - 1, ((scroll + list.height()) / rowH).toInt())
        for (i in first..last) {
            val top = list.top + i * rowH - scroll
            val r = RectF(list.left, top, list.right - 6f, top + rowH - 2f)
            row(c, i, r)
            if (tap != null) {
                val visible = RectF(r.left, max(r.top, list.top), r.right, min(r.bottom, list.bottom))
                hits += Hit(visible) { tap(i) }
            }
        }
        c.restore()
        if (maxScroll > 0f) {
            val trackH = list.height()
            val thumbH = max(14f, trackH * trackH / (count * rowH))
            val thumbY = list.top + (trackH - thumbH) * (scroll / maxScroll)
            round(list.right - 3f, list.top, list.right, list.bottom, 1.5f, COL_CARD_RIM, c)
            round(list.right - 3f, thumbY, list.right, thumbY + thumbH, 1.5f, COL_PANEL_EDGE, c)
        }
    }

    private fun drawTattle(c: Canvas) {
        val list = pageJson.optJSONArray("list") ?: JSONArray()
        pageTitle(c, "Tattle log", "${pageJson.optInt("known")} of ${pageJson.optInt("total")} enemies")
        if (list.length() == 0) {
            art.textCentered(c, (cx0 + cx1) / 2f, 110f, "Goombario has tattled nothing yet.", 0.75f, alpha = 200)
            return
        }
        drawList(c, list.length(), 20f,
            row = { cv, i, r ->
                card(cv, r.left, r.top, r.right, r.bottom)
                val name = list.getJSONObject(i).optString("name")
                art.text(cv, r.left + 6f, r.top + 2f, name, art.fit(name, r.width() - 12f, 0.8f, 0.55f))
            },
            tap = { i ->
                val o = list.getJSONObject(i)
                tattleOpen = o.optInt("type")
                tattleName = o.optString("name")
                tattleText = null
                tattlePage = 0
                CompanionBridge.wantTattle(tattleOpen)
                invalidate()
            })
    }

    private fun drawTattleText(c: Canvas) {
        pageTitle(c, tattleName, null)
        chip(c, cx1 - 50f, headY - 2f, cx1, headY + 18f, "Back", 0.75f)
        hits += Hit(RectF(cx1 - 54f, headY - 6f, cx1 + 4f, headY + 22f)) { tattleOpen = -1; invalidate() }
        card(c, cx0, bodyY - 14f, cx1, 202f, COL_AREA)
        val body = tattleText ?: "..."
        val scale = 0.72f
        val lines = art.wrap(body, cx1 - cx0 - 14f, scale)
        val perPage = 12
        val pages = max(1, (lines.size + perPage - 1) / perPage)
        tattlePage = tattlePage.coerceIn(0, pages - 1)
        var y = bodyY - 10f
        for (line in lines.drop(tattlePage * perPage).take(perPage)) {
            art.text(c, cx0 + 7f, y, line, scale)
            y += 13f
        }
        if (pages > 1) {
            chip(c, cx1 - 50f, 182f, cx1 - 6f, 199f, "${tattlePage + 1}/$pages", 0.6f)
            hits += Hit(RectF(cx1 - 50f, 180f, cx1 - 6f, 201f)) { tattlePage = (tattlePage + 1) % pages; invalidate() }
        }
    }

    private fun drawRecipes(c: Canvas) {
        val list = pageJson.optJSONArray("list") ?: JSONArray()
        pageTitle(c, "Recipes", "${pageJson.optInt("cooked")} of ${pageJson.optInt("total")} dishes cooked")
        drawList(c, list.length(), 22f, row = { cv, i, r ->
            val o = list.getJSONObject(i)
            val done = o.optInt("done") == 1
            card(cv, r.left, r.top, r.right, r.bottom, if (done) COL_CARD else COL_OFF)
            itemIcon(cv, o.optInt("item"), r.left + 3f, r.top + 1f, 18f, dim = !done, alpha = if (done) 255 else 110)
            val name = if (done) o.optString("name") else "???"
            art.text(cv, r.left + 25f, r.top + 3f, name, art.fit(name, r.width() - 30f, 0.8f, 0.55f), alpha = if (done) 255 else 140)
        })
    }

    private fun drawBadges(c: Canvas) {
        val list = pageJson.optJSONArray("list") ?: JSONArray()
        pageTitle(c, "Badges", "BP ${pageJson.optInt("bpUsed")} of ${pageJson.optInt("bpMax")} used")
        if (list.length() == 0) {
            art.textCentered(c, (cx0 + cx1) / 2f, 110f, "No badges yet.", 0.75f, alpha = 200)
            return
        }
        drawList(c, list.length(), 22f, row = { cv, i, r ->
            val o = list.getJSONObject(i)
            val on = o.optInt("on") == 1
            card(cv, r.left, r.top, r.right, r.bottom, if (on) COL_SEL else COL_CARD)
            itemIcon(cv, o.optInt("item"), r.left + 3f, r.top + 1f, 18f)
            val bp = "${o.optInt("bp")}"
            val bw = art.width(bp, 0.75f)
            image(cv, CompanionArt.BP, r.right - bw - 22f, r.top + 2f)
            art.textRight(cv, r.right - 4f, r.top + 3f, bp, 0.75f)
            val name = o.optString("name")
            art.text(cv, r.left + 25f, r.top + 3f, name, art.fit(name, r.width() - 56f - bw, 0.8f, 0.5f))
        })
    }

    private fun drawItems(c: Canvas) {
        data class Row(val title: String?, val item: Int, val name: String)
        val rows = ArrayList<Row>()
        fun add(title: String, key: String) {
            val a = pageJson.optJSONArray(key) ?: return
            if (a.length() == 0) return
            rows += Row(title, 0, "")
            for (i in 0 until a.length()) {
                val o = a.getJSONObject(i)
                rows += Row(null, o.optInt("item"), o.optString("name"))
            }
        }
        add("Items", "items")
        add("Key items", "keyItems")
        add("Stored", "stored")
        pageTitle(c, "Items", null)
        if (rows.isEmpty()) {
            art.textCentered(c, (cx0 + cx1) / 2f, 110f, "Mario carries nothing yet.", 0.75f, alpha = 200)
            return
        }
        drawList(c, rows.size, 22f, row = { cv, i, r ->
            val row = rows[i]
            if (row.title != null) {
                art.text(cv, r.left + 2f, r.top + 3f, row.title, 0.85f)
            } else {
                card(cv, r.left + 6f, r.top, r.right, r.bottom)
                itemIcon(cv, row.item, r.left + 9f, r.top + 1f, 18f)
                art.text(cv, r.left + 31f, r.top + 3f, row.name, art.fit(row.name, r.width() - 36f, 0.8f, 0.55f))
            }
        })
    }

    // ---- touch ------------------------------------------------------------------------------
    private var downX = 0f
    private var downY = 0f
    private var lastX = 0f
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
                lastX = gx
                lastY = gy
                dragging = false
            }
            MotionEvent.ACTION_MOVE -> {
                if (!dragging && (abs(gy - downY) > DRAG_SLOP || abs(gx - downX) > DRAG_SLOP)) dragging = true
                if (dragging) {
                    if (tab == Tab.MAP && mapView.contains(downX, downY)) {
                        mapFollow = false
                        mapX -= gx - lastX
                        mapY -= gy - lastY
                        invalidate()
                    } else if (maxScroll > 0f) {
                        scroll = (scroll - (gy - lastY)).coerceIn(0f, maxScroll)
                        invalidate()
                    }
                }
                lastX = gx
                lastY = gy
            }
            MotionEvent.ACTION_UP -> {
                if (!dragging) {
                    // Last drawn hits win: the rail and buttons draw over the page.
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

        // Star Haven's palette (bottom_screen.c)
        val COL_BG_TOP = Color.rgb(250, 196, 110)
        val COL_BG_BOTTOM = Color.rgb(222, 130, 60)
        val COL_PANEL = Color.rgb(255, 247, 222)
        val COL_PANEL_EDGE = Color.rgb(116, 72, 40)
        val COL_SHADOW = Color.argb(90, 80, 40, 10)
        val COL_TAB = Color.rgb(205, 160, 105)
        val COL_HP = Color.rgb(232, 56, 48)
        val COL_FP = Color.rgb(64, 176, 232)
        val COL_BAR_BACK = Color.rgb(150, 120, 90)
        val COL_BTN = Color.argb(230, 116, 72, 40)
        val COL_BTN_OFF = Color.argb(110, 116, 72, 40)
        val COL_CARD = Color.rgb(250, 236, 204)
        val COL_CARD_RIM = Color.rgb(200, 160, 110)
        val COL_AREA = Color.rgb(255, 242, 214)
        val COL_SEL = Color.rgb(255, 214, 120)
        val COL_OFF = Color.rgb(200, 185, 165)
        val COL_DARK_CHIP = Color.argb(200, 60, 36, 20)
        val COL_MARKER = Color.rgb(220, 80, 30)
        val COL_GOLD = Color.rgb(240, 176, 40)
    }
}
