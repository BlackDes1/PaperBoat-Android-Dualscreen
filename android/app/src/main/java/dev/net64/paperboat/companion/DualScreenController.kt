package dev.net64.paperboat.companion

import android.app.Activity
import android.content.Context
import android.content.pm.ActivityInfo
import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Display
import android.view.View
import android.view.ViewGroup
import android.widget.RelativeLayout
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Arranges the game (SDL's surface) and the bottom screen ([CompanionView]).
 *
 * The 3DS shows the game on a 400x240 top screen and the touch menu on a
 * 320x240 bottom screen, centred under it. On Android:
 *
 *  - With a second display (dual-screen handhelds, a monitor): the game fills
 *    this display and the bottom screen goes to the other one in a
 *    [CompanionPresentation].
 *  - Otherwise, both share this display. Portrait stacks them as on a 3DS;
 *    landscape puts them side by side, top screen first. "3DS" size keeps the
 *    exact 400:320 width ratio; "Large" gives the game every pixel the bottom
 *    screen does not need.
 *
 * Only the surface's layout changes: SDL reports the new size to the engine,
 * which rebuilds its framebuffers once and keeps drawing its own aspect ratio
 * and touch controls inside it, so input coordinates stay right without any
 * conversion here. The bottom screen does its own hit testing in 3DS pixels.
 */
class DualScreenController(private val activity: Activity) {

    val settings = CompanionSettings(activity)

    private val displayManager = activity.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
    private val handler = Handler(Looper.getMainLooper())

    private var root: ViewGroup? = null
    private var surface: View? = null
    private var docked: CompanionView? = null
    private var presentation: CompanionPresentation? = null
    private var started = false

    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) = apply()
        override fun onDisplayRemoved(displayId: Int) = apply()
        override fun onDisplayChanged(displayId: Int) = Unit
    }

    private val layoutListener = View.OnLayoutChangeListener { _, l, t, r, b, ol, ot, or, ob ->
        if (r - l != or - ol || b - t != ob - ot) handler.post { layoutSurface() }
    }

    /** Call once SDL has set its content view (after SDLActivity.onCreate). */
    fun attach() {
        // SDLActivity: content -> RelativeLayout (mLayout) -> SDLSurface first.
        val content = activity.findViewById<ViewGroup>(android.R.id.content)
        val layout = content.getChildAt(0) as? ViewGroup ?: return
        root = layout
        surface = layout.getChildAt(0)
        layout.addOnLayoutChangeListener(layoutListener)
        CompanionBridge.available = true
    }

    fun start() {
        if (started) return
        started = true
        displayManager.registerDisplayListener(displayListener, handler)
        apply()
    }

    fun stop() {
        if (!started) return
        started = false
        displayManager.unregisterDisplayListener(displayListener)
        dismissPresentation()
    }

    /** Re-reads the settings and rebuilds the arrangement. */
    fun apply() {
        applyOrientation()
        val external = externalDisplay()
        val mode = settings.mode
        val useExternal = external != null && (mode == CompanionSettings.Mode.AUTO || mode == CompanionSettings.Mode.SECOND_DISPLAY)

        if (useExternal) {
            removeDocked()
            showPresentation(external!!)
        } else {
            dismissPresentation()
            if (mode == CompanionSettings.Mode.OFF) removeDocked() else ensureDocked()
        }
        layoutSurface()
    }

    private fun applyOrientation() {
        activity.requestedOrientation = when (settings.orientation) {
            CompanionSettings.Orientation.AUTO -> ActivityInfo.SCREEN_ORIENTATION_FULL_USER
            // USER_* rather than SENSOR_*: SDLSurface.surfaceChanged() treats a
            // landscape-shaped surface under SENSOR_PORTRAIT (and the reverse) as
            // "not ready" and stops rendering, and a stacked top screen is exactly
            // that shape.
            CompanionSettings.Orientation.LANDSCAPE -> ActivityInfo.SCREEN_ORIENTATION_USER_LANDSCAPE
            CompanionSettings.Orientation.PORTRAIT -> ActivityInfo.SCREEN_ORIENTATION_USER_PORTRAIT
        }
    }

    /** SDL resets the orientation when its window is created; put ours back. */
    fun reapplyOrientationSoon() {
        handler.postDelayed({ applyOrientation() }, 500)
    }

    private fun externalDisplay(): Display? =
        displayManager.getDisplays(DisplayManager.DISPLAY_CATEGORY_PRESENTATION)
            .firstOrNull { it.displayId != activity.display()?.displayId && it.isValid }

    private fun Activity.display(): Display? =
        if (android.os.Build.VERSION.SDK_INT >= 30) display else @Suppress("DEPRECATION") windowManager.defaultDisplay

    private fun newView(context: Context) = CompanionView(context, settings)

    private fun ensureDocked() {
        val layout = root ?: return
        if (docked != null) return
        val v = newView(activity)
        docked = v
        layout.addView(v, RelativeLayout.LayoutParams(0, 0))
    }

    private fun removeDocked() {
        docked?.let { root?.removeView(it) }
        docked = null
    }

    private fun showPresentation(display: Display) {
        if (presentation?.display?.displayId == display.displayId) return
        dismissPresentation()
        val p = CompanionPresentation(activity, display) { ctx -> newView(ctx).also { it.fill = false } }
        try {
            p.show()
            presentation = p
            Log.i(TAG, "Bottom screen on display ${display.displayId} (${display.name})")
        } catch (e: Exception) {
            // WindowManager.InvalidDisplayException and friends: stay single-screen.
            Log.w(TAG, "Could not use display ${display.displayId}", e)
            ensureDocked()
        }
    }

    private fun dismissPresentation() {
        presentation?.let { runCatching { it.dismiss() } }
        presentation = null
    }

    /** Positions the game surface and the docked bottom screen for the current size. */
    private fun layoutSurface() {
        val layout = root ?: return
        val game = surface ?: return
        val w = layout.width
        val h = layout.height
        if (w <= 0 || h <= 0) return

        val panel = docked
        if (panel == null) {
            place(game, 0, 0, w, h)
            return
        }

        val portrait = h > w
        if (portrait) {
            // Top screen over bottom screen, 400:320 wide and 240 tall each.
            if (settings.original3ds) {
                val s = min(w / 400f, h / 480f)
                val topW = (400 * s).roundToInt()
                val screenH = (240 * s).roundToInt()
                val botW = (320 * s).roundToInt()
                val y0 = ((h - 2 * screenH) / 2f).roundToInt().coerceAtLeast(0)
                place(game, (w - topW) / 2, y0, topW, screenH)
                place(panel, (w - botW) / 2, y0 + screenH, botW, screenH)
            } else {
                // The bottom screen keeps 4:3 at full width; the game gets the rest.
                val botH = min((w * 3f / 4f).roundToInt(), h / 2)
                place(game, 0, 0, w, h - botH)
                place(panel, 0, h - botH, w, botH)
            }
        } else {
            val swap = settings.swapSides
            if (settings.original3ds) {
                val s = min(w / 720f, h / 240f)
                val topW = (400 * s).roundToInt()
                val botW = (320 * s).roundToInt()
                val screenH = (240 * s).roundToInt()
                val x0 = ((w - topW - botW) / 2f).roundToInt().coerceAtLeast(0)
                val y0 = ((h - screenH) / 2f).roundToInt().coerceAtLeast(0)
                if (swap) {
                    place(panel, x0, y0, botW, screenH)
                    place(game, x0 + botW, y0, topW, screenH)
                } else {
                    place(game, x0, y0, topW, screenH)
                    place(panel, x0 + topW, y0, botW, screenH)
                }
            } else {
                // The bottom screen 4:3 at full height, capped at 40% of the width.
                val botW = min((h * 4f / 3f).roundToInt(), (w * 0.4f).roundToInt())
                val botH = (botW * 3f / 4f).roundToInt()
                if (swap) {
                    place(panel, 0, (h - botH) / 2, botW, botH)
                    place(game, botW, 0, w - botW, h)
                } else {
                    place(game, 0, 0, w - botW, h)
                    place(panel, w - botW, (h - botH) / 2, botW, botH)
                }
            }
        }
    }

    private fun place(v: View, x: Int, y: Int, width: Int, height: Int) {
        val lp = (v.layoutParams as? RelativeLayout.LayoutParams) ?: RelativeLayout.LayoutParams(width, height)
        if (lp.width == width && lp.height == height && lp.leftMargin == x && lp.topMargin == y && v.layoutParams === lp) return
        lp.width = width
        lp.height = height
        lp.leftMargin = x
        lp.topMargin = y
        v.layoutParams = lp
    }

    private companion object {
        const val TAG = "PaperboatDualScreen"
    }
}
