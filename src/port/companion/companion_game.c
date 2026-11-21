/**
 * Game-thread half of the companion screen (see companion.h): reads game
 * state into JSON pages for the Android bottom screen, and applies its taps.
 *
 * What each page shows follows Star Haven's 3DS bottom screen
 * (n64_shim/hud_info.c and journal.c, Apache-2.0, SecondaryLifer); the code
 * is written for PaperBoat's decomp and resource manager.
 */
#include "common.h"
#include "game_modes.h"
#include "map.h"
#include "message_ids.h"
#include "hud_element.h"
#include "battle/battle.h"
#include "companion.h"

#include <stdio.h>
#include <string.h>

/* CompanionStore.cpp */
int companion_store_wanted(int page);
void companion_store_page(int page, const char* json);
int companion_store_take_tattle_request(void);
void companion_store_tattle_text(int actorType, const char* text);

extern AreaConfig gAreas[];
extern PauseMapSpace PauseMapSpaces[];
extern s32 bActorTattles[];
extern s32 gPausePartnersPartnerIDs[8];
extern const s32 CookableItemIDs[];
extern const s32 CookableDiscoveredFlags[];
extern s32 get_global_byte(s32 index);
extern s32 get_global_flag(s32 index);
MapConfig* get_current_map_config(void);
void dma_load_msg(u32 msgID, void* dest);

#define COMPANION_MAP_SPACES 34   /* entries in PauseMapSpaces (include/world_map.inc.c) */
#define COMPANION_RECIPES 52      /* entries in CookableItemIDs (mac_02 recipes.inc.c) */
#define COMPANION_ACTOR_TYPES 0xD4 /* tattle bits GB_Tattles_00.. cover this many actor types */

/* ---- message text --------------------------------------------------------- */

