/**
 * Game art for the companion screen: the pause menu's icons and labels, the
 * partner portraits, the world map, item icons and the dialogue font, decoded
 * from the game archive to ARGB for the Android view. This is the art Star
 * Haven's 3DS bottom screen is built from (port/3ds/source/n64_shim/
 * hud_info.c, pm3ds_ui_get_image), looked up here by archive path.
 *
 * Runs on whatever thread calls the JNI functions (the companion view's art
 * loader thread); the resource manager is thread safe. Every result is an
 * int[] of { width, height, scale, pixels... } (ARGB; scale is the image's
 * pixels per original N64 pixel x1000, above 1000 for an HD texture pack's
 * replacement), or null when the asset is not in the archive (yet). The font
 * always comes from the game's own glyphs.
 */
#include <jni.h>

#include <cmath>
#include <cstdint>
#include <cstdio>
#include <cstring>
#include <memory>
#include <string>
#include <vector>

#include <libultraship/libultraship.h>
#include "fast/resource/type/Texture.h"

extern "C" {
#include "companion.h"
#include "assets/ui.h"
#include "assets/misc/pause.h"

extern intptr_t gItemIconRasterOffsets[];
extern intptr_t gItemIconPaletteOffsets[];
extern unsigned char MsgCharWidthNormal[];
extern unsigned short D_802F4560[80][8];
}

