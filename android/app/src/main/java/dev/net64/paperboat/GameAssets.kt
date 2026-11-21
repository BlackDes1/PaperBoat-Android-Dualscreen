package dev.net64.paperboat

import android.content.Context
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.RandomAccessFile
import java.security.MessageDigest
import java.util.zip.ZipInputStream

/**
 * Everything the game needs on disk, and how it gets there.
 *
 * libultraship resolves every runtime path through
 * `SDL_AndroidGetExternalStoragePath()` — `getExternalFilesDir(null)`, which a
 * file manager can also reach, so mods can be dropped in. Everything lives there:
 *
 *     Android/data/dev.net64.paperboat/files/
 *       baserom.us.z64       the user's US ROM, copied in by the launcher
 *       baserom.pal.z64      the user's European ROM, likewise
 *       config.yml, assets/  Torch's extraction recipes, unpacked from the APK
 *       paperboat.o2r        engine assets, built from port/ into the APK
 *       pm64.o2r             generated on-device from the US ROM
 *       pm64-pal.o2r         generated on-device from the European ROM
 *       mods/                user mods (.o2r, .zip or plain folders)
 *       saves/               fileN.json, one per save slot (US)
 *       saves_pal/           the same for the European game
 *
 * The two regions are two builds of the game (libmain.so, libmain_pal.so, see
 * android/palcore); [Region] ties each to its ROM, archive and saves.
 *
 * Unpacking happens here, not in the engine: Torch needs config.yml and assets/
 * on disk before the game process starts. The zip is built by build.gradle.kts.
 */
object GameAssets {

    /**
     * A build of the game. [torchName] is the `name:` config.yml gives the
     * ROM's hash; the rest must match the region's CMake settings
     * (PAPERBOAT_GAME_ARCHIVE, PAPERBOAT_SAVE_DIR, OUTPUT_NAME).
     */
    enum class Region(
        val torchName: String,
        val library: String,
        val romName: String,
        val archive: String,
        val savesDir: String,
        val label: String,
    ) {
        // romName for US matches the engine's mobile fallback (GameExtractor.cpp),
        // so an in-game re-extraction finds it too. Torch hashes contents, not the name.
        US("pm64", "main", "baserom.us.z64", "pm64.o2r", "saves", "USA"),
        PAL("pm64-pal", "main_pal", "baserom.pal.z64", "pm64-pal.o2r", "saves_pal", "Europe"),
    }

    private const val TAG = "GameAssets"
    private const val PREFS = "launcher"
    private const val KEY_REGION = "region"
    private const val CONTROLLER_DB = "gamecontrollerdb.txt"
    private const val TORCH_HASHES = "torch.hash.yml"
    private const val GAMEDATA_ZIP = "gamedata.zip"
    private const val GAMEDATA_VERSION = "gamedata.version"
    private const val STAMP = ".gamedata.version"

    fun gameDir(context: Context): File =
        (context.getExternalFilesDir(null) ?: context.filesDir).also { it.mkdirs() }

    fun romFile(context: Context, region: Region) = File(gameDir(context), region.romName)

    fun gameArchive(context: Context, region: Region) = File(gameDir(context), region.archive)

    fun modsDir(context: Context) = File(gameDir(context), "mods")

    fun isExtracted(context: Context, region: Region) = gameArchive(context, region).length() > 0

    /**
     * Whether this APK carries the region's game (a US-only build leaves PAL
     * out). Libraries normally stay uncompressed inside the APK rather than
     * being extracted to nativeLibraryDir, so look in both.
     */
    fun isBuilt(context: Context, region: Region): Boolean = builtCache.getOrPut(region) {
        val name = "lib${region.library}.so"
        val info = context.applicationInfo
        if (File(info.nativeLibraryDir, name).isFile) return@getOrPut true
        val apks = listOf(info.sourceDir) + (info.splitSourceDirs?.toList() ?: emptyList())
        apks.any { apk ->
            runCatching {
                java.util.zip.ZipFile(apk).use { zip ->
                    zip.entries().asSequence().any { it.name.startsWith("lib/") && it.name.endsWith("/$name") }
                }
            }.getOrDefault(false)
        }
    }