/* Generated from tools/build/msg/parse_compile.py CHARSET_STANDARD: game byte -> UTF-8. */
static const char* const kCharsetUtf8[248] = {
    "♪", /* 0x00 */
    "!", /* 0x01 */
    "\"", /* 0x02 */
    "#", /* 0x03 */
    "$", /* 0x04 */
    "%", /* 0x05 */
    "&", /* 0x06 */
    "'", /* 0x07 */
    "(", /* 0x08 */
    ")", /* 0x09 */
    "*", /* 0x0A */
    "+", /* 0x0B */
    ",", /* 0x0C */
    "-", /* 0x0D */
    ".", /* 0x0E */
    "/", /* 0x0F */
    "0", /* 0x10 */
    "1", /* 0x11 */
    "2", /* 0x12 */
    "3", /* 0x13 */
    "4", /* 0x14 */
    "5", /* 0x15 */
    "6", /* 0x16 */
    "7", /* 0x17 */
    "8", /* 0x18 */
    "9", /* 0x19 */
    ":", /* 0x1A */
    ";", /* 0x1B */
    "<", /* 0x1C */
    "=", /* 0x1D */
    ">", /* 0x1E */
    "?", /* 0x1F */
    "@", /* 0x20 */
    "A", /* 0x21 */
    "B", /* 0x22 */
    "C", /* 0x23 */
    "D", /* 0x24 */
    "E", /* 0x25 */
    "F", /* 0x26 */
    "G", /* 0x27 */
    "H", /* 0x28 */
    "I", /* 0x29 */
    "J", /* 0x2A */
    "K", /* 0x2B */
    "L", /* 0x2C */
    "M", /* 0x2D */
    "N", /* 0x2E */
    "O", /* 0x2F */
    "P", /* 0x30 */
    "Q", /* 0x31 */
    "R", /* 0x32 */
    "S", /* 0x33 */
    "T", /* 0x34 */
    "U", /* 0x35 */
    "V", /* 0x36 */
    "W", /* 0x37 */
    "X", /* 0x38 */
    "Y", /* 0x39 */
    "Z", /* 0x3A */
    "[", /* 0x3B */
    "¥", /* 0x3C */
    "]", /* 0x3D */
    "^", /* 0x3E */
    "_", /* 0x3F */
    "`", /* 0x40 */
    "a", /* 0x41 */
    "b", /* 0x42 */
    "c", /* 0x43 */
    "d", /* 0x44 */
    "e", /* 0x45 */
    "f", /* 0x46 */
    "g", /* 0x47 */
    "h", /* 0x48 */
    "i", /* 0x49 */
    "j", /* 0x4A */
    "k", /* 0x4B */
    "l", /* 0x4C */
    "m", /* 0x4D */
    "n", /* 0x4E */
    "o", /* 0x4F */
    "p", /* 0x50 */
    "q", /* 0x51 */
    "r", /* 0x52 */
    "s", /* 0x53 */
    "t", /* 0x54 */
    "u", /* 0x55 */
    "v", /* 0x56 */
    "w", /* 0x57 */
    "x", /* 0x58 */
    "y", /* 0x59 */
    "z", /* 0x5A */
    "{", /* 0x5B */
    "|", /* 0x5C */
    "}", /* 0x5D */
    "~", /* 0x5E */
    "°", /* 0x5F */
    "À", /* 0x60 */
    "Á", /* 0x61 */
    "Â", /* 0x62 */
    "Ä", /* 0x63 */
    "Ç", /* 0x64 */
    "È", /* 0x65 */
    "É", /* 0x66 */
    "Ê", /* 0x67 */
    "Ë", /* 0x68 */
    "Ì", /* 0x69 */
    "Í", /* 0x6A */
    "Î", /* 0x6B */
    "Ï", /* 0x6C */
    "Ñ", /* 0x6D */
    "Ò", /* 0x6E */
    "Ó", /* 0x6F */
    "Ô", /* 0x70 */
    "Ö", /* 0x71 */
    "Ù", /* 0x72 */
    "Ú", /* 0x73 */
    "Û", /* 0x74 */
    "Ü", /* 0x75 */
    "ß", /* 0x76 */
    "à", /* 0x77 */
    "á", /* 0x78 */
    "â", /* 0x79 */
    "ä", /* 0x7A */
    "ç", /* 0x7B */
    "è", /* 0x7C */
    "é", /* 0x7D */
    "ê", /* 0x7E */
    "ë", /* 0x7F */
    "ì", /* 0x80 */
    "í", /* 0x81 */
    "î", /* 0x82 */
    "ï", /* 0x83 */
    "ñ", /* 0x84 */
    "ò", /* 0x85 */
    "ó", /* 0x86 */
    "ô", /* 0x87 */
    "ö", /* 0x88 */
    "ù", /* 0x89 */
    "ú", /* 0x8A */
    "û", /* 0x8B */
    "ü", /* 0x8C */
    "¡", /* 0x8D */
    "¿", /* 0x8E */
    "ª", /* 0x8F */
    "?", /* 0x90 */
    "?", /* 0x91 */
    "?", /* 0x92 */
    "?", /* 0x93 */
    "?", /* 0x94 */
    "?", /* 0x95 */
    "?", /* 0x96 */
    "?", /* 0x97 */
    "?", /* 0x98 */
    "?", /* 0x99 */
    "?", /* 0x9A */
    "?", /* 0x9B */
    "?", /* 0x9C */
    "?", /* 0x9D */
    "?", /* 0x9E */
    "?", /* 0x9F */
    "?", /* 0xA0 */
    "?", /* 0xA1 */
    "“", /* 0xA2 */
    "”", /* 0xA3 */
    "‘", /* 0xA4 */
    "’", /* 0xA5 */
    "?", /* 0xA6 */
    "?", /* 0xA7 */
    "?", /* 0xA8 */
    "?", /* 0xA9 */
    "?", /* 0xAA */
    "?", /* 0xAB */
    "?", /* 0xAC */
    "?", /* 0xAD */
    "?", /* 0xAE */
    "?", /* 0xAF */
    "?", /* 0xB0 */
    "?", /* 0xB1 */
    "?", /* 0xB2 */
    "?", /* 0xB3 */
    "?", /* 0xB4 */
    "?", /* 0xB5 */
    "?", /* 0xB6 */
    "?", /* 0xB7 */
    "?", /* 0xB8 */
    "?", /* 0xB9 */
    "?", /* 0xBA */
    "?", /* 0xBB */
    "?", /* 0xBC */
    "?", /* 0xBD */
    "?", /* 0xBE */
    "?", /* 0xBF */
    "?", /* 0xC0 */
    "?", /* 0xC1 */
    "?", /* 0xC2 */
    "?", /* 0xC3 */
    "?", /* 0xC4 */
    "?", /* 0xC5 */
    "?", /* 0xC6 */
    "?", /* 0xC7 */
    "?", /* 0xC8 */
    "?", /* 0xC9 */
    "?", /* 0xCA */
    "?", /* 0xCB */
    "?", /* 0xCC */
    "?", /* 0xCD */
    "?", /* 0xCE */
    "?", /* 0xCF */
    "?", /* 0xD0 */
    "?", /* 0xD1 */
    "?", /* 0xD2 */
    "?", /* 0xD3 */
    "?", /* 0xD4 */
    "?", /* 0xD5 */
    "?", /* 0xD6 */
    "?", /* 0xD7 */
    "?", /* 0xD8 */
    "?", /* 0xD9 */
    "?", /* 0xDA */
    "?", /* 0xDB */
    "?", /* 0xDC */
    "?", /* 0xDD */
    "?", /* 0xDE */
    "?", /* 0xDF */
    "?", /* 0xE0 */
    "?", /* 0xE1 */
    "?", /* 0xE2 */
    "?", /* 0xE3 */
    "?", /* 0xE4 */
    "?", /* 0xE5 */
    "?", /* 0xE6 */
    "?", /* 0xE7 */
    "?", /* 0xE8 */
    "?", /* 0xE9 */
    "?", /* 0xEA */
    "?", /* 0xEB */
    "?", /* 0xEC */
    "?", /* 0xED */
    "?", /* 0xEE */
    "?", /* 0xEF */
    "?", /* 0xF0 */
    "?", /* 0xF1 */
    "?", /* 0xF2 */
    "?", /* 0xF3 */
    "?", /* 0xF4 */
    "?", /* 0xF5 */
    "?", /* 0xF6 */
    "　", /* 0xF7 */
};

