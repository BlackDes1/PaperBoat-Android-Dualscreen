package dev.net64.paperboat

import android.content.Context
import android.util.Log
import dev.net64.paperboat.companion.CompanionBridge
import java.io.File
import java.util.zip.ZipFile

/**
 * HD texture packs. Their textures sit under alt/ in the archive and the
 * engine only uses them with "Use Alternate Assets" on (PC: the Mods menu
 * checkbox or Tab), which is off by default. Android has neither, so the
 * Mods screen turns it on when a pack is imported, offers a switch, and the
 * Settings screen lists it under Graphics.
 */
object HdTextures {
    const val CVAR = "gEnhancements.Mods.AlternateAssets"
    const val MIPMAPS_CVAR = "gEnhancements.Mods.AutoMipmaps"

    fun isOn(context: Context): Boolean = GameConfig.load(context).getInt(CVAR, 0) != 0

    /**
     * Saves the setting for the next start and, when the game runs in this
     * process, switches it there too, so the engine's own save on quit keeps it.
     */
    fun set(context: Context, on: Boolean) {
        runCatching {
            val config = GameConfig.load(context)
            config.setInt(CVAR, if (on) 1 else 0)
            config.save()
        }.onFailure { Log.w(TAG, "Could not save $CVAR", it) }
        CompanionBridge.setAltAssets(on)
    }

    /** Whether a mod carries alternate assets (an HD texture pack). */
    fun hasAltAssets(file: File, isFolder: Boolean): Boolean = runCatching {
        if (isFolder) {
            File(file, "alt").isDirectory
        } else {
            ZipFile(file).use { zip -> zip.entries().asSequence().any { it.name.startsWith("alt/") } }
        }
    }.getOrDefault(false)

    /** Whether any enabled mod is an HD texture pack. */
    fun anyEnabledPack(context: Context): Boolean =
        ModStore.list(context).any { it.enabled && hasAltAssets(it.file, it.isFolder) }

    private const val TAG = "PaperboatHd"
}
