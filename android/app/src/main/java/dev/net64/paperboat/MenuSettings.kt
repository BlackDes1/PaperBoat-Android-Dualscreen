package dev.net64.paperboat

import kotlin.math.roundToInt

/**
 * The settings the launcher menu exposes, keyed by the same CVar names the
 * engine's ImGui menu writes (src/port/ui/PaperboatMenu*.cpp and
 * cmake/lus-cvars.cmake). Defaults mirror the engine's, so an untouched
 * setting shows what the game will actually use.
 *
 * Every setting is a list of steps: D-pad left/right walks it, A and a tap
 * advance it. That one model covers toggles, choices and sliders alike.
 */
sealed class MenuSetting(val key: String, val label: String, val help: String) {

    abstract fun stepCount(): Int
    abstract fun currentStep(config: GameConfig): Int
    abstract fun applyStep(config: GameConfig, step: Int)
    abstract fun describe(config: GameConfig): String

    /** Back to the default (Reset on the Settings page). */
    open fun reset(config: GameConfig) = config.reset(key)

    /** Stored as 0/1, like every engine checkbox. */
    class Toggle(key: String, label: String, help: String, private val default: Boolean) :
        MenuSetting(key, label, help) {
        override fun stepCount() = 2
        override fun currentStep(config: GameConfig) = if (config.getInt(key, if (default) 1 else 0) != 0) 1 else 0
        override fun applyStep(config: GameConfig, step: Int) = config.setInt(key, step)
        override fun describe(config: GameConfig) = if (currentStep(config) == 1) "On" else "Off"
    }

    /** A fixed set of integer values, e.g. MSAA samples or a combobox index. */
    class Choice(
        key: String,
        label: String,
        help: String,
        private val default: Int,
        private val options: List<Pair<Int, String>>,
    ) : MenuSetting(key, label, help) {
        override fun stepCount() = options.size
        override fun currentStep(config: GameConfig): Int {
            val value = config.getInt(key, default)
            return options.indexOfFirst { it.first == value }.takeIf { it >= 0 }
                ?: options.indexOfFirst { it.first == default }.coerceAtLeast(0)
        }
        override fun applyStep(config: GameConfig, step: Int) = config.setInt(key, options[step].first)
        override fun describe(config: GameConfig) = options[currentStep(config)].second
    }

    /** A float slider in fixed increments, shown as a percentage. */
    class Percent(
        key: String,
        label: String,
        help: String,
        private val default: Float,
        private val min: Float,
        private val max: Float,
        private val step: Float,
    ) : MenuSetting(key, label, help) {
        override fun stepCount() = ((max - min) / step).roundToInt() + 1
        override fun currentStep(config: GameConfig) =
            ((config.getFloat(key, default).coerceIn(min, max) - min) / step).roundToInt()
        override fun applyStep(config: GameConfig, step: Int) = config.setFloat(key, min + step * this.step)
        override fun describe(config: GameConfig) = "${(value(currentStep(config)) * 100).roundToInt()}%"
        private fun value(step: Int) = min + step * this.step
    }

    /** An integer slider in fixed increments. */
    class Range(
        key: String,
        label: String,
        help: String,
        private val default: Int,
        private val min: Int,
        private val max: Int,
        private val step: Int,
        private val suffix: String,
    ) : MenuSetting(key, label, help) {
        override fun stepCount() = (max - min) / step + 1
        override fun currentStep(config: GameConfig) = (config.getInt(key, default).coerceIn(min, max) - min) / step
        override fun applyStep(config: GameConfig, step: Int) = config.setInt(key, min + step * this.step)
        override fun describe(config: GameConfig) = "${min + currentStep(config) * step}$suffix"
    }
}

/**
 * A setting the Android side keeps itself (SharedPreferences), not the engine:
 * the dual-screen layout. [get] and [set] work on the option index.
 */
