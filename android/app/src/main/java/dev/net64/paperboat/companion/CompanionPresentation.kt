package dev.net64.paperboat.companion

import android.app.Presentation
import android.content.Context
import android.os.Bundle
import android.view.Display
import android.view.WindowManager

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
    }
}