namespace {

// Keep in step with CompanionArt.kt.
enum ArtId {
    ART_HEART, ART_FLOWER, ART_COIN, ART_STAR_POINT, ART_STAR_PIECE,
    ART_BP, ART_MARIO, ART_TIME, ART_POW_STAR, ART_POW_EMPTY,
    ART_LABEL_STATS, ART_LABEL_MAP, ART_LABEL_PARTY, ART_LABEL_ITEMS, ART_LABEL_BADGES,
    ART_MAP_WALK0, ART_MAP_WALK1, ART_MAP_LOCATION, ART_WORLD_MAP, ART_RANK,
    ART_PARTNER = 32,           // + partner id (1..9)
    ART_PARTNER_DISABLED = 48,  // + partner id
};

struct Art {
    const char* raster;
    const char* palette;
};

Art PartnerArt(int id, bool disabled) {
#define P(name) disabled ? Art { ui_##name##_png, ui_##name##_disabled_pal } : Art { ui_##name##_png, ui_##name##_pal }
    switch (id) {
        case 1: return P(goombario);
        case 2: return P(kooper);
        case 3: return P(bombette);
        case 4: return P(parakarry);
        case 6: return P(watt);
        case 7: return P(sushie);
        case 8: return P(lakilester);
        case 9: return P(bow);
    }
#undef P
    return { nullptr, nullptr };
}

Art ArtFor(int id) {
    switch (id) {
        case ART_HEART: return { ui_stat_heart_png, nullptr };
        case ART_FLOWER: return { ui_stat_flower_png, nullptr };
        case ART_COIN: return { ui_status_coin_0_png, ui_status_coin_0_pal };
        case ART_STAR_POINT: return { ui_status_star_point_0_png, ui_status_star_point_0_pal };
        case ART_STAR_PIECE: return { ui_pause_stat_star_piece0_png, ui_pause_stat_star_piece0_pal };
        case ART_BP: return { ui_pause_stat_bp_png, ui_pause_stat_bp_pal };
        case ART_MARIO: return { ui_pause_stat_mario_png, ui_pause_stat_mario_pal };
        case ART_TIME: return { ui_pause_stat_time_png, ui_pause_stat_time_pal };
        case ART_POW_STAR: return { ui_status_pow_star_1_png, ui_status_pow_star_1_pal };
        case ART_POW_EMPTY: return { ui_status_pow_star_empty_png, ui_status_pow_star_empty_pal };
        case ART_LABEL_STATS: return { ui_pause_label_stats_png, ui_pause_label_stats_pal };
        case ART_LABEL_MAP: return { ui_pause_label_map_png, ui_pause_label_map_pal };
        case ART_LABEL_PARTY: return { ui_pause_label_party_png, ui_pause_label_party_pal };
        case ART_LABEL_ITEMS: return { ui_pause_label_items_png, ui_pause_label_items_pal };
        case ART_LABEL_BADGES: return { ui_pause_label_badges_png, ui_pause_label_badges_pal };
        case ART_MAP_WALK0: return { ui_pause_map_walk0_png, ui_pause_map_walk0_pal };
        case ART_MAP_WALK1: return { ui_pause_map_walk1_png, ui_pause_map_walk1_pal };
        case ART_MAP_LOCATION: return { pause_map_location_png, nullptr };
        case ART_WORLD_MAP: return { pause_world_map_png, pause_world_map_pal };
        case ART_RANK: return { ui_pause_partner_rank_png, ui_pause_partner_rank_pal };
    }
    if (id > ART_PARTNER && id < ART_PARTNER + 10) {
        return PartnerArt(id - ART_PARTNER, false);
    }
    if (id > ART_PARTNER_DISABLED && id < ART_PARTNER_DISABLED + 10) {
        return PartnerArt(id - ART_PARTNER_DISABLED, true);
    }
    return { nullptr, nullptr };
}

std::shared_ptr<Fast::Texture> Load(const char* path, bool exact = false) {
    if (path == nullptr || path[0] == '\0') {
        return nullptr;
    }
    auto ctx = Ship::Context::GetRawInstance();
    if (!ctx || !ctx->GetResourceManager()) {
        return nullptr;
    }
    // exact: the game's own asset even when an HD pack (alternate assets) is on
    auto res = ctx->GetResourceManager()->LoadResource(path, exact);
    auto tex = std::dynamic_pointer_cast<Fast::Texture>(res);
    if (!tex || tex->ImageData == nullptr) {
        return nullptr;
    }
    return tex;
}

constexpr const char* kOtr = "__OTR__";

std::string BasePath(const char* path) {
    std::string p = path ? path : "";
    if (p.rfind(kOtr, 0) == 0) {
        p.erase(0, 7);
    }
    return p;
}

bool AltAssetsOn() {
    // the bottom screen can ask before the engine (and its CVars) is up
    auto ctx = Ship::Context::GetRawInstance();
    if (ctx == nullptr || ctx->GetConsoleVariables() == nullptr) {
        return false;
    }
    return CVarGetInteger("gEnhancements.Mods.AlternateAssets", 0) != 0;
}

bool HasFile(const std::string& path) {
    auto ctx = Ship::Context::GetRawInstance();
    auto rm = ctx ? ctx->GetResourceManager() : nullptr;
    auto am = rm ? rm->GetArchiveManager() : nullptr;
    return am && am->HasFile(path);
}

// The HD pack's replacement, looked up as the renderer does (interpreter.cpp):
// "alt/<raster>@<palette file>" for a palette variant, then "alt/<raster>".
std::shared_ptr<Fast::Texture> LoadHd(const char* raster, const char* palette) {
    if (raster == nullptr || !AltAssetsOn()) {
        return nullptr;
    }
    const std::string base = "alt/" + BasePath(raster);
    std::vector<std::string> tries;
    if (palette != nullptr) {
        std::string pal = BasePath(palette);
        const size_t slash = pal.find_last_of('/');
        tries.push_back(base + "@" + (slash == std::string::npos ? pal : pal.substr(slash + 1)));
    }
    tries.push_back(base);
    for (const auto& t : tries) {
        if (!HasFile(t)) {
            continue;
        }
        auto tex = Load(t.c_str(), true);
        if (tex && (tex->Flags & TEX_FLAG_LOAD_AS_RAW) != 0 && tex->Type == Fast::TextureType::RGBA32bpp) {
            return tex;
        }
    }
    return nullptr;
}

uint32_t Argb(uint32_t r, uint32_t g, uint32_t b, uint32_t a) {
    return (a << 24) | (r << 16) | (g << 8) | b;
}

// N64 RGBA5551, big-endian
uint32_t From5551(const uint8_t* p) {
    uint16_t v = (uint16_t) ((p[0] << 8) | p[1]);
    return Argb(((v >> 11) & 31) * 255 / 31, ((v >> 6) & 31) * 255 / 31, ((v >> 1) & 31) * 255 / 31,
                (v & 1) ? 255 : 0);
}

// Decodes a texture; palette (CI formats) starts palOffset colours in.
bool Decode(const Fast::Texture& t, const uint8_t* pal, size_t palBytes, std::vector<uint32_t>& out, int& w,
            int& h) {
    w = t.Width;
    h = t.Height;
    const uint8_t* d = t.ImageData;
    const size_t n = (size_t) w * h;
    if (n == 0) {
        return false;
    }
    if ((t.Flags & TEX_FLAG_LOAD_AS_RAW) != 0) {
        // HD replacement (texture pack): RGBA32 at its full size
        if ((size_t) w * h * 4 > t.ImageDataSize) {
            return false;
        }
        out.resize((size_t) w * h);
        for (size_t i = 0; i < out.size(); i++) {
            out[i] = Argb(d[i * 4], d[i * 4 + 1], d[i * 4 + 2], d[i * 4 + 3]);
        }
        return true;
    }
    auto palColour = [&](unsigned idx) -> uint32_t {
        if (pal == nullptr || (idx + 1) * 2 > palBytes) {
            return 0;
        }
        return From5551(pal + idx * 2);
    };
    out.resize(n);
    switch (t.Type) {
        case Fast::TextureType::RGBA32bpp:
            if (t.ImageDataSize < n * 4) return false;
            for (size_t i = 0; i < n; i++) out[i] = Argb(d[i * 4], d[i * 4 + 1], d[i * 4 + 2], d[i * 4 + 3]);
            return true;
        case Fast::TextureType::RGBA16bpp:
            if (t.ImageDataSize < n * 2) return false;
            for (size_t i = 0; i < n; i++) out[i] = From5551(d + i * 2);
            return true;
        case Fast::TextureType::Palette4bpp:
            if (pal == nullptr || t.ImageDataSize < (n + 1) / 2) return false;
            for (size_t i = 0; i < n; i++) out[i] = palColour((i & 1) ? (d[i / 2] & 15) : (d[i / 2] >> 4));
            return true;
        case Fast::TextureType::Palette8bpp:
            if (pal == nullptr || t.ImageDataSize < n) return false;
            for (size_t i = 0; i < n; i++) out[i] = palColour(d[i]);
            return true;
        case Fast::TextureType::Grayscale4bpp:
            for (size_t i = 0; i < n; i++) {
                uint32_t v = ((i & 1) ? (d[i / 2] & 15) : (d[i / 2] >> 4)) * 17;
                out[i] = Argb(v, v, v, v);
            }
            return true;
        case Fast::TextureType::Grayscale8bpp:
            for (size_t i = 0; i < n; i++) out[i] = Argb(d[i], d[i], d[i], d[i]);
            return true;
        case Fast::TextureType::GrayscaleAlpha4bpp:
            for (size_t i = 0; i < n; i++) {
                uint32_t v = (i & 1) ? (d[i / 2] & 15) : (d[i / 2] >> 4);
                uint32_t g = (v >> 1) * 255 / 7;
                out[i] = Argb(g, g, g, (v & 1) ? 255 : 0);
            }
            return true;
        case Fast::TextureType::GrayscaleAlpha8bpp:
            for (size_t i = 0; i < n; i++) {
                uint32_t g = (d[i] >> 4) * 17, a = (d[i] & 15) * 17;
                out[i] = Argb(g, g, g, a);
            }
            return true;
        case Fast::TextureType::GrayscaleAlpha16bpp:
            if (t.ImageDataSize < n * 2) return false;
            for (size_t i = 0; i < n; i++) out[i] = Argb(d[i * 2], d[i * 2], d[i * 2], d[i * 2 + 1]);
            return true;
        default:
            return false;
    }
}

jintArray ToJava(JNIEnv* env, const std::vector<uint32_t>& px, int w, int h, int extra = -1) {
    const int head = extra >= 0 ? 3 : 2;
    jintArray arr = env->NewIntArray((jsize) (px.size() + head));
    if (arr == nullptr) {
        return nullptr;
    }
    jint hdr[3] = { w, h, extra };
    env->SetIntArrayRegion(arr, 0, head, hdr);
    env->SetIntArrayRegion(arr, head, (jsize) px.size(), reinterpret_cast<const jint*>(px.data()));
    return arr;
}

jintArray DecodeToJava(JNIEnv* env, const char* raster, const char* palette) {
    auto tex = LoadHd(raster, palette);
    if (!tex) {
        tex = Load(raster, true);
    }
    if (!tex) {
        return nullptr;
    }
    std::shared_ptr<Fast::Texture> pal;
    if (tex->Type == Fast::TextureType::Palette4bpp || tex->Type == Fast::TextureType::Palette8bpp) {
        pal = Load(palette, true);
        if (!pal) {
            return nullptr;
        }
    }
    std::vector<uint32_t> px;
    int w, h;
    if (!Decode(*tex, pal ? pal->ImageData : nullptr, pal ? pal->ImageDataSize : 0, px, w, h)) {
        return nullptr;
    }
    // An HD texture is drawn at the original's size: its pixels per N64 pixel, x1000
    int scale = 1000;
    if ((tex->Flags & TEX_FLAG_LOAD_AS_RAW) != 0 && tex->VPixelScale > 1.0f) {
        scale = (int) std::lround(tex->VPixelScale * 1000.0f);
    }
    return ToJava(env, px, w, h, scale);
}

constexpr int kGlyph = 16;
constexpr int kFontCols = 16;
constexpr int kMaxGlyphs = 0xF0;   // the standard charset's printable range
constexpr int kWidths = 0xA6;      // MsgCharWidthNormal's entries (to MSG_CHAR_SINGLE_QUOTE_CLOSE)
constexpr int kItemIcons = 349;    // gItemIconRasterOffsets' entries
constexpr int kPalStandard = 0x0A; // MSG_PAL_STANDARD
constexpr int kPalWhite = 0x00;    // MSG_PAL_WHITE

} // namespace