class PrefChoice(
    key: String,
    label: String,
    help: String,
    private val default: Int,
    private val options: List<String>,
    private val get: () -> Int,
    private val set: (Int) -> Unit,
) : MenuSetting(key, label, help) {
    override fun stepCount() = options.size
    override fun currentStep(config: GameConfig) = get().coerceIn(0, options.size - 1)
    override fun applyStep(config: GameConfig, step: Int) = set(step)
    override fun describe(config: GameConfig) = options[currentStep(config)]
    override fun reset(config: GameConfig) = set(default)
}

/** A row that opens a screen of its own, for settings that don't fit ◀ value ▶. */
class MenuLink(val label: String, val help: String, val activity: Class<out android.app.Activity>)

class MenuSection(val title: String, val settings: List<MenuSetting>, val links: List<MenuLink> = emptyList())

object MenuSettings {

    /** Set by SettingsActivity before the sections are used (the dual-screen rows need it). */
    lateinit var context: android.content.Context

    private val companion by lazy { dev.net64.paperboat.companion.CompanionSettings(context) }

    private fun dualScreen() = MenuSection(
        "Dual screen",
        listOf(
            PrefChoice(
                "android.companion.mode", "Screens",
                "Where the 3DS-style bottom screen goes. Auto uses a second display when there is one.",
                0, dev.net64.paperboat.companion.CompanionSettings.Mode.entries.map { it.label },
                { companion.mode.ordinal },
                { companion.mode = dev.net64.paperboat.companion.CompanionSettings.Mode.entries[it] },
            ),
            PrefChoice(
                "android.companion.orientation", "Orientation",
                "With both screens on one display: side by side in landscape, stacked in portrait.",
                0, dev.net64.paperboat.companion.CompanionSettings.Orientation.entries.map { it.label },
                { companion.orientation.ordinal },
                { companion.orientation = dev.net64.paperboat.companion.CompanionSettings.Orientation.entries[it] },
            ),
            PrefChoice(
                "android.companion.size", "Size",
                "With both screens on one display: the 3DS's proportions, or as large a game as fits.",
                0, listOf("3DS size", "Large game"),
                { if (companion.original3ds) 0 else 1 },
                { companion.original3ds = it == 0 },
            ),
            PrefChoice(
                "android.companion.side", "Bottom screen side",
                "Side by side: which side the bottom screen sits on.",
                0, listOf("Right", "Left"),
                { if (companion.swapSides) 1 else 0 },
                { companion.swapSides = it == 1 },
            ),
        ),
    )

    private fun volume(key: String, label: String) =
        MenuSetting.Range("gSettings.Volume.$key", label, "0 mutes it.", 100, 0, 100, 10, "%")