const char* companion_charset(int code) {
    return code >= 0 && code < (int) ARRAY_COUNT(kCharsetUtf8) ? kCharsetUtf8[code] : NULL;
}


/* arguments after MSG_CHAR_READ_FUNCTION f, -1 if unknown (as Star Haven's touch_menu.c) */
static const s8 sFuncArgs[0x30] = {
    1, 1, 1, 0, 0, 1, 0, 0, 0, 0, 0, 1, 1, 2, 0, 2,   /* 00 font .. 0F speed */
    2, 1, 1, 1, 1, 1, 3, 2, 7, 1, 2, 2, 1, 3, 1, 1,   /* 10 set x .. 1F end choice */
    1, 1, 0, 0, 0, 0, -1, 1, 1, 1, 1, 0, 4, -1, 1, 1, /* 20 set cancel .. 2F voice */
};

void companion_decode_msg(int msgID, char* out, int size, int dialogue) {
    static u8 buf[0x400 + 1];
    s32 n = 0;

    if (size <= 0) {
        return;
    }
    out[0] = '\0';
    if (msgID == MSG_NONE || msgID == 0) {
        return;
    }
    memset(buf, MSG_CHAR_READ_END, sizeof(buf));
    dma_load_msg((u32) msgID, buf);

    for (s32 i = 0; i < (s32) sizeof(buf) - 1 && n < size - 1; i++) {
        u8 c = buf[i];
        if (c == MSG_CHAR_READ_END) {
            break;
        }
        if (c == MSG_CHAR_READ_ENDL || c == MSG_CHAR_READ_SPACE || c == MSG_CHAR_READ_FULL_SPACE ||
            c == MSG_CHAR_READ_HALF_SPACE || c == MSG_CHAR_READ_NEXT) {
            if (n > 0 && out[n - 1] != ' ') {
                out[n++] = ' ';
            }
        } else if (c == MSG_CHAR_READ_PAUSE) {
            i++;
        } else if (c == MSG_CHAR_READ_FUNCTION) {
            u8 f = buf[++i];
            if (f >= ARRAY_COUNT(sFuncArgs) || sFuncArgs[f] < 0) {
                break;
            }
            i += sFuncArgs[f];
        } else if (c == MSG_CHAR_READ_STYLE) {
            if (!dialogue) {
                break;
            }
            u8 style = buf[++i];
            i += style == MSG_STYLE_CHOICE ? 4 : style == MSG_STYLE_POSTCARD ? 1 : 0;
        } else if (c >= 0xF0) {
            /* other control bytes take no argument */
        } else if (c < ARRAY_COUNT(kCharsetUtf8)) {
            const char* s = kCharsetUtf8[c];
            s32 len = (s32) strlen(s);
            if (n + len >= size) {
                break;
            }
            memcpy(out + n, s, len);
            n += len;
        }
    }
    while (n > 0 && out[n - 1] == ' ') {
        n--;
    }
    out[n] = '\0';
}

