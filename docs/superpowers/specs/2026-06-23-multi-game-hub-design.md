# KanoLauncher — Multi-Game Hub Design

**Date:** 2026-06-23
**Status:** Approved design, ready for implementation planning
**Scope of this spec:** turn the Minecraft-only launcher into a unified, themed **game hub** that lists, launches, and organizes all the user's installed games, with Minecraft as a first-class member and per-game profiles/theming. Mod *discovery* per game is in scope; full in-app mod install for non-Minecraft games is explicitly deferred.

---

## 1. Goal & vision

One themed place that lists and launches all the user's installed games (Steam, Epic, GOG, manually-added, and Minecraft), organizes them flexibly, and gives each game its own look and settings. Think a personal, themed Playnite — but with the existing deep Minecraft experience reused wholesale as the richest "game" in the library.

**Non-goals (this spec):**
- Downloading/installing the games themselves (Steam/Epic own that — the hub launches already-installed games; Minecraft remains the one exception it fully downloads, via existing code).
- In-app mod *browsing/installing* for non-Minecraft games (future per-source layer; see §7).
- Deep, reliable Xbox/Game Pass / EA / Ubisoft integration (best-effort only; see §5).

**Success criteria:**
- The launcher opens to a game library showing the user's installed Steam games + Minecraft + any manually-added games, with cover art.
- Any game launches from the hub.
- Games can be organized via custom collections, platform groups, a favorites/recents row, and a sort toggle.
- Each game has its own accent color (shown as a tile glow + applied when opened) and its own settings profile.
- A game can be favorited (★) and one game can be set as "open into on startup."
- Adding a game auto-discovers the most reliable mod source for it and surfaces it.
- The existing Minecraft experience is unchanged in behavior, reached by opening the Minecraft tile.

---

## 2. Approach (chosen)

**Approach A — Game abstraction + a library shell around the existing app.** Additive: a new model + shell layer sits *above* the current Minecraft code, which is reused unchanged. Minecraft becomes one `Game` whose "open" routes into the existing Minecraft UI. Rejected alternatives: full generalization (massive rewrite, high risk — violates YAGNI) and a side "Games" tab (not a true unified hub).

---

## 3. Core model & components

Each unit has one clear job and a defined interface.

### `Game` (model, persisted in `games.json`)
Fields: `id`, `name`, `source` (`STEAM | EPIC | GOG | STANDALONE | MINECRAFT`), `installDir`, `launchTarget` (protocol URL / exe path / native marker), `coverArtPath`, `lastPlayedEpoch`, `playtimeMinutes`, `favorite` (bool), `startupGame` (bool — at most one true), `categories` (list of collection names), `accentHex` (per-game theme color), `moddable` (bool), `modSource` (resolved mod host + identifier; see §7), `launchOptions` (custom args), `workingDirOverride`, `preLaunch`/`postLaunch` (optional commands).

### `GameSource` (interface)
`List<Game> scan()` — one implementation per platform. Each is independently testable against sample manifest fixtures. Implementations: `SteamSource`, `StandaloneSource`, `EpicSource`, `GogSource`, `MinecraftSource` (returns one synthetic always-present Minecraft game). Adding a platform = adding one class; nothing else changes.

### `GameLibrary` (core)
Calls every enabled `GameSource`, merges results with the user's saved `games.json` (matching by `source`+`id`), **always preserving user overrides** (favorite, categories, accentHex, custom name/art, launchOptions, startupGame, manually-added games), dedupes, and persists. Single source of truth for the hub. Re-scans on launch and on manual Refresh.

### Launch routing — `GameRunner.launch(Game)`
Dispatches by `source`: Steam → `steam://rungameid/<appid>`; Epic → `com.epicgames.launcher://apps/<id>?action=launch`; GOG → its protocol/exe; Standalone → run `exe` (with `launchOptions`/`workingDirOverride`); **Minecraft → existing launch flow, unchanged.**

**Last-played vs. playtime — honesty note:** `lastPlayed` is recorded reliably (we know when Play was clicked), so the recents row and sort-by-recent are accurate for every game. **Play *duration* and live "is it running" are best-effort:** protocol-launched games (Steam/Epic) hand off to that platform, so we don't own the child process — duration is approximated by watching for the game's exe name when known, and omitted otherwise (Steam already tracks playtime itself). Minecraft and Standalone games (real owned processes) track both reliably. Sort-by-playtime ranks on whatever was captured and sinks unknowns to the bottom.