    private val builtCache = HashMap<Region, Boolean>()

    /** The region the game starts in: the last one launched or extracted. */
    fun activeRegion(context: Context): Region {
        val name = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_REGION, null)
        return Region.entries.firstOrNull { it.name == name } ?: Region.US
    }

    fun setActiveRegion(context: Context, region: Region) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_REGION, region.name).commit()
    }

    /**
     * Unpacks the APK-bundled extraction inputs into [gameDir]. Re-runs whenever
     * the packaged zip changes, so an update ships new recipes without the user
     * clearing data. Returns an error message, or null when ready.
     */
    fun stageBundledAssets(context: Context): String? {
        val target = gameDir(context)
        val stamp = File(target, STAMP)

        return try {
            val expected = context.assets.open(GAMEDATA_VERSION).use { it.readBytes().decodeToString() }
            if (stamp.takeIf { it.isFile }?.readText() == expected) {
                return null
            }

            context.assets.open(GAMEDATA_ZIP).use { input ->
                ZipInputStream(input.buffered()).use { zip ->
                    while (true) {
                        val entry = zip.nextEntry ?: break
                        val destination = File(target, entry.name).canonicalFile

                        // An entry named ../../x would escape the game directory.
                        if (!destination.path.startsWith(target.canonicalPath + File.separator)) {
                            Log.w(TAG, "Skipping suspicious zip entry ${entry.name}")
                            zip.closeEntry()
                            continue
                        }

                        if (entry.isDirectory) {
                            destination.mkdirs()
                        } else {
                            destination.parentFile?.mkdirs()
                            FileOutputStream(destination).use { output -> zip.copyTo(output, COPY_BUFFER_BYTES) }
                        }
                        zip.closeEntry()
                    }
                }
            }

            // Users are meant to edit this one, so only seed it once.
            val controllerDb = File(target, CONTROLLER_DB)
            if (!controllerDb.isFile) {
                copyAsset(context, CONTROLLER_DB, controllerDb)
            }

            val mods = modsDir(context)
            mods.mkdirs()
            if (mods.list().isNullOrEmpty()) {
                // Only a hint; nothing reads it back, so it must not fail here.
                try {
                    copyAsset(context, "mods/place_mods_here.txt", File(mods, "place_mods_here.txt"))
                } catch (error: IOException) {
                    Log.w(TAG, "Could not write the mods placeholder", error)
                }
            }

            // New recipes make the previous archive stale, and Torch skips
            // work whose inputs it believes are unchanged.
            Region.entries.forEach { gameArchive(context, it).delete() }
            File(target, TORCH_HASHES).delete()

            stamp.writeText(expected)
            null
        } catch (error: IOException) {
            Log.e(TAG, "Could not unpack the bundled assets", error)
            "Could not unpack the bundled game files: ${error.message}"
        }
    }

    /**
     * Which region's game this ROM is, or null if config.yml has no recipe
     * for it. Read from the same config.yml the extraction uses, so there is
     * no second list of hashes to drift; hashed here rather than by the engine
     * because the launcher has to know the region before it can load the
     * right libmain. The European ROM's usual 48 MB dump hashes as the 64 MB
     * image Torch expects (GameExtractor.cpp's NormalizePalDump does the same).
     *
     * [stageBundledAssets] must have run first; it puts config.yml in place.
     */
    fun identifyRom(context: Context, rom: File): Region? {
        val name = romNamesFromConfig(File(gameDir(context), "config.yml"))[sha1(rom)] ?: return null
        return Region.entries.firstOrNull { it.torchName == name }
    }

    /** config.yml's top-level keys are ROM SHA-1s, each with a `name:` below. */
    private fun romNamesFromConfig(config: File): Map<String, String> {
        val names = HashMap<String, String>()
        var hash: String? = null
        config.forEachLine { line ->
            val key = HASH_KEY.matchEntire(line)
            if (key != null) {
                hash = key.groupValues[1].lowercase()
            } else {
                val name = NAME_LINE.matchEntire(line)
                val h = hash
                if (name != null && h != null) {
                    names[h] = name.groupValues[1]
                    hash = null
                }
            }
        }
        return names
    }

    private fun sha1(rom: File): String {
        val digest = MessageDigest.getInstance("SHA-1")
        val buffer = ByteArray(COPY_BUFFER_BYTES)
        RandomAccessFile(rom, "r").use { file ->
            fun feed(from: Long, to: Long) {
                file.seek(from)
                var left = to - from
                while (left > 0) {
                    val n = file.read(buffer, 0, minOf(buffer.size.toLong(), left).toInt())
                    if (n <= 0) break
                    digest.update(buffer, 0, n)
                    left -= n
                }
            }
            val length = file.length()
            feed(0, length)
            if (length == PAL_SHORT_DUMP) {
                feed(PAL_MIRROR_FROM, PAL_SHORT_DUMP)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    /**
     * Runs Torch over the region's ROM to produce its archive. Blocking and
     * slow — expect a minute or more on older hardware. Returns an error
     * message, or null on success. [loadNative] must have loaded [region].
     */
    fun generateGameArchive(context: Context, region: Region): String? {
        val dir = gameDir(context)
        return nativeGenerateGameArchive(romFile(context, region).absolutePath, dir.absolutePath, dir.absolutePath)
    }

    /**
     * Loads the region's libmain into this process. The two libraries export
     * the same JNI names, so a process can only ever hold one: returns false
     * when the other is already loaded (the caller restarts the process).
     */
    @Synchronized
    fun loadNative(region: Region): Boolean {
        loaded?.let { return it == region }
        System.loadLibrary("SDL2")
        System.loadLibrary(region.library)
        loaded = region
        return true
    }

    private var loaded: Region? = null

    private const val RUNNING_MARKER = ".running-region"

    /** Called by the game process once its libmain is loaded. */
    fun markRunning(context: Context, region: Region) {
        runCatching {
            File(context.filesDir, RUNNING_MARKER).writeText("${android.os.Process.myPid()} ${region.name}")
        }
    }

    /**
     * Ends a game process still running the other region: a process cannot
     * unload one libmain for the other, and MainActivity would otherwise be
     * brought back with the wrong game. Same app, same uid, so this is allowed.
     */
    fun stopGameRunningOtherThan(context: Context, region: Region) {
        val marker = File(context.filesDir, RUNNING_MARKER)
        val parts = runCatching { marker.readText().trim().split(' ') }.getOrNull() ?: return
        val pid = parts.getOrNull(0)?.toIntOrNull() ?: return
        if (parts.getOrNull(1) == region.name) return
        val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
        val alive = manager.runningAppProcesses?.any { it.pid == pid && it.processName == context.packageName } == true
        if (alive) {
            Log.i(TAG, "Stopping the running ${parts.getOrNull(1)} game to start ${region.name}")
            android.os.Process.killProcess(pid)
        }
        marker.delete()
    }

    private val HASH_KEY = Regex("""^([0-9a-fA-F]{40}):\s*$""")
    private val NAME_LINE = Regex("""^\s+name:\s*(\S+)\s*$""")
    private const val PAL_SHORT_DUMP = 0x3000000L
    private const val PAL_MIRROR_FROM = 0x2000000L

    private const val COPY_BUFFER_BYTES = 1 shl 17

    private fun copyAsset(context: Context, assetPath: String, target: File) {
        target.parentFile?.mkdirs()
        context.assets.open(assetPath).use { input ->
            FileOutputStream(target).use { output ->
                input.copyTo(output)
                output.fd.sync()
            }
        }
    }

    // libmain(_pal).so carries both the game and the Torch extractor.
    private external fun nativeGenerateGameArchive(rom: String, sourceDir: String, destDir: String): String?
}