/* ---- JSON ------------------------------------------------------------------ */

typedef struct {
    char* p;
    s32 n;
    s32 cap;
} JsonBuf;

static void jb_raw(JsonBuf* b, const char* s) {
    s32 len = (s32) strlen(s);
    if (b->n + len >= b->cap) {
        len = b->cap - 1 - b->n;
    }
    if (len > 0) {
        memcpy(b->p + b->n, s, len);
        b->n += len;
    }
    b->p[b->n] = '\0';
}

static void jb_str(JsonBuf* b, const char* s) {
    char tmp[8];
    jb_raw(b, "\"");
    for (; *s != '\0'; s++) {
        u8 c = (u8) *s;
        if (c == '"' || c == '\\') {
            tmp[0] = '\\';
            tmp[1] = (char) c;
            tmp[2] = '\0';
        } else if (c < 0x20) {
            snprintf(tmp, sizeof(tmp), "\\u%04x", c);
        } else {
            tmp[0] = (char) c;
            tmp[1] = '\0';
        }
        jb_raw(b, tmp);
    }
    jb_raw(b, "\"");
}

static void jb_key(JsonBuf* b, const char* key) {
    if (b->n > 0 && b->p[b->n - 1] != '{' && b->p[b->n - 1] != '[') {
        jb_raw(b, ",");
    }
    jb_str(b, key);
    jb_raw(b, ":");
}

static void jb_int(JsonBuf* b, const char* key, s32 v) {
    char tmp[24];
    jb_key(b, key);
    snprintf(tmp, sizeof(tmp), "%d", (int) v);
    jb_raw(b, tmp);
}

static void jb_text(JsonBuf* b, const char* key, const char* v) {
    jb_key(b, key);
    jb_str(b, v);
}

static void jb_item_sep(JsonBuf* b) {
    if (b->n > 0 && b->p[b->n - 1] != '[') {
        jb_raw(b, ",");
    }
}

/* ---- names (cached: messages only change with the PAL language) ---------- */