### `ModSourceResolver` (core; see §7)
Given a `Game`, returns the most reliable mod host + that host's identifier for the game.

### UI: `GameLibraryView` (new root) + per-game detail
A new top-level view (its own class, to avoid further bloating the 3,700-line `MainApp`). The Minecraft tile routes into the existing Minecraft views (`showHome`/instances/etc.), which gain a "← Hub" affordance. Other games open a lightweight detail/profile page.

> **Targeted improvement:** `MainApp` is already very large. The hub (library view, game detail, collection editor) lives in new dedicated classes rather than being piled into `MainApp`. The Minecraft views are left where they are.

---

## 4. Hub UI & organization

The library combines all four organization modes the user chose:
- **Favorites + Recently-played row** pinned at the top; favorited games show a **★**.
- A **view toggle** between **custom collections** (user-made groups: "Modded", "Co-op", …) and **auto platform groups** (Steam / Epic / GOG / Minecraft).
- A **sort dropdown** (A–Z, recently played, most playtime, moddable-first) reorders within the current view.
- **Tiles**: cover art + title + ★ (if favorited) + a **colored glow/border = the game's accent**. A Play button launches directly; clicking the tile opens the game's profile/detail.

**Per-game appearance:** each game's `accentHex` shows as its tile glow on the hub, and the whole UI re-themes to that color when the game is opened (reuses the existing `applyTheme` engine, which tints from one hex). The hub itself uses a neutral default accent.

**Favorite / jump-straight-in:** `favorite` drives the ★ and the favorites row. A Settings option **"On startup, open: Hub / Last played / <specific game>"** controls the landing screen; choosing a game sets its `startupGame`. The **Hub** is always one click away from any game (incl. Minecraft).

