package dev.net64.paperboat

import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import dev.net64.paperboat.GameAssets.Region
import java.io.File
import java.io.FileOutputStream
import kotlin.concurrent.thread

/**
 * First-run flow: unpack the bundled files, take a ROM from the user, and turn
 * it into its region's archive (pm64.o2r for the US game, pm64-pal.o2r for the
 * European one) before handing off to the game. Nothing about the game ships
 * in the APK; the ROM stays on the device and the extraction runs here.
 *
 * With both regions extracted, the launcher asks which one to play.
 */
class LauncherActivity : ComponentActivity() {

    private lateinit var status: TextView
    private lateinit var chooseButton: Button
    private lateinit var progress: ProgressBar
    private lateinit var regionButtons: LinearLayout

    private val pickRom =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
            if (uri != null) importRom(uri)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildUi()
        prepare()
    }

    /**
     * Unpacks the bundled files, then decides what is left to do: start the
     * game, extract an already-imported ROM, or ask for one. Off the main
     * thread — this moves tens of megabytes out of the APK.
     */
    private fun prepare() {
        setBusy(true, getString(R.string.launcher_preparing))

        thread(name = "pm64-prepare") {
            val stagingError = GameAssets.stageBundledAssets(this)

            runOnUiThread {
                when {
                    // Nothing works without the bundled files; don't invite a retry.
                    stagingError != null -> {
                        setBusy(false, stagingError)
                        chooseButton.isEnabled = false
                    }
                    else -> chooseNextStep()
                }
            }
        }
    }

    /** Start a game, extract a ROM already imported, or ask for one. */
    private fun chooseNextStep() {
        val playable = Region.entries.filter { GameAssets.isBuilt(this, it) && GameAssets.isExtracted(this, it) }
        val pending = Region.entries.firstOrNull {
            GameAssets.isBuilt(this, it) && !GameAssets.isExtracted(this, it) && GameAssets.romFile(this, it).isFile
        }
        val requested = intent.getStringExtra(EXTRA_EXTRACT)?.let { n -> Region.entries.firstOrNull { it.name == n } }
        when {
            requested != null && GameAssets.romFile(this, requested).isFile -> extractRom(requested)
            pending != null -> extractRom(pending)
            playable.size == 1 -> startGame(playable[0])
            playable.size > 1 -> showRegionChoice(playable)
            else -> setBusy(false, romInstructions)
        }
    }

    /** Both games are ready: one button each, the last one played first. */
    private fun showRegionChoice(regions: List<Region>) {
        val last = GameAssets.activeRegion(this)
        setBusy(false, "Which Paper Mario do you want to play?\n\nOr choose another ROM to add or replace one.")
        regionButtons.removeAllViews()
        for (region in regions.sortedBy { if (it == last) 0 else 1 }) {
            regionButtons.addView(Button(this).apply {
                text = "Play Paper Mario (${region.label})"
                setOnClickListener { startGame(region) }
            })
        }
        regionButtons.visibility = View.VISIBLE
    }

    private fun buildUi() {
        val padding = (24 * resources.displayMetrics.density).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(padding, padding, padding, padding)
            setBackgroundColor(Color.rgb(16, 24, 32))
        }

        status = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 16f
            gravity = Gravity.CENTER
        }
        chooseButton = Button(this).apply {
            text = getString(R.string.launcher_choose_rom)
            setOnClickListener { pickRom.launch(arrayOf("*/*")) }
        }
        progress = ProgressBar(this).apply { visibility = View.GONE }
        regionButtons = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
        }

        // Failure messages run long and the launcher is landscape-only, so let
        // the text scroll rather than push the button off-screen.
        val scroller = ScrollView(this).apply {
            addView(
                status,
                ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            )
        }

        root.addView(scroller, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        root.addView(regionButtons)
        root.addView(chooseButton)
        root.addView(progress)
        setContentView(root)
        root.padForSystemBars()
    }

    /**
     * Copies the picked document in under a temporary name, asks the engine
     * whether it recognises it, and only then makes it the ROM. Verifying the
     * landed copy is what makes a truncated read fail here, not mid-extraction.
     */
    private fun importRom(uri: Uri) {
        setBusy(true, getString(R.string.launcher_verifying))

        thread(name = "pm64-rom-import") {
            val result = runCatching {
                val partial = File(GameAssets.gameDir(this), "rom-import.part")
                partial.delete()

                contentResolver.openInputStream(uri).use { input ->
                    requireNotNull(input) { "The document provider did not open the file." }
                    FileOutputStream(partial).use { output ->
                        input.copyTo(output, COPY_BUFFER_BYTES)
                        output.fd.sync()
                    }
                }

                val region = GameAssets.identifyRom(this, partial)
                if (region == null) {
                    partial.delete()
                    error(
                        "That is not a Paper Mario ROM the bundled recipes know how to extract. " +
                            "Supported: the US release and the European release (English, German, " +
                            "French, Spanish), as a .z64 dump. The file name does not matter."
                    )
                }
                if (!GameAssets.isBuilt(this, region)) {
                    partial.delete()
                    error("This build of Paperboat leaves out the ${region.label} game (built without :palcore).")
                }

                val target = GameAssets.romFile(this, region)
                if (target.exists() && !target.delete()) {
                    error("Could not replace the previous ROM.")
                }
                check(partial.renameTo(target)) { "Could not store the ROM in the app's files directory." }
                // A new ROM of the region replaces its archive.
                GameAssets.gameArchive(this, region).delete()
                region
            }

            runOnUiThread {
                result.fold(
                    onSuccess = { extractRom(it) },
                    onFailure = {
                        setBusy(false, "${it.message ?: it}\n\n$romInstructions")
                    }
                )
            }
        }
    }

    private fun extractRom(region: Region) {
        if (!GameAssets.loadNative(region)) {
            // The other region's library is loaded and both export the same
            // JNI names: start over in a fresh process to extract this one.
            startActivity(Intent(this, LauncherActivity::class.java).putExtra(EXTRA_EXTRACT, region.name))
            finish()
            Runtime.getRuntime().exit(0)
            return
        }
        setBusy(true, getString(R.string.launcher_extracting) + "\n\n(${region.label})")

        thread(name = "pm64-extract") {
            val error = GameAssets.generateGameArchive(this, region)

            runOnUiThread {
                if (error == null) {
                    startGame(region)
                } else {
                    setBusy(false, "$error\n\n$romInstructions")
                }
            }
        }
    }

    /**
     * Keeps the screen awake while busy: the extraction reports no progress, can
     * outlast the display timeout, and leaves a truncated archive if killed.
     */
    private fun setBusy(busy: Boolean, message: String) {
        status.text = message
        chooseButton.isEnabled = !busy
        if (busy) regionButtons.visibility = View.GONE
        progress.visibility = if (busy) View.VISIBLE else View.GONE
        if (busy) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    private fun startGame(region: Region) {
        GameAssets.setActiveRegion(this, region)
        GameAssets.stopGameRunningOtherThan(this, region)
        startActivity(Intent(this, MainActivity::class.java))
        finish()

        // This activity has its own process and the game runs in the main one,
        // so ending it costs the game nothing and hands back everything the
        // launcher touched — after an extraction, Torch still holding the ROM
        // and every decoded asset. startActivity() has already reached the
        // activity manager, so the game starts either way.
        Runtime.getRuntime().exit(0)
    }

    /** Spelled out from the real path so debug builds don't point at the release one. */
    private val romInstructions: String
        get() = "Select your own Paper Mario (N64) .z64 ROM, US or European. It is verified, kept in this app's " +
            "private storage and converted into the game's asset archive on this device — no " +
            "game data ships with the app.\n\n" +
            "Mods go in ${GameAssets.modsDir(this)}."

    private companion object {
        const val COPY_BUFFER_BYTES = 1 shl 17
        const val EXTRA_EXTRACT = "extract"
    }
}