#define NAME_LEN 48
static char sItemNames[NUM_ITEMS][NAME_LEN];
static u8 sItemNameDone[NUM_ITEMS];
static char sActorNames[COMPANION_ACTOR_TYPES][NAME_LEN];
static u8 sActorNameDone[COMPANION_ACTOR_TYPES];
static char sSpaceNames[COMPANION_MAP_SPACES][NAME_LEN];
static u8 sSpaceNameDone[COMPANION_MAP_SPACES];
static s32 sCachedLanguage = -1;

static void check_language(void) {
    s32 lang = 0;
#if VERSION_PAL
    lang = gCurrentLanguage;
#endif
    if (lang != sCachedLanguage) {
        sCachedLanguage = lang;
        memset(sItemNameDone, 0, sizeof(sItemNameDone));
        memset(sActorNameDone, 0, sizeof(sActorNameDone));
        memset(sSpaceNameDone, 0, sizeof(sSpaceNameDone));
    }
}

static const char* item_name(s32 item) {
    if (item <= 0 || item >= NUM_ITEMS) {
        return "";
    }
    if (!sItemNameDone[item]) {
        companion_decode_msg(gItemTable[item].nameMsg, sItemNames[item], NAME_LEN, 0);
        sItemNameDone[item] = 1;
    }
    return sItemNames[item];
}

static const char* actor_name(s32 type) {
    if (type < 0 || type >= COMPANION_ACTOR_TYPES) {
        return "";
    }
    if (!sActorNameDone[type]) {
        companion_decode_msg(bActorNames[type], sActorNames[type], NAME_LEN, 0);
        sActorNameDone[type] = 1;
    }
    return sActorNames[type];
}

static const char* space_name(s32 i) {
    if (!sSpaceNameDone[i]) {
        companion_decode_msg(MSG_Menus_00F2 + i * 3, sSpaceNames[i], NAME_LEN, 0);
        sSpaceNameDone[i] = 1;
    }
    return sSpaceNames[i];
}

/* ---- state ------------------------------------------------------------------ */

static const struct {
    const char* area;
    const char* name;
} sAreaNames[] = {
    { "area_kmr", "Goomba Region" },     { "area_mac", "Toad Town" },
    { "area_tik", "Toad Town Tunnels" }, { "area_kgr", "Inside the Whale" },
    { "area_kkj", "Peach's Castle" },    { "area_hos", "Star Region" },
    { "area_nok", "Koopa Region" },      { "area_trd", "Koopa Bros. Fortress" },
    { "area_iwa", "Mt. Rugged" },        { "area_dro", "Dry Dry Outpost" },
    { "area_sbk", "Dry Dry Desert" },    { "area_isk", "Dry Dry Ruins" },
    { "area_mim", "Forever Forest" },    { "area_obk", "Boo's Mansion" },
    { "area_arn", "Gusty Gulch" },       { "area_dgb", "Tubba Blubba's Castle" },
    { "area_omo", "Shy Guy's Toy Box" }, { "area_jan", "Lavalava Island" },
    { "area_kzn", "Mt. Lavalava" },      { "area_flo", "Flower Fields" },
    { "area_sam", "Shiver Region" },     { "area_pra", "Crystal Palace" },
    { "area_kpa", "Bowser's Castle" },   { "area_osr", "Peach's Castle Grounds" },
    { "area_end", "The End" },           { "area_mgm", "Playroom" },
};

static s32 in_game(void) {
    s32 mode = get_game_mode();
    return (mode == GAME_MODE_WORLD || mode == GAME_MODE_CHANGE_MAP || mode == GAME_MODE_ENTER_WORLD ||
            mode == GAME_MODE_BATTLE || mode == GAME_MODE_END_BATTLE || mode == GAME_MODE_PAUSE ||
            mode == GAME_MODE_UNPAUSE) &&
           gGameStatusPtr->demoState == 0 && gGameStatusPtr->introPart < 0;
}

