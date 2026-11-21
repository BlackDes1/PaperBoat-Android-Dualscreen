# Android: dual screen, PAL and maintenance notes

This branch (`android-dualscreen`) is upstream PaperBoat `develop`
(`9dc47846b`, after 1.0.3) plus:

1. a 3DS-style bottom screen ("companion screen") for Android,
2. the PAL game (English/German/French/Spanish) from
   [JeanPatrick33/PaperBoat `pal-support`](https://github.com/JeanPatrick33/PaperBoat),
   shipped in the same APK as the US game,
3. Android fixes found while testing on an AYN Thor (Adreno 740, Android 13).

## Where the Android port came from

`PaperBoat-android-develop` was byte-identical to upstream commit `791d8042c`
(2026-09-17): the Android port *is* upstream's `android/` directory, so
"updating Android" meant moving to upstream `develop` and keeping the Android
work as commits on top. There were no local Android-only changes to carry.

The configuration-path bug (`files//storage/emulated/0/.../paperboat.cfg.json`)
was already fixed upstream by `6b1014b7a` ("Fix macOS path bundles"): the
engine passed `GetPathRelativeToAppDirectory("paperboat.cfg.json")`, an
absolute path, to `Ship::Context::CreateUninitializedInstance`, and
libultraship joins the config name onto the app directory itself, so the
directory appeared twice (and `paperboat.cfg.json.tmp` with it, since the
atomic save writes `<path>.tmp` then renames). Upstream now passes the bare
name. Nothing else needs changing on Android; do not reintroduce an absolute
path there.

## Companion (bottom) screen

Reference: Star Haven, the native 3DS port of Paper Mario
(github.com/SecondaryLifer/StarHaven, Apache-2.0): top screen 400x240 game,
bottom screen 320x240 touch menu with a tab rail (Stats, Map, Party, Tattle,
Found, Recipes, Badges, Items, Options) and Items/Partner/Pause shortcuts.

| Piece | File |
| --- | --- |
| Game-thread data (JSON pages, message decoding) | `src/port/companion/companion_game.c` |
| Thread hand-off, frame hook, JNI | `src/port/companion/CompanionStore.cpp` |
| Tap requests applied in the world menu code | `src/world/menus.c` (`companion_take_request`) |
| Android view (drawn on the 320x240 grid) | `android/.../companion/CompanionView.kt` |
| Second display | `android/.../companion/CompanionPresentation.kt` |
| Layout of game + bottom screen | `android/.../companion/DualScreenController.kt` |
| Settings (SharedPreferences `companion`) | `android/.../companion/CompanionSettings.kt` |

Design choices:

* **No second renderer.** The bottom screen is an Android `View`, not a
  second GL context or a copy of the game's frames. It costs no GPU work in
  the game's frame and cannot stall it.
* **Game state is read on the game thread.** `GameFrameUpdate` builds JSON
  pages (stats every 6 game frames, lists every 30) only while a visible
  screen asks for them; the UI thread copies finished strings under a mutex.
  Message text is decoded with the game's charset table
  (`tools/build/msg/parse_compile.py`), so PAL accents come out right.
* **Taps go through the game's own rules.** Requests are consumed in
  `check_input_open_menus()` exactly where the 3DS port does it: they open the
  item/partner/pause menus as their buttons would, or switch partner with
  `switch_to_partner()`, and are refused (error sound) when input is off.
* **Layout.** With a presentation display (dual-screen handhelds, an external
  monitor) the game fills the main display and the bottom screen goes to the
  other one. Otherwise both share the main display: portrait stacks them
  (400:320 widths, equal heights, as on a 3DS); landscape puts them side by
  side. "Large" gives the game every pixel the 4:3 bottom screen does not use.
  Only the SDL surface's layout changes; SDL reports the size to the engine,
  so the game keeps its own aspect handling and touch controls.
* **Orientation.** `USER_PORTRAIT`/`USER_LANDSCAPE`, never `SENSOR_*`:
  `SDLSurface.surfaceChanged()` marks a landscape-shaped surface "not ready"
  under `SENSOR_PORTRAIT`, which a stacked top screen is.

Not ported from Star Haven (yet): quick save, the Found tracker (needs Star
Haven's randomizer location table), badge equipping from the list, the battle
command tiles, and game art (icons, world map image) on the bottom screen.

## PAL

The decomp compiles one region at a time (`VERSION_US` / `VERSION_PAL` change
code, data layout and asset paths), so the APK carries two game libraries:

| | US | PAL |
| --- | --- | --- |
| Native library | `libmain.so` (`:app`) | `libmain_pal.so` (`:palcore`, `-DPAPERBOAT_REGION=pal`) |
| ROM in the files dir | `baserom.us.z64` | `baserom.pal.z64` |
| Game archive | `pm64.o2r` | `pm64-pal.o2r` |
| Saves | `saves/` | `saves_pal/` (Android only) |
| `config.yml` name | `pm64` (SHA-1 3837f44c…) | `pm64-pal` (SHA-1 2111d392…) |

The launcher identifies a ROM by SHA-1 against `config.yml` (a 48 MB PAL dump
is hashed as the 64 MB image, like `NormalizePalDump`), loads the matching
library, and runs Torch. With both games extracted it asks which to play.
A process can hold only one of the two libraries (they export the same JNI
names), so switching region restarts the game process. Mods in `mods/` are
shared; a mod made for the US game may not work with the PAL one.

`./gradlew assembleDebug -Ppaperboat.pal=false` skips `:palcore` for a faster
US-only build.

## Merging future upstream changes

* Merge upstream `develop` into this branch; the Android work is a handful of
  commits and touches upstream files only in:
  `CMakeLists.txt` (region outputs, `-fsigned-char`), `config.yml` (PAL output
  name), `src/world/menus.c` (companion hook), `src/port/Engine.cpp`,
  `src/port/android/AndroidBridge.cpp`, `src/port/save/Save*.cpp` (archive and
  save-dir macros), `src/port/DevTools/ThreadWatchdog.cpp` (Bionic guard),
  and the Kotlin under `android/`.
* New source files are picked up only when CMake re-globs (`GLOB_RECURSE`
  without `CONFIGURE_DEPENDS`): touch `CMakeLists.txt` or delete `.cxx/`.
* If upstream adds PAL support of its own, prefer it and drop the
  `pal-support` merge; keep `PAPERBOAT_GAME_ARCHIVE`/`PAPERBOAT_SAVE_DIR` and
  `:palcore` if both regions should still share one APK.
* Keep `android/palcore/build.gradle.kts` CMake arguments in step with
  `android/app/build.gradle.kts`.
