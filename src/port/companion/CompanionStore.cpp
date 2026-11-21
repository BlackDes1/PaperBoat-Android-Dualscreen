/**
 * Thread hand-off for the companion screen (see companion.h), its game-frame
 * hook, and the JNI entry points of dev.net64.paperboat.companion.CompanionBridge.
 *
 * The game thread builds pages (companion_game.c) and stores them here under
 * a mutex; the Android UI thread only copies finished strings. Requests go the
 * other way through an atomic, consumed by check_input_open_menus().
 */
#include "companion.h"

#include <array>
#include <atomic>
#include <chrono>
#include <cstring>
#include <mutex>
#include <string>

#include "port/hooks/Events.h"
#include "port/ShipInit.hpp"
#include <libultraship/libultraship.h>

namespace {

using Clock = std::chrono::steady_clock;

struct Page {
    std::string json;
    unsigned serial = 0;
    Clock::time_point wantedUntil {};
};

std::mutex sMutex;
std::array<Page, COMPANION_PAGE_COUNT> sPages;
std::atomic<int> sRequest { COMPANION_REQ_NONE };

int sTattleWanted = -1;
int sTattleType = -1;
std::string sTattleText;
unsigned sTattleSerial = 0;

constexpr auto kWantWindow = std::chrono::seconds(2);

} // namespace

extern "C" {

void companion_post_request(int request) {
    sRequest.store(request);
}

int companion_take_request(void) {
    return sRequest.exchange(COMPANION_REQ_NONE);
}

void companion_want_page(int page) {
    if (page < 0 || page >= COMPANION_PAGE_COUNT) {
        return;
    }
    std::lock_guard<std::mutex> lock(sMutex);
    sPages[page].wantedUntil = Clock::now() + kWantWindow;
}

int companion_store_wanted(int page) {
    std::lock_guard<std::mutex> lock(sMutex);
    return Clock::now() < sPages[page].wantedUntil ? 1 : 0;
}

void companion_store_page(int page, const char* json) {
    std::lock_guard<std::mutex> lock(sMutex);
    Page& p = sPages[page];
    if (p.json != json) {
        p.json = json;
        p.serial++;
        if (p.serial == 0) {
            p.serial = 1;
        }
    }
}

unsigned companion_copy_page(int page, char* out, int size) {
    if (size <= 0) {
        return 0;
    }
    out[0] = '\0';
    if (page < 0 || page >= COMPANION_PAGE_COUNT) {
        return 0;
    }
    std::lock_guard<std::mutex> lock(sMutex);
    const Page& p = sPages[page];
    const size_t n = std::min(p.json.size(), static_cast<size_t>(size - 1));
    std::memcpy(out, p.json.data(), n);
    out[n] = '\0';
    return p.serial;
}

void companion_want_tattle_text(int actorType) {
    std::lock_guard<std::mutex> lock(sMutex);
    sTattleWanted = actorType;
}

int companion_store_take_tattle_request(void) {
    std::lock_guard<std::mutex> lock(sMutex);
    const int wanted = sTattleWanted;
    sTattleWanted = -1;
    return wanted;
}

void companion_store_tattle_text(int actorType, const char* text) {
    std::lock_guard<std::mutex> lock(sMutex);
    sTattleType = actorType;
    sTattleText = text;
    sTattleSerial++;
}

} // extern "C"

// "Use Alternate Assets" (HD texture packs) set from the Android menus. CVars
// belong to the game thread, so the value waits here for the next frame.
static std::atomic<int> sAltAssetsRequest { -1 };

static void ApplyAltAssetsRequest() {
    const int value = sAltAssetsRequest.exchange(-1);
    if (value < 0) {
        return;
    }
    CVarSetInteger("gEnhancements.Mods.AlternateAssets", value);
    if (auto window = Ship::Context::GetRawInstance()->GetWindow()) {
        if (auto gui = window->GetGui()) {
            gui->SaveConsoleVariablesNextFrame();
        }
    }
}

static void RegisterCompanionScreen() {
    REGISTER_LISTENER(GameFrameUpdate, EVENT_PRIORITY_NORMAL, [](IEvent*) {
        ApplyAltAssetsRequest();
        companion_game_frame();
    });
}

static RegisterShipInitFunc initCompanionScreen(RegisterCompanionScreen);

#ifdef __ANDROID__
#include <jni.h>

namespace {
std::string CopyPage(int page) {
    static thread_local std::string buffer(32 * 1024, '\0');
    companion_copy_page(page, buffer.data(), static_cast<int>(buffer.size()));
    return std::string(buffer.c_str());
}
} // namespace

extern "C" {

/** Turns HD textures (alternate assets) on or off from the next game frame. */
JNIEXPORT void JNICALL Java_dev_net64_paperboat_companion_CompanionBridge_nativeSetAltAssets(JNIEnv*, jclass,
                                                                                            jboolean on) {
    sAltAssetsRequest = on ? 1 : 0;
}

/** Marks a page wanted and returns its latest JSON ("" until first built). */
JNIEXPORT jstring JNICALL Java_dev_net64_paperboat_companion_CompanionBridge_nativePage(JNIEnv* env, jclass,
                                                                                         jint page) {
    companion_want_page(page);
    return env->NewStringUTF(CopyPage(page).c_str());
}

JNIEXPORT void JNICALL Java_dev_net64_paperboat_companion_CompanionBridge_nativeRequest(JNIEnv*, jclass,
                                                                                         jint request) {
    companion_post_request(request);
}

JNIEXPORT void JNICALL Java_dev_net64_paperboat_companion_CompanionBridge_nativeWantTattle(JNIEnv*, jclass,
                                                                                            jint actorType) {
    companion_want_tattle_text(actorType);
}

/** The tattle text built for actorType, or null while it is not ready. */
JNIEXPORT jstring JNICALL Java_dev_net64_paperboat_companion_CompanionBridge_nativeTattleText(JNIEnv* env, jclass,
                                                                                            jint actorType) {
    std::lock_guard<std::mutex> lock(sMutex);
    if (sTattleType != actorType) {
        return nullptr;
    }
    return env->NewStringUTF(sTattleText.c_str());
}

} // extern "C"
#endif