/* chapter from story progress (first value of each chapter in enums.h) */
static s32 chapter_of(s32 story) {
    static const s32 firstOf[] = { -102, -72, -51, -12, 8, 42, 61, 90, 96 };
    s32 ch = 0;
    while (ch < (s32) ARRAY_COUNT(firstOf) && story >= firstOf[ch]) {
        ch++;
    }
    return ch;
}

static void build_stats(JsonBuf* b) {
    PlayerData* pd = &gPlayerData;
    s32 mode = get_game_mode();
    s32 inGame = in_game();
    MapConfig* mc;
    const char* areaId = "";
    const char* location = "";

    jb_raw(b, "{");
    jb_int(b, "inGame", inGame);
    jb_int(b, "battle", mode == GAME_MODE_BATTLE);
    jb_int(b, "paused", mode == GAME_MODE_PAUSE);
    jb_int(b, "peach", (gGameStatusPtr->peachFlags & PEACH_FLAG_IS_PEACH) != 0);
    /* whether the Items / Partner / Pause shortcuts can open their menus now:
     * the checks check_input_open_menus() makes (no menus in the prologue's
     * intro, the epilogue, cutscenes, or while input is off) */
    {
        s32 story = evt_get_variable(NULL, GB_StoryProgress);
        s32 menus = inGame && mode == GAME_MODE_WORLD && gGameStatusPtr->demoState == 0 &&
                    !(gGameStatusPtr->peachFlags & PEACH_FLAG_IS_PEACH) && story != STORY_INTRO &&
                    story < STORY_EPILOGUE && gPlayerStatus.inputDisabledCount == 0 &&
                    !(gPlayerStatus.flags & PS_FLAG_PAUSE_DISABLED) &&
                    !(gOverrideFlags & GLOBAL_OVERRIDES_DISABLE_MENUS);
        jb_int(b, "menus", menus);
    }
    if (!inGame) {
        jb_raw(b, "}");
        return;
    }
    jb_int(b, "hp", pd->curHP);
    jb_int(b, "maxHp", pd->curMaxHP);
    jb_int(b, "fp", pd->curFP);
    jb_int(b, "maxFp", pd->curMaxFP);
    jb_int(b, "bp", pd->maxBP);
    jb_int(b, "level", pd->level);
    jb_int(b, "starPoints", pd->starPoints);
    jb_int(b, "coins", pd->coins);
    jb_int(b, "starPieces", pd->starPieces);
    jb_int(b, "starPower", pd->starPower);
    jb_int(b, "maxStarPower", pd->maxStarPower);
    jb_int(b, "boots", pd->bootsLevel);
    jb_int(b, "hammer", pd->hammerLevel);
    jb_int(b, "playSeconds", (s32) (pd->frameCounter / 60));
    jb_int(b, "chapter", chapter_of(evt_get_variable(NULL, GB_StoryProgress)));
    jb_int(b, "partner", pd->curPartner);

    if (gGameStatusPtr->areaID >= 0 && gAreas[gGameStatusPtr->areaID].id != NULL) {
        areaId = gAreas[gGameStatusPtr->areaID].id;
        for (u32 i = 0; i < ARRAY_COUNT(sAreaNames); i++) {
            if (strcmp(areaId, sAreaNames[i].area) == 0) {
                location = sAreaNames[i].name;
            }
        }
    }
    mc = get_current_map_config();
    if (mc != NULL && mc->id != NULL && strcmp(mc->id, "kmr_20") == 0) {
        location = "Mario's House";
    }
    jb_text(b, "area", areaId);
    jb_text(b, "location", location);
    jb_text(b, "map", (mc != NULL && mc->id != NULL) ? mc->id : "");

    /* party, in the pause menu's order */
    jb_key(b, "partners");
    jb_raw(b, "[");
    for (s32 i = 0; i < 8; i++) {
        s32 id = gPausePartnersPartnerIDs[i];
        char name[NAME_LEN];
        companion_decode_msg(gPartnerPopupProperties[id].nameMsg, name, sizeof(name), 0);
        jb_item_sep(b);
        jb_raw(b, "{");
        jb_int(b, "id", id);
        jb_text(b, "name", name);
        jb_int(b, "owned", pd->partners[id].enabled != 0);
        jb_int(b, "rank", pd->partners[id].level);
        jb_raw(b, "}");
    }
    jb_raw(b, "]");

    /* world map: the pause menu's spaces, visited flags and Mario's location */
    {
        s32 here = evt_get_variable(NULL, GB_WorldLocation);
        s32 current = -1;
        jb_key(b, "spaces");
        jb_raw(b, "[");
        for (s32 i = 0; i < COMPANION_MAP_SPACES; i++) {
            if (PauseMapSpaces[i].id == here) {
                current = i;
            }
            jb_item_sep(b);
            jb_raw(b, "{");
            jb_int(b, "x", PauseMapSpaces[i].pos.x);
            jb_int(b, "y", PauseMapSpaces[i].pos.y);
            jb_int(b, "parent", PauseMapSpaces[i].parent);
            jb_int(b, "visited", evt_get_variable(NULL, GF_MAP_ToadTown + i) != 0);
            jb_text(b, "name", space_name(i));
            jb_raw(b, "}");
        }
        jb_raw(b, "]");
        jb_int(b, "space", current);
    }
    jb_raw(b, "}");
}

