package dev.net64.paperboat.companion

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import java.util.concurrent.Executors

/**
 * The game's own art for the bottom screen, as Star Haven draws it on the 3DS:
 * pause-menu icons and labels, partner portraits, the world map, item icons
 * and the dialogue font. Decoded from the game archive by CompanionArt.cpp on
 * a background thread and kept as bitmaps; until an image is there (the game
 * archive opens a moment after start) callers draw a plain fallback.
 */
class CompanionArt(private val onLoaded: () -> Unit) {

    private val loader = Executors.newSingleThreadExecutor { r -> Thread(r, "companion-art").apply { isDaemon = true } }
    private val art = HashMap<Int, Bitmap>()
    private val items = HashMap<Int, Bitmap>()
    private val pending = HashSet<Int>()
    // HD texture pack images: their pixels per original N64 pixel. They are
    // drawn at the original's size, so layouts stay put with a pack on.
    private val hd = java.util.IdentityHashMap<Bitmap, Float>()

    private fun scaleOf(b: Bitmap): Float = synchronized(hd) { hd[b] } ?: 1f

    /** Size of [b] in original N64 pixels. */
    fun widthOf(b: Bitmap): Float = b.width / scaleOf(b)

    fun heightOf(b: Bitmap): Float = b.height / scaleOf(b)
    private val retryAt = HashMap<Int, Long>()

    // ---- font ------------------------------------------------------------------
    @Volatile private var font: Bitmap? = null
    private var glyphCount = 0
    private var glyphRows = 0
    private var glyphCell = 16   // sheet pixels per glyph cell (64 with an HD pack)
    private var widths = ByteArray(0)
    private val codes = HashMap<Int, Int>()
    private var fontRequested = 0L

