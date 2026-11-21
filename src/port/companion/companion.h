/**
 * Companion screen: the 3DS-style second screen of the Android port.
 *
 * The layout and the pages follow Star Haven, the native 3DS port of Paper
 * Mario (github.com/SecondaryLifer/StarHaven, port/3ds/source/platform/
 * bottom_screen.c and n64_shim/hud_info.c, journal.c; Apache-2.0). On a 3DS
 * the game runs on the top screen and a touch menu runs on the bottom one:
 * Stats, Map, Party, Tattle, Recipes, Badges and Items, with HP/FP/Star Power
 * in battle. Here the bottom screen is an Android view (see
 * android/.../companion/), fed by JSON snapshots that this module builds.
 *
 * Threading: every function in this header except the request/snapshot
 * accessors runs on the game thread (from the GameFrameUpdate hook or from
 * check_input_open_menus), because it reads game state and loads messages
 * through the resource manager. The UI thread only copies finished strings
 * out (companion_copy_page) and posts requests (companion_post_request).
 */
#ifndef PORT_COMPANION_H
#define PORT_COMPANION_H

#ifdef __cplusplus
extern "C" {
#endif

/* Pages the UI can ask for. Each is a JSON document rebuilt on the game thread
 * while it is wanted. */
enum CompanionPage {
    COMPANION_PAGE_STATS = 0,   /* status, location, party, world map spaces */
    COMPANION_PAGE_TATTLE,      /* enemies Goombario has tattled */
    COMPANION_PAGE_RECIPES,     /* Tayce T.'s dishes */
    COMPANION_PAGE_BADGES,      /* owned badges, equipped flag, BP */
    COMPANION_PAGE_ITEMS,       /* inventory and key items */
    COMPANION_PAGE_COUNT
};

/* Requests the touch screen can make of the game, applied in
 * check_input_open_menus() exactly when the matching menu could open. */
enum CompanionRequest {
    COMPANION_REQ_NONE = 0,
    COMPANION_REQ_ITEM_MENU = 1,    /* the C-Left item popup */
    COMPANION_REQ_PARTNER_MENU = 2, /* the C-Right partner popup */
    COMPANION_REQ_PAUSE = 3,        /* the pause menu */
    COMPANION_REQ_PARTNER_BASE = 0x100 /* + partner ID: switch straight to that partner */
};

/* --- game thread ---------------------------------------------------------- */

/* Called once per game frame (GameFrameUpdate). Cheap when nothing is wanted. */
void companion_game_frame(void);

/* Consumed by check_input_open_menus(); returns a CompanionRequest or 0. */
int companion_take_request(void);

/* Text of message msgID as UTF-8 (tattles span pages: dialogue = 1 joins them). */
void companion_decode_msg(int msgID, char* out, int size, int dialogue);

/* --- any thread ------------------------------------------------------------ */

void companion_post_request(int request);
/* Marks a page as wanted for the next ~2 seconds. */
void companion_want_page(int page);
/* Copies the latest JSON of a page into out (NUL-terminated). Returns its
 * serial number, which changes whenever the page is rebuilt with new content,
 * or 0 if the page has not been built yet. */
unsigned companion_copy_page(int page, char* out, int size);
/* Text of one tattle (index from the tattle page), built on the game thread. */
void companion_want_tattle_text(int actorType);

/* game byte -> UTF-8 of the standard charset, NULL past its end (companion_game.c) */
const char* companion_charset(int code);

#ifdef __cplusplus
}
#endif

#endif
