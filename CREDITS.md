# Credits

PaperBoat Android Dualscreen is the Android port of PaperBoat with a
3DS-style second screen, the European (PAL) release and texture-pack
support. It stands on the work of the projects below. No game data is
included: Paper Mario and its assets belong to Nintendo, and you need
your own legally dumped ROM to play.

## Star Haven

The bottom screen (layout, pages, colours, the game art it is drawn
from, fast forward) follows Star Haven's 3DS bottom screen, and its game
state queries are ported from Star Haven (`src/port/companion/`,
`android/.../companion/`).

```
Star Haven, a native Nintendo 3DS port of Paper Mario
Copyright 2026 SecondaryLifer
https://github.com/SecondaryLifer/StarHaven

This product includes Star Haven by SecondaryLifer. Forks and other works
based on it must keep this notice and credit Star Haven by SecondaryLifer,
as required by section 4(d) of the Apache License, Version 2.0.

Scope: the Apache License covers the files under port/3ds/ and the port's
changes to the decompilation (code under _3DS / PM3DS_ guards and the asset
tools changed for the port). The decompilation itself comes from
https://github.com/pmret/papermario under its own terms. Paper Mario and
its assets belong to Nintendo; none of them are licensed here.

Third-party parts:
- cia/banner.cgfx, the 3D HOME Menu banner, by Velvii (https://github.com/Velvii),
  used with permission. Its title texture uses the Hey Gorgeous font (ffonts.net),
  which is free for personal, non-commercial use.
```

The Star Haven banner is not used in this project.

## Other projects

- **PaperBoat** by HarbourMasters — the PC port this is built on, and its
  original Android port. https://github.com/HarbourMasters/PaperBoat
- **Paper Mario decompilation** by the pmret team — the game code.
  https://github.com/pmret/papermario
- **PAL support** by JeanPatrick33 (`pal-support` branch) — the European
  release (English, German, French, Spanish).
  https://github.com/JeanPatrick33/PaperBoat
- **chris43123's PaperBoat fork** (`airx` branch) — the texture fix
  (native heap pointer tagging off), signed `char`, highp shaders, the
  main menu, the pause menu and button mapping.
  https://github.com/chris43123/PaperBoat
- **libultraship** (JeodC `lus-converge`, after HarbourMasters) — engine
  and Fast3D renderer. https://github.com/JeodC/libultraship
- **Torch** by HarbourMasters — asset extraction.
  https://github.com/HarbourMasters/Torch
- **SDL2** — window, input and audio. https://www.libsdl.org

Each keeps its own license; see the projects above and the files in
this repository. `LICENSE` (Apache 2.0) covers this repository's own
work; `LICENSE-PaperBoat` is PaperBoat's license (CC0 1.0), kept for the
code that comes from it.

## Android dual-screen work

Dual screen, PAL in one APK, mod and HD-texture-pack support on Android,
and the config path fix: BlackDes1, with Claude (Anthropic).
