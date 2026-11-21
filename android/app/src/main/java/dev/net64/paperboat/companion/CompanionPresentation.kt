package dev.net64.paperboat.companion

import android.app.Presentation
import android.content.Context
import android.os.Bundle
import android.view.Display
import android.view.WindowManager
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

/**
 * The bottom screen on a second physical display (dual-screen handhelds,
 * an external monitor). It is an ordinary view tree: no second GL context, no
 * copy of the game's frames, so it costs the GPU nothing beyond its own UI.
 */
class CompanionPresentation(
    outerContext: Context,
    display: Display,
    private val build: (Context) -> CompanionView,
) : Presentation(outerContext, display) {

    var view: CompanionView? = null
        private set

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val v = build(context)
        view = v
        setContentView(v)
        hideSystemBars()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars()
    }

    /** The whole second screen is the bottom screen: no navigation bar over it. */
    private fun hideSystemBars() {
        val w = window ?: return
        WindowCompat.setDecorFitsSystemWindows(w, false)
        WindowInsetsControllerCompat(w, w.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }
}
