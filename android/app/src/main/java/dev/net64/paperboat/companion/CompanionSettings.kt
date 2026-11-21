package dev.net64.paperboat.companion

import android.content.Context

/**
 * How the two screens are arranged. Stored in SharedPreferences so the choice
 * survives restarts; the engine's own settings stay in paperboat.cfg.json.
 */
class CompanionSettings(context: Context) {

    enum class Mode(val label: String) {
        /** A second display when there is one, otherwise both screens on this one. */
        AUTO("Auto"),
        /** Both screens on the main display, arranged by orientation. */
        SINGLE_DISPLAY("This display"),
        /** The bottom screen on a second display (falls back to AUTO without one). */
        SECOND_DISPLAY("Second display"),
        /** Game only, as before dual-screen support. */
        OFF("Off"),
    }

    enum class Orientation(val label: String) { AUTO("Auto-rotate"), LANDSCAPE("Landscape"), PORTRAIT("Portrait") }

    private val prefs = context.applicationContext.getSharedPreferences("companion", Context.MODE_PRIVATE)

    var mode: Mode
        get() = enumValue(prefs.getString(KEY_MODE, null), Mode.AUTO)
        set(value) = prefs.edit().putString(KEY_MODE, value.name).apply()

    var orientation: Orientation
        get() = enumValue(prefs.getString(KEY_ORIENTATION, null), Orientation.AUTO)
        set(value) = prefs.edit().putString(KEY_ORIENTATION, value.name).apply()

    /** True: the 3DS's 400x240 over 320x240 proportions. False: the game takes all the room it can. */
    var original3ds: Boolean
        get() = prefs.getBoolean(KEY_ORIGINAL, true)
        set(value) = prefs.edit().putBoolean(KEY_ORIGINAL, value).apply()

    /** Landscape: bottom screen on the left instead of the right. */
    var swapSides: Boolean
        get() = prefs.getBoolean(KEY_SWAP, false)
        set(value) = prefs.edit().putBoolean(KEY_SWAP, value).apply()

    var lastTab: Int
        get() = prefs.getInt(KEY_TAB, 0)
        set(value) = prefs.edit().putInt(KEY_TAB, value).apply()

    private inline fun <reified T : Enum<T>> enumValue(name: String?, default: T): T =
        name?.let { n -> enumValues<T>().firstOrNull { it.name == n } } ?: default

    private companion object {
        const val KEY_MODE = "mode"
        const val KEY_ORIENTATION = "orientation"
        const val KEY_ORIGINAL = "original3ds"
        const val KEY_SWAP = "swapSides"
        const val KEY_TAB = "tab"
    }
}