static s32 type_tattled(s32 type) {
    s32 byte = get_global_byte(EVT_INDEX_OF_GAME_BYTE(GB_Tattles_00) + type / 8);
    byte |= gBattleStatus.tattleFlags[type / 8];
    return (byte >> (type % 8)) & 1;
}

static void build_tattle(JsonBuf* b) {
    s32 known = 0, total = 0;
    jb_raw(b, "{");
    jb_key(b, "list");
    jb_raw(b, "[");
    for (s32 t = 0; t < COMPANION_ACTOR_TYPES; t++) {
        s32 msg = bActorTattles[t];
        s32 dup = 0;
        if (msg == MSG_NONE || msg == 0) {
            continue;
        }
        /* boss copies and flying variants share a tattle message: list it once */
        for (s32 e = 0; e < t; e++) {
            if (bActorTattles[e] == msg) {
                dup = 1;
                break;
            }
        }
        if (dup) {
            continue;
        }
        total++;
        if (!type_tattled(t)) {
            continue;
        }
        known++;
        jb_item_sep(b);
        jb_raw(b, "{");
        jb_int(b, "type", t);
        jb_text(b, "name", actor_name(t));
        jb_raw(b, "}");
    }
    jb_raw(b, "]");
    jb_int(b, "known", known);
    jb_int(b, "total", total);
    jb_raw(b, "}");
}

static void build_recipes(JsonBuf* b) {
    s32 cooked = 0;
    jb_raw(b, "{");
    jb_key(b, "list");
    jb_raw(b, "[");
    for (s32 i = 0; i < COMPANION_RECIPES; i++) {
        s32 item = CookableItemIDs[i];
        s32 done = get_global_flag(CookableDiscoveredFlags[i]) != 0;
        cooked += done;
        jb_item_sep(b);
        jb_raw(b, "{");
        jb_int(b, "item", item);
        jb_text(b, "name", item_name(item));
        jb_int(b, "done", done);
        jb_raw(b, "}");
    }
    jb_raw(b, "]");
    jb_int(b, "cooked", cooked);
    jb_int(b, "total", COMPANION_RECIPES);
    jb_raw(b, "}");
}