extern "C" {

JNIEXPORT jintArray JNICALL Java_dev_net64_paperboat_companion_CompanionBridge_nativeArt(JNIEnv* env, jclass,
                                                                                          jint id) {
    Art a = ArtFor(id);
    return a.raster ? DecodeToJava(env, a.raster, a.palette) : nullptr;
}

JNIEXPORT jintArray JNICALL Java_dev_net64_paperboat_companion_CompanionBridge_nativeItemIcon(JNIEnv* env, jclass,
                                                                                               jint item) {
    if (item <= 0 || item >= kItemIcons) {
        return nullptr;
    }
    return DecodeToJava(env, reinterpret_cast<const char*>(gItemIconRasterOffsets[item]),
                        reinterpret_cast<const char*>(gItemIconPaletteOffsets[item]));
}

/**
 * The dialogue font as one sheet: 16 glyphs a row, the standard (dark) palette
 * in the top half and the white one in the bottom half. Cells are 16x16, or
 * 64x64 with an HD pack: its glyphs ("charset_standard_N@..._palette_10" dark,
 * plain "charset_standard_N" white) shrunk to the cell, the ones it lacks
 * enlarged from the game's own. Returns { width, height, glyph count,
 * cell scale x1000, pixels... }.
 */
JNIEXPORT jintArray JNICALL Java_dev_net64_paperboat_companion_CompanionBridge_nativeFont(JNIEnv* env, jclass) {
    const uint8_t* pals = reinterpret_cast<const uint8_t*>(D_802F4560);
    // load_font has not run yet while the palettes are all zero
    bool loaded = false;
    for (int i = 0; i < (int) sizeof(D_802F4560); i++) {
        if (pals[i] != 0) {
            loaded = true;
            break;
        }
    }
    if (!loaded) {
        return nullptr;
    }
    std::vector<std::shared_ptr<Fast::Texture>> glyphs;
    for (int g = 0; g < kMaxGlyphs; g++) {
        char path[64];
        snprintf(path, sizeof(path), "__OTR__charset/charset_standard_%d", g);
        auto t = Load(path, true);
        if (!t) {
            break;
        }
        glyphs.push_back(t);
    }
    if (glyphs.empty()) {
        return nullptr;
    }
    const bool hd = AltAssetsOn() && HasFile("alt/charset/charset_standard_33");
    const int cell = hd ? 64 : kGlyph;
    const int count = (int) glyphs.size();
    const int rows = (count + kFontCols - 1) / kFontCols;
    const int w = kFontCols * cell, half = rows * cell, h = half * 2;
    std::vector<uint32_t> sheet((size_t) w * h, 0);
    for (int pass = 0; pass < 2; pass++) {
        const int palIdx = pass == 0 ? kPalStandard : kPalWhite;
        const uint8_t* pal = pals + palIdx * 16;
        const size_t palBytes = sizeof(D_802F4560) - palIdx * 16;
        for (int g = 0; g < count; g++) {
            const int ox = (g % kFontCols) * cell, oy = pass * half + (g / kFontCols) * cell;
            std::vector<uint32_t> px;
            int gw = 0, gh = 0;
            if (hd) {
                char alt[96];
                if (pass == 0) {
                    snprintf(alt, sizeof(alt), "alt/charset/charset_standard_%d@charset_standard_palette_%d", g, palIdx);
                } else {
                    snprintf(alt, sizeof(alt), "alt/charset/charset_standard_%d", g);
                }
                if (HasFile(alt)) {
                    auto t = Load(alt, true);
                    if (t && Decode(*t, nullptr, 0, px, gw, gh) && gw >= cell && gh >= cell) {
                        // box-filter the HD glyph down to the cell (premultiplied)
                        const int k = gw / cell;
                        for (int y = 0; y < cell; y++) {
                            for (int x = 0; x < cell; x++) {
                                uint32_t r = 0, gg = 0, b = 0, a = 0;
                                for (int yy = 0; yy < k; yy++) {
                                    for (int xx = 0; xx < k; xx++) {
                                        const uint32_t c = px[(size_t) (y * k + yy) * gw + x * k + xx];
                                        const uint32_t ca = c >> 24;
                                        a += ca;
                                        r += ((c >> 16) & 255) * ca;
                                        gg += ((c >> 8) & 255) * ca;
                                        b += (c & 255) * ca;
                                    }
                                }
                                const uint32_t n = (uint32_t) (k * k);
                                sheet[(size_t) (oy + y) * w + ox + x] =
                                    a == 0 ? 0 : Argb(r / a, gg / a, b / a, a / n);
                            }
                        }
                        continue;
                    }
                }
            }
            if (!Decode(*glyphs[g], pal, palBytes, px, gw, gh)) {
                continue;
            }
            const int up = cell / kGlyph;  // the game's glyph, enlarged
            for (int y = 0; y < cell && y / up < gh; y++) {
                for (int x = 0; x < cell && x / up < gw; x++) {
                    sheet[(size_t) (oy + y) * w + ox + x] = px[(size_t) (y / up) * gw + x / up];
                }
            }
        }
    }
    // header: width, height, count, then the cell scale like the images'
    const int head = 4;
    jintArray arr = env->NewIntArray((jsize) (sheet.size() + head));
    if (arr == nullptr) {
        return nullptr;
    }
    jint hdr[4] = { w, h, count, cell / kGlyph * 1000 };
    env->SetIntArrayRegion(arr, 0, head, hdr);
    env->SetIntArrayRegion(arr, head, (jsize) sheet.size(), reinterpret_cast<const jint*>(sheet.data()));
    return arr;
}

/** Whether HD textures (alternate assets) are on: the art is reloaded when this changes. */
JNIEXPORT jboolean JNICALL Java_dev_net64_paperboat_companion_CompanionBridge_nativeAltAssets(JNIEnv*, jclass) {
    return AltAssetsOn() ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jbyteArray JNICALL Java_dev_net64_paperboat_companion_CompanionBridge_nativeFontWidths(JNIEnv* env,
                                                                                                  jclass) {
    jbyteArray arr = env->NewByteArray(kWidths);
    if (arr != nullptr) {
        env->SetByteArrayRegion(arr, 0, kWidths, reinterpret_cast<const jbyte*>(MsgCharWidthNormal));
    }
    return arr;
}

/** The charset, game byte -> text, as one string with U+0001 between entries. */
JNIEXPORT jstring JNICALL Java_dev_net64_paperboat_companion_CompanionBridge_nativeCharset(JNIEnv* env, jclass) {
    std::string all;
    for (int i = 0; i < 256; i++) {
        const char* s = companion_charset(i);
        if (s == nullptr) {
            break;
        }
        if (i > 0) {
            all.push_back('\x01');
        }
        all += s;
    }
    return env->NewStringUTF(all.c_str());
}

} // extern "C"