    val sections = listOf(
        MenuSection(
            "Graphics",
            listOf(
                MenuSetting.Percent(
                    "gSettings.InternalResolution", "Render scale",
                    "Resolution the game renders at, relative to the screen. Lower is faster; " +
                        "above 100% smooths edges but costs a lot of GPU.",
                    1.0f, 0.5f, 2.0f, 0.1f,
                ),
                MenuSetting.Choice(
                    "gSettings.MSAAValue", "Anti-aliasing",
                    "Smooths jagged edges. Every step costs GPU; keep it off if the game stutters.",
                    1, listOf(1 to "Off", 2 to "2x", 4 to "4x", 8 to "8x"),
                ),
                MenuSetting.Choice(
                    "gSettings.InterpolationFPS", "Frame rate",
                    "30 is the original. 60 draws in-between frames for smoother motion, at twice the work.",
                    30, listOf(30 to "30 FPS", 60 to "60 FPS"),
                ),
                MenuSetting.Toggle(
                    "gSettings.VsyncEnabled", "VSync",
                    "Syncs frames to the screen to avoid tearing.",
                    true,
                ),
                MenuSetting.Choice(
                    "gSettings.TextureFilter", "Texture filtering",
                    "Three-point matches the N64. None keeps pixels sharp.",
                    0, listOf(0 to "Three-point (N64)", 1 to "Linear", 2 to "None (sharp)"),
                ),
                MenuSetting.Toggle(
                    HdTextures.CVAR, "HD textures",
                    "Uses the HD textures of texture packs in Mods (their alternate assets). " +
                        "PC: Mods > Use Alternate Assets.",
                    false,
                ),
                MenuSetting.Toggle(
                    HdTextures.MIPMAPS_CVAR, "HD texture mipmaps",
                    "Smooths HD textures seen from afar. Turn off if a pack looks blurry.",
                    true,
                ),
                MenuSetting.Toggle(
                    "gEnhancements.Graphics.FullHeightView", "Full height view",
                    "Removes the black bars at the top and bottom during gameplay.",
                    false,
                ),
                MenuSetting.Toggle(
                    "gEnhancements.Graphics.RoundedReel", "Rounded projector reel",
                    "Makes the battle projector reel look rounded on widescreen.",
                    false,
                ),
            ),
        ),
        dualScreen(),
        MenuSection(
            "Audio",
            listOf(
                volume("Master", "Master volume"),
                volume("MainMusic", "Music"),
                volume("Environment", "Environment"),
                volume("SFX", "Sound effects"),
            ),
        ),
        MenuSection(
            "Controls",
            listOf(
                MenuSetting.Toggle(
                    "gTouchControls.Enabled", "Touch controls",
                    "The on-screen pad. Turn it off if you only use the built-in buttons.",
                    true,
                ),
                MenuSetting.Percent(
                    "gTouchControls.Opacity", "Touch controls opacity",
                    "How visible the on-screen pad is.",
                    0.8f, 0.1f, 1.0f, 0.1f,
                ),
                MenuSetting.Percent(
                    "gTouchControls.Scale", "Touch controls size",
                    "Size of the on-screen pad. Use Edit Touch Layout in the in-game menu to move buttons.",
                    1.0f, 0.5f, 2.0f, 0.1f,
                ),
                MenuSetting.Toggle(
                    "gSettings.Controls.DPadAsLeftStick", "D-pad moves Mario",
                    "Lets the D-pad act as the analog stick.",
                    false,
                ),
            ),
            links = listOf(
                MenuLink(
                    "Button mapping",
                    "Choose which button on the handheld does what: Start, Z, the C buttons and the rest.",
                    ButtonMappingActivity::class.java,
                ),
            ),
        ),
        MenuSection(
            "Gameplay",
            listOf(
                MenuSetting.Toggle(
                    "gEnhancements.NoIntro", "Skip prologue",
                    "New save files start at Mario's house instead of the opening story.",
                    false,
                ),
                MenuSetting.Choice(
                    "gEnhancements.BlockWindowMode", "Block timing",
                    "How forgiving timed blocks in battle are. Wider overrides the Dodge Master badge.",
                    0, listOf(0 to "Original (3 frames)", 1 to "Forgiving (7)", 2 to "Very forgiving (10)"),
                ),
                MenuSetting.Toggle(
                    "gEnhancements.PreventLoadingZoneStorage", "Prevent loading zone storage",
                    "Blocks a speedrun glitch. Can freeze Mario in midair until he lands.",
                    false,
                ),
            ),
        ),
        MenuSection(
            "Cheats",
            listOf(
                MenuSetting.Toggle("gCheats.InfiniteHealth", "Infinite HP", "Mario's HP never drops.", false),
                MenuSetting.Toggle("gCheats.InfiniteFlowerPoints", "Infinite FP", "Flower Points never drop.", false),
                MenuSetting.Toggle("gCheats.NoBPCost", "Free badges", "Badges cost no BP.", false),
                MenuSetting.Toggle("gCheats.MaxStarPower", "Max Star Power", "Star Power stays full.", false),
            ),
        ),
    )
}