static void build_badges(JsonBuf* b) {
    PlayerData* pd = &gPlayerData;
    s32 used = 0;
    jb_raw(b, "{");
    jb_key(b, "list");
    jb_raw(b, "[");
    for (s32 i = 0; i < ARRAY_COUNT(pd->badges); i++) {
        s32 item = pd->badges[i];
        s32 on = 0;
        if (item == ITEM_NONE) {
            continue;
        }
        for (s32 j = 0; j < ARRAY_COUNT(pd->equippedBadges); j++) {
            if (pd->equippedBadges[j] == item) {
                on = 1;
                break;
            }
        }
        if (on) {
            used += gMoveTable[gItemTable[item].moveID].costBP;
        }
        jb_item_sep(b);
        jb_raw(b, "{");
        jb_int(b, "item", item);
        jb_text(b, "name", item_name(item));
        jb_int(b, "bp", gMoveTable[gItemTable[item].moveID].costBP);
        jb_int(b, "on", on);
        jb_raw(b, "}");
    }
    jb_raw(b, "]");
    jb_int(b, "bpMax", pd->maxBP);
    jb_int(b, "bpUsed", used);
    jb_raw(b, "}");
}

static void list_items(JsonBuf* b, const char* key, const s16* items, s32 count) {
    jb_key(b, key);
    jb_raw(b, "[");
    for (s32 i = 0; i < count; i++) {
        if (items[i] == ITEM_NONE) {
            continue;
        }
        jb_item_sep(b);
        jb_raw(b, "{");
        jb_int(b, "item", items[i]);
        jb_text(b, "name", item_name(items[i]));
        jb_raw(b, "}");
    }
    jb_raw(b, "]");
}

static void build_items(JsonBuf* b) {
    PlayerData* pd = &gPlayerData;
    jb_raw(b, "{");
    list_items(b, "items", pd->invItems, ARRAY_COUNT(pd->invItems));
    list_items(b, "keyItems", pd->keyItems, ARRAY_COUNT(pd->keyItems));
    list_items(b, "stored", pd->storedItems, ARRAY_COUNT(pd->storedItems));
    jb_raw(b, "}");
}

/* ---- frame hook ------------------------------------------------------------- */

static char sJson[24 * 1024];

void companion_game_frame(void) {
    static u32 sFrame;
    JsonBuf b;
    s32 tattle;

    if (gGameStatusPtr == NULL) {
        return;
    }
    sFrame++;
    check_language();

    b.p = sJson;
    b.cap = sizeof(sJson);

    /* stats move every frame in battle; 6 game frames keep the HP bars live
     * without rebuilding at the render rate */
    if (companion_store_wanted(COMPANION_PAGE_STATS) && sFrame % 6 == 0) {
        b.n = 0;
        build_stats(&b);
        companion_store_page(COMPANION_PAGE_STATS, sJson);
    }
    if (!in_game()) {
        return;
    }
    if (sFrame % 30 == 3 && companion_store_wanted(COMPANION_PAGE_TATTLE)) {
        b.n = 0;
        build_tattle(&b);
        companion_store_page(COMPANION_PAGE_TATTLE, sJson);
    }
    if (sFrame % 30 == 9 && companion_store_wanted(COMPANION_PAGE_RECIPES)) {
        b.n = 0;
        build_recipes(&b);
        companion_store_page(COMPANION_PAGE_RECIPES, sJson);
    }
    if (sFrame % 30 == 15 && companion_store_wanted(COMPANION_PAGE_BADGES)) {
        b.n = 0;
        build_badges(&b);
        companion_store_page(COMPANION_PAGE_BADGES, sJson);
    }
    if (sFrame % 30 == 21 && companion_store_wanted(COMPANION_PAGE_ITEMS)) {
        b.n = 0;
        build_items(&b);
        companion_store_page(COMPANION_PAGE_ITEMS, sJson);
    }
    tattle = companion_store_take_tattle_request();
    if (tattle >= 0 && tattle < COMPANION_ACTOR_TYPES) {
        static char text[1024];
        if (type_tattled(tattle)) {
            companion_decode_msg(bActorTattles[tattle], text, sizeof(text), 1);
        } else {
            text[0] = '\0';
        }
        companion_store_tattle_text(tattle, text);
    }
}