    private val pixel = Paint().apply { isFilterBitmap = false }
    private val tinted = Paint().apply { isFilterBitmap = false }
    private val fallback = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
    }
    private val src = Rect()
    private val dst = RectF()
    private val grey = ColorMatrixColorFilter(ColorMatrix().apply { setSaturation(0f) })

    fun close() = loader.shutdownNow()

    // A load that failed (the game had not opened its archive or loaded its
    // font yet) is retried on the next draw; make sure there is one.
    private val main = Handler(Looper.getMainLooper())
    private var retryPosted = false

    private fun retrySoon() {
        synchronized(main) {
            if (retryPosted) return
            retryPosted = true
        }
        main.postDelayed({
            synchronized(main) { retryPosted = false }
            onLoaded()
        }, RETRY_MS + 50)
    }

    // ---- images ------------------------------------------------------------------

    private var altOn: Boolean? = null
    private var altCheckedAt = 0L

    /** Call once a frame: reloads every image when HD textures are switched on or off. */
    fun checkHd() {
        val now = SystemClock.uptimeMillis()
        if (now - altCheckedAt < 2000) return
        altCheckedAt = now
        val on = CompanionBridge.altAssets()
        if (altOn != null && on != altOn) {
            synchronized(art) { art.clear() }
            synchronized(items) { items.clear() }
            synchronized(hd) { hd.clear() }
            synchronized(pending) { retryAt.clear() }
            font = null
            fontRequested = 0
        }
        altOn = on
    }

    /** The image [id] (an ART_* constant), or null while it is not loaded. */
    fun image(id: Int): Bitmap? = synchronized(art) { art[id] } ?: run { request(id, false); null }

    fun itemIcon(item: Int): Bitmap? = synchronized(items) { items[item] } ?: run { request(item, true); null }

    private fun request(key: Int, item: Boolean) {
        if (!CompanionBridge.available) return
        val k = if (item) key or ITEM_KEY else key
        val now = SystemClock.uptimeMillis()
        synchronized(pending) {
            if (k in pending || now < (retryAt[k] ?: 0L)) return
            pending += k
        }
        loader.execute {
            val data = if (item) CompanionBridge.itemIcon(key) else CompanionBridge.art(key)
            val bmp = data?.let { toBitmap(it, 3) }
            if (bmp != null && data[2] > 1000) synchronized(hd) { hd[bmp] = data[2] / 1000f }
            synchronized(pending) {
                pending -= k
                if (bmp == null) retryAt[k] = SystemClock.uptimeMillis() + RETRY_MS
            }
            if (bmp == null) {
                Log.d(TAG, "art ${if (item) "item " else ""}$key not ready")
                retrySoon()
            }
            if (bmp != null) {
                if (item) synchronized(items) { items[key] = bmp } else synchronized(art) { art[key] = bmp }
                onLoaded()
            }
        }
    }

    private fun toBitmap(data: IntArray, head: Int): Bitmap? {
        if (data.size < head) return null
        val w = data[0]
        val h = data[1]
        if (w <= 0 || h <= 0 || data.size < head + w * h) return null
        return Bitmap.createBitmap(data, head, w, w, h, Bitmap.Config.ARGB_8888)
    }

    /** Draws [b] at (x, y) scaled by [scale]; [dim] greys it out, [alpha] fades it. */
    fun draw(c: Canvas, b: Bitmap, x: Float, y: Float, scale: Float = 1f, dim: Boolean = false, alpha: Int = 255) {
        dst.set(x, y, x + widthOf(b) * scale, y + heightOf(b) * scale)
        pixel.colorFilter = if (dim) grey else null
        pixel.alpha = alpha
        pixel.isFilterBitmap = scaleOf(b) > 1f
        c.drawBitmap(b, null, dst, pixel)
        pixel.isFilterBitmap = false
        pixel.colorFilter = null
        pixel.alpha = 255
    }

    /** Draws [b] fitted into a [size]-square box at (x, y), keeping its shape. */
    fun drawFit(c: Canvas, b: Bitmap, x: Float, y: Float, size: Float, dim: Boolean = false, alpha: Int = 255) {
        val s = size / maxOf(widthOf(b), heightOf(b))
        draw(c, b, x + (size - widthOf(b) * s) / 2f, y + (size - heightOf(b) * s) / 2f, s, dim, alpha)
    }

    /** Draws [b] as a solid silhouette of [color] (Star Haven's tinted map markers). */
    fun drawTinted(c: Canvas, b: Bitmap, x: Float, y: Float, scale: Float, color: Int) {
        dst.set(x, y, x + widthOf(b) * scale, y + heightOf(b) * scale)
        tinted.colorFilter = PorterDuffColorFilter(color, PorterDuff.Mode.MULTIPLY)
        tinted.isFilterBitmap = scaleOf(b) > 1f
        c.drawBitmap(b, null, dst, tinted)
        tinted.isFilterBitmap = false
        tinted.colorFilter = null
    }

    /** Draws part of [b] ([sx], [sy], [sw], [sh] in original N64 pixels) into a rectangle. */
    fun drawPart(c: Canvas, b: Bitmap, sx: Int, sy: Int, sw: Int, sh: Int, d: RectF) {
        val k = scaleOf(b)
        src.set((sx * k).toInt(), (sy * k).toInt(), ((sx + sw) * k).toInt(), ((sy + sh) * k).toInt())
        pixel.isFilterBitmap = k > 1f
        c.drawBitmap(b, src, d, pixel)
        pixel.isFilterBitmap = false
    }

    // ---- text in the game's font ------------------------------------------------------

    private fun ensureFont(): Bitmap? {
        font?.let { return it }
        val now = SystemClock.uptimeMillis()
        if (!CompanionBridge.available || now < fontRequested + RETRY_MS) return null
        fontRequested = now
        loader.execute {
            val data = CompanionBridge.font()
            val w = CompanionBridge.fontWidths()
            val table = CompanionBridge.charset()
            val bmp = data?.let { toBitmap(it, 4) }
            if (data == null || w == null || table == null || bmp == null) {
                Log.d(TAG, "font not ready: ${data != null} ${w != null} ${table != null}")
                retrySoon()
                return@execute
            }
            val map = HashMap<Int, Int>()
            table.split('\u0001').forEachIndexed { code, s ->
                if (s.isNotEmpty() && s.codePointCount(0, s.length) == 1) map.putIfAbsent(s.codePointAt(0), code)
            }
            synchronized(codes) {
                codes.clear()
                codes.putAll(map)
                widths = w
                glyphCount = data[2]
                glyphRows = (glyphCount + 15) / 16
                glyphCell = 16 * data[3] / 1000
            }
            font = bmp
            onLoaded()
        }
        return null
    }

    private fun code(cp: Int): Int = codes[cp] ?: codes[fold(cp)] ?: -1

    // Characters the charset lacks: the nearest it has.
    private fun fold(cp: Int): Int = when (cp) {
        '’'.code, '‘'.code -> '\''.code
        '“'.code, '”'.code -> '"'.code
        '…'.code -> '.'.code
        else -> cp
    }

    private fun advance(code: Int): Int =
        if (code in widths.indices) widths[code].toInt() and 0xFF else SPACE_WIDTH

    /** Width of [s] at [scale], in grid pixels. */
    fun width(s: String, scale: Float): Float {
        if (ensureFont() == null) {
            fallback.textSize = FALLBACK_SIZE * scale
            return fallback.measureText(s)
        }
        var w = 0f
        var i = 0
        while (i < s.length) {
            val cp = s.codePointAt(i)
            i += Character.charCount(cp)
            val code = code(cp)
            w += (if (code >= 0) advance(code) else SPACE_WIDTH) * scale
        }
        return w
    }

    /**
     * Draws [s] with its top-left at (x, y), 16 * [scale] tall. [white] picks the
     * white palette (for dark backgrounds); [alpha] fades it. Returns the end x.
     */
    fun text(c: Canvas, x: Float, y: Float, s: String, scale: Float, white: Boolean = false, alpha: Int = 255): Float {
        val f = ensureFont()
        if (f == null) {
            fallback.textSize = FALLBACK_SIZE * scale
            fallback.color = if (white) Color.WHITE else FALLBACK_INK
            fallback.alpha = alpha
            c.drawText(s, x, y + 12f * scale, fallback)
            return x + fallback.measureText(s)
        }
        tinted.colorFilter = null
        tinted.alpha = alpha
        tinted.isFilterBitmap = glyphCell > 16
        var cx = x
        var i = 0
        val k = glyphCell / 16
        val half = glyphRows * glyphCell
        while (i < s.length) {
            val cp = s.codePointAt(i)
            i += Character.charCount(cp)
            val code = code(cp)
            if (code < 0 || code >= glyphCount) {
                cx += SPACE_WIDTH * scale
                continue
            }
            val adv = advance(code)
            val cw = minOf(adv + 2, 16)
            val gx = (code % 16) * glyphCell
            val gy = (if (white) half else 0) + (code / 16) * glyphCell
            src.set(gx, gy, gx + cw * k, gy + glyphCell)
            dst.set(cx, y, cx + cw * scale, y + 16f * scale)
            c.drawBitmap(f, src, dst, tinted)
            cx += adv * scale
        }
        tinted.alpha = 255
        tinted.isFilterBitmap = false
        return cx
    }

    fun textCentered(c: Canvas, cx: Float, y: Float, s: String, scale: Float, white: Boolean = false, alpha: Int = 255) {
        text(c, cx - width(s, scale) / 2f, y, s, scale, white, alpha)
    }

    fun textRight(c: Canvas, right: Float, y: Float, s: String, scale: Float, white: Boolean = false, alpha: Int = 255) {
        text(c, right - width(s, scale), y, s, scale, white, alpha)
    }

    /** The largest scale up to [scale] (not under [min]) at which [s] fits [avail]. */
    fun fit(s: String, avail: Float, scale: Float, min: Float): Float {
        val w = width(s, scale)
        return if (w <= avail || w == 0f) scale else maxOf(min, scale * avail / w)
    }

    /** Splits [s] into lines no wider than [w] at [scale]. */
    fun wrap(s: String, w: Float, scale: Float): List<String> {
        val out = ArrayList<String>()
        for (para in s.split('\n')) {
            var line = ""
            for (word in para.split(' ')) {
                val cand = if (line.isEmpty()) word else "$line $word"
                if (line.isNotEmpty() && width(cand, scale) > w) {
                    out += line
                    line = word
                } else {
                    line = cand
                }
            }
            out += line
        }
        return out
    }

    companion object {
        // Keep in step with CompanionArt.cpp.
        const val HEART = 0
        const val FLOWER = 1
        const val COIN = 2
        const val STAR_POINT = 3
        const val STAR_PIECE = 4
        const val BP = 5
        const val MARIO = 6
        const val TIME = 7
        const val POW_STAR = 8
        const val POW_EMPTY = 9
        const val LABEL_STATS = 10
        const val LABEL_MAP = 11
        const val LABEL_PARTY = 12
        const val LABEL_ITEMS = 13
        const val LABEL_BADGES = 14
        const val MAP_WALK0 = 15
        const val MAP_WALK1 = 16
        const val MAP_LOCATION = 17
        const val WORLD_MAP = 18
        const val RANK = 19
        const val PARTNER = 32
        const val PARTNER_DISABLED = 48

        // item ids (include/item_enum.h)
        const val ITEM_BOOTS = 0x001
        const val ITEM_HAMMER = 0x004
        const val ITEM_FRYING_PAN = 0x023
        const val ITEM_MUSHROOM = 0x08A
        const val ITEM_STAR_PIECE = 0x15C

        private const val TAG = "PaperboatCompanion"
        private const val ITEM_KEY = 1 shl 20
        private const val RETRY_MS = 3000L
        private const val SPACE_WIDTH = 5
        private const val FALLBACK_SIZE = 12f
        private val FALLBACK_INK = Color.rgb(48, 48, 48)
    }
}
