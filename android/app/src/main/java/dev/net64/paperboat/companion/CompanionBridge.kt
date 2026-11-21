package dev.net64.paperboat.companion

/**
 * JNI surface of src/port/companion (CompanionStore.cpp). Pages are JSON built
 * on the game thread; reading one marks it wanted for the next two seconds, so
 * the game only builds what a visible screen is showing.
 */
object CompanionBridge {
    const val PAGE_STATS = 0
    const val PAGE_TATTLE = 1
    const val PAGE_RECIPES = 2
    const val PAGE_BADGES = 3
    const val PAGE_ITEMS = 4

    const val REQ_ITEM_MENU = 1
    const val REQ_PARTNER_MENU = 2
    const val REQ_PAUSE = 3
    const val REQ_PARTNER_BASE = 0x100

    /** False until libmain is loaded (the launcher process never loads it). */
    @Volatile
    var available = false

    @JvmStatic private external fun nativePage(page: Int): String

    @JvmStatic private external fun nativeRequest(request: Int)

    @JvmStatic private external fun nativeWantTattle(actorType: Int)

    @JvmStatic private external fun nativeTattleText(actorType: Int): String?

    // Game art (CompanionArt.cpp): { width, height, ARGB pixels... } or null.
    @JvmStatic private external fun nativeArt(id: Int): IntArray?

    @JvmStatic private external fun nativeItemIcon(item: Int): IntArray?

    @JvmStatic private external fun nativeFont(): IntArray?

    @JvmStatic private external fun nativeFontWidths(): ByteArray?

    @JvmStatic private external fun nativeCharset(): String?

    @JvmStatic private external fun nativeSetAltAssets(on: Boolean)

    fun page(page: Int): String =
        if (!available) "" else runCatching { nativePage(page) }.getOrDefault("")

    fun request(request: Int) {
        if (available) runCatching { nativeRequest(request) }
    }

    fun wantTattle(actorType: Int) {
        if (available) runCatching { nativeWantTattle(actorType) }
    }

    fun tattleText(actorType: Int): String? =
        if (!available) null else runCatching { nativeTattleText(actorType) }.getOrNull()

    private fun <T> logged(name: String, f: () -> T): T? = runCatching(f).onFailure {
        android.util.Log.w("PaperboatCompanion", "$name failed", it)
    }.getOrNull()

    fun art(id: Int): IntArray? = if (!available) null else logged("nativeArt") { nativeArt(id) }

    fun itemIcon(item: Int): IntArray? = if (!available) null else logged("nativeItemIcon") { nativeItemIcon(item) }

    fun font(): IntArray? = if (!available) null else logged("nativeFont") { nativeFont() }

    fun fontWidths(): ByteArray? = if (!available) null else logged("nativeFontWidths") { nativeFontWidths() }

    fun charset(): String? = if (!available) null else logged("nativeCharset") { nativeCharset() }

    /** HD textures on or off in the running game (from its next frame). */
    fun setAltAssets(on: Boolean) {
        if (available) logged("nativeSetAltAssets") { nativeSetAltAssets(on) }
    }
}