**Per-game settings profile** (the game detail's Settings):
- **Minecraft:** unchanged — RAM, JVM args, resolution, loader, *plus* its existing per-instance settings.
- **Steam/Epic/GOG/Standalone:** custom launch options/args, exe or working-dir override, optional pre/post-launch actions, mods-folder link, cover art, categories, accent color. (RAM/JVM tuning is Java-only and applies to Minecraft; other games use launch args for equivalent tweaks.)

---

## 5. Game detection & phasing

Each platform is detected by reading where it records installed games:

- **Steam** (Phase 1): parse `libraryfolders.vdf` → each library's `steamapps/appmanifest_*.acf` → `appid`, `name`, `installdir`. Launch via `steam://rungameid/<appid>`.
- **Standalone** (Phase 1): user adds any `.exe` (name, art, launch args, working dir). The always-works catch-all.
- **Epic** (Phase 2): parse `%ProgramData%/Epic/EpicGamesLauncher/Data/Manifests/*.item` (JSON) → name, install location, app name. Launch via Epic protocol.
- **GOG** (Phase 2): GOG Galaxy's local SQLite DB (or registry) → installed products. Launch via exe/protocol.
- **Xbox / EA / Ubisoft** (Phase 3, best-effort): sandboxed/registry-based and flaky — auto-detect where feasible, otherwise the user adds them as Standalone.

**Phasing summary:**
- **Phase 1 = Steam + Standalone + Minecraft** — the complete working hub: detection, library, all organization modes, per-game profiles/theming, favorites, jump-straight-in, mod-source discovery (§7), cover art.
- **Phase 2 = Epic + GOG** — additional `GameSource`s, no rework.
- **Phase 3 = Xbox/EA/Ubisoft** — best-effort sources.

**Cover art:** Steam games get art free from Steam's CDN by app id. SteamGridDB (free key) is an optional enrichment for non-Steam games. The user can always set custom art per game. Missing art falls back to a generated tile (title + accent), consistent with the current block-icon style.

---

## 6. Minecraft integration

Minecraft is a synthetic `Game` (always present, `source = MINECRAFT`). Opening it routes into the current Minecraft UI with **zero behavior change**; that UI gains a "← Hub" control. Its accent (default green) themes its section. All existing Minecraft features — instances, Browse/Modrinth, CurseForge import, performance pack, worlds, servers, accounts, etc. — are untouched and live "inside" the Minecraft tile.

---

## 7. Mod-source discovery (on Add Game)

When a game is added/first seen, `ModSourceResolver` identifies the game (name + Steam app id if applicable) and checks mod hosts in order of reliability:

1. **Steam Workshop** — if it's a Steam game with Workshop support (keyed off app id): most reliable, first-party.
2. **Thunderstore** — query its public communities list for a name match (BepInEx/Unity games: Lethal Company, Valheim, Risk of Rain 2…). Clean, Modrinth-like API.
3. **Nexus Mods** — for Bethesda/large single-player games (Skyrim, Fallout, Stardew, Cyberpunk…). **Note:** the Nexus *API* requires a free personal key, so to keep v1 **keyless**, Nexus discovery uses a small curated map of well-known Nexus games plus a "search Nexus for `<game>`" link — no key needed. (A user key + the Nexus API is only required for the future in-app layer, not for v1 discovery/linking.)
4. **Modrinth / CurseForge** — Minecraft (already built in).
5. **Fallback** — no confident match → offer "set source manually / search the web."

The resolved source is stored on the `Game` (`modSource`) and shown on its profile.

**Reliability handling:** name→host matching isn't perfect, so it's **auto-detect + a quick confirm/override** ("Found Thunderstore for this — correct?"), never blind. A small curated map of well-known games seeds the most reliable answers; live API lookups cover the rest.

**v1 boundary:** v1 **surfaces** the source — smart links to the right mod site + an **Open mods folder** button. **Full in-app browse/install for non-Minecraft games is the next layer, per source, Thunderstore first** (its API is the most Modrinth-like and would slot into the existing Browse UI). Minecraft keeps full in-app browse/install as it is now. "Resource packs / shaders" as distinct categories are a Minecraft concept; other games expose a single "mods" bucket as their host names it.

---

## 8. Data & persistence

- **`games.json`** in the launcher data dir: the merged library — detected games + manually-added games + all user overrides. Merge on every scan by `source`+`id`, preserving overrides; orphaned auto-detected entries (game uninstalled) are flagged "not installed" rather than deleted if the user customized them.
- **Per-game accent** reuses the existing `Config`/theme engine (one hex → full palette).
- Existing `config.json` (global settings) gains: startup target (Hub / Last played / game id), and default hub view/sort.
- **Backward-compatible:** existing users' Minecraft instances and `config.json` are untouched. On first launch of the hub build, `games.json` doesn't exist yet → the first scan creates it (Minecraft always present via `MinecraftSource`, plus whatever Steam/etc. detection finds). Nothing about the current Minecraft data is migrated or moved.

---

## 9. Testing

- **Unit-test each `GameSource`** against checked-in sample fixtures (a real `appmanifest.acf`, an Epic `.item`, a GOG DB row) → asserts correct parse to `Game`s. Pure parsing logic, TDD-friendly.
- **`GameLibrary` merge/dedup/persist**: detected + saved overrides → preserved correctly; uninstall flagging; manual-add round-trip.
- **`GameRunner`**: correct launch command/URL built per source (no actual launch in the unit test).
- **`ModSourceResolver`**: known games map to expected hosts; ambiguous → fallback.
- **Smoke test** on the user's real Steam/Epic installs: detection, art, launch.

---

## 10. Build order (for the implementation plan)

1. `Game` model + `games.json` persistence + `GameLibrary` merge.
2. `SteamSource` + `StandaloneSource` + `MinecraftSource`; `GameRunner` launch routing.
3. `GameLibraryView` (hub) with tiles + cover art; route Minecraft tile into existing UI + "← Hub".
4. Organization: favorites/★, recents row, collections, platform groups, sort toggle.
5. Per-game profiles: accent theming, settings page, startup-target setting, jump-straight-in.
6. `ModSourceResolver` + Add-Game discovery (confirm/override) + surface links/open-folder.
7. Phase 2 (`EpicSource`, `GogSource`); Phase 3 (best-effort) and per-source in-app mods (Thunderstore) as later milestones.

Phase 1 = steps 1–6.
