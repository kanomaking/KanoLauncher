# KanoLauncher — Personal Minecraft Launcher Build Plan

**Version 2.0 (corrected)**
Design language: Red-heavy on black, silver accents — sleek, classy, dark mode.

> This is the revised plan. It replaces v1 after a technical review. The original was a good idea but tried to do too much at once and had several claims that don't work as written. This version keeps the vision, cuts what can't work, fixes what was wrong, and orders the work so it's actually buildable solo.

---

## 0. Read This First — The Reality Check

- **Scope:** v1 is deliberately small. One account, one running instance, Modrinth only, Vanilla + Fabric, log in and play, on Windows + Ubuntu. Everything else is deferred to its own buffered milestone.
- **The biggest risk is the Microsoft login approval** (Section 1). It's an external manual review with no promised timeline that blocks *all* online features. Start it in week 1.
- **The name `KanoLauncher` is fine.** The only naming rule is: never put "Minecraft" or other Mojang/Microsoft trademarks in the product name, and don't imply it's official. No rename needed.

---

## 1. Microsoft Login Approval (DO THIS WEEK 1)

To log into Minecraft from your own launcher, **Microsoft must manually approve your app.** Until they do, every call to `api.minecraftservices.com` (login, profile, skin) returns `403`. The Xbox sign-in steps still succeed, which makes this confusing to debug — the failure shows up at the final Minecraft step.

### How to get approved
1. **Azure portal → Microsoft Entra ID → App registrations → New registration.**
2. **Name:** `KanoLauncher`. **Account types:** "Personal Microsoft accounts only" (this is the `consumers` tenant).
3. **Authentication tab → turn ON "Allow public client flows".** Register as a **public/native client**. **No client secret** — a confidential registration causes `unauthorized_client`, and you must never embed a secret in a downloadable binary anyway.
4. **Copy the Application (client) ID** — this is what the launcher uses.
5. **Build the device-code login and sign in once with a real account** to generate login activity Microsoft can see.
6. **Submit the approval request:** https://aka.ms/mce-reviewappid (provide the client ID + app details).
7. **Wait:** manual review, no SLA (days to weeks). After approval, up to ~24h to propagate.

### Auth implementation details (these silently fail if wrong)
- **Tenant:** use `login.microsoftonline.com/consumers/...` — NOT `common` or `organizations`.
- **Scopes:** `XboxLive.signin offline_access`. The `offline_access` scope is required to get a refresh token.
- **Token chain every launch:** fresh MSA token → Xbox Live (XBL) → XSTS → Minecraft token. Don't cache the MC token past its ~24h life; re-run the chain from a fresh MSA token each launch.
- **Per-account state machine:** `valid` / `needs-refresh` / `needs-reauth`. MSA refresh token is long-lived but revocable — on refresh failure, prompt re-login for that account.
- **Handle XSTS error codes:** `2148916233` (no Xbox account), `2148916238` (child account / needs adult consent).
- **"Not yet approved" is a first-class UI state** — show it clearly while waiting on Microsoft.

### Token storage
- Encrypt the saved refresh token with **AES-256-GCM**. Fresh random 96-bit nonce per record, stored alongside the ciphertext, never reused.
- **Wrap the encryption key in the OS keystore** — DPAPI / Credential Manager on Windows, libsecret / Secret Service on Ubuntu. **Never** a static key embedded in the app.

---

## 2. Vision & Core Philosophy

A private, cross-platform (Windows & Ubuntu) Minecraft launcher that does a few things very well:

- **Trusted content** — Modrinth-first. Clean dependency resolution, compatibility filtering, safe installs.
- **Sensible performance** — honest JVM tuning based on the user's actual hardware. No snake-oil.
- **Log in and play** — fast, reliable launch loop on both OSes.
- **Premium minimalist UI** — Modrinth-inspired, red/black/silver, zero bloat.
- **Quality-of-life extras (later)** — multi-account, multi-instance, skin changer, a tiny FPS/coords overlay.

---

## 3. Technology Stack

| Layer | Choice | Notes |
|---|---|---|
| Language | Java 21+ (Kotlin optional) | Native Minecraft tooling. |
| UI | JavaFX 21 + CSS | Use the `org.openjfx.javafxplugin` Gradle plugin; pull platform-classified natives; build per-OS in CI. Bundle your own font (don't assume Segoe UI exists on Ubuntu). Test HiDPI on real GTK. |
| Packaging | jpackage | **Windows: `.msi`. Ubuntu: `.deb`.** jpackage does NOT make AppImage — its Linux outputs are `app-image` (a folder), `deb`, `rpm`. (AppImage would need separate `appimagetool` tooling — skip it.) |
| Java runtime | Shared per-major-version JREs | Minecraft needs ~3 Java majors total (8 legacy / 17 / 21). Bundle by major version in one shared per-user store, reference by version, download on demand from the Adoptium API. NOT one JRE per instance (wasteful; security patching nightmare). |
| Download engine | One reused `HttpClient` | Connection pooling. Cap concurrency **per host**. HTTP Range resume for big files (JREs, modpack zips). Atomic temp-write → verify SHA-1 → move into store. |
| Auth | Microsoft OAuth2 device-code (see §1) | |
| Build | Gradle (Kotlin DSL) | Multi-project. |

---

## 4. Feature Specifications

### 4.1 Instance Manager
- Create instances: pick MC version + loader (Vanilla, Fabric for v1; NeoForge/Forge/Quilt later).
- Each instance gets its own game directory: `instances/<version>/<name>`.
- **Per-instance settings: RAM defaults to 4 GB**, JVM flags, bundled Java major version, resolution, fullscreen.
- Clone, export (`.mrpack` / zip), import `.mrpack`.
- Shared **read-only** asset/library cache across instances (see §4.5).

### 4.2 Game Files — NEVER Bundle Them
- **Do not bundle Minecraft's client jar, assets, or libraries** into the launcher. Writing a launcher is tolerated; redistributing Mojang's files is not (EULA-enforced; see the 2023 Eaglercraft DMCAs).
- Fetch them per-user at runtime from the official version manifest, checksum-validate, and cache.

### 4.3 Content Management (Modrinth-first)
- **v1: Modrinth only.** No API token needed for browse/search/download.
- **Rate limit: 300 req/min shared.** Your download threads + search + dependency expansion can burst past it — add a client-side limiter + 429 backoff.
- **Mandatory unique User-Agent** with contact info, e.g. `github_user/KanoLauncher/1.0 (contact)`. Generic UAs like `Java-HttpClient` get flagged/blocked.
- **Dependency resolution is Modrinth-only** — clean graph, no download blocks. (This is the part v1 above all depends on being simple.)
- **Compatibility filter** = "filtered by declared compatibility," not a guarantee. Frame it honestly.
- **Conflict detection** = only what metadata supports: loader mismatch, MC-version mismatch, declared `incompatible` deps, duplicate-by-filename/mod-id. Show as warnings; don't promise semantic conflict resolution.
- **Performance Pack toggle** = curated optimisation mods (Sodium, Lithium, Starlight…). **Fabric-only — make the toggle loader-aware.**
- **`.mrpack` import** — ✅ shipped (see §11). Validates each file against the pack's sha1; honors the URL whitelist (`cdn.modrinth.com`, `github.com`, `raw.githubusercontent.com`, `gitlab.com` + subdomains, no CurseForge); extracts `overrides/`; creates a new instance from the pack's declared MC + loader.

### 4.4 CurseForge — DEFERRED (modpacks only)
Modrinth covers almost everything you want. CurseForge is added **later**, just to grab modpacks Modrinth doesn't have. When you do:
- **User supplies their own approved CurseForge API key** — never bundle one.
- **CF CDN downloads require the `x-api-key` header** (enforced). Attach it to CurseForge hosts ONLY, as a header (never a query param — leaks to logs). Never send it to Modrinth/GitHub/Mojang.
- **Handle `downloadUrl: null`** (author opted out of third-party distribution): don't fail silently — show a "download manually on CurseForge" deep link and mark that dependency chain blocked.
- Keep CF strictly best-effort and secondary; don't position the launcher as a CurseForge competitor (their ToS bites rival-platform entanglement, not launchers generically).

### 4.5 Assets & Cache
- Mojang assets are **immutable, SHA-1 content-addressed objects** — there is no "delta update," only present-or-fetch.
- Maintain a shared `assets/objects/` store keyed by SHA-1. On update, diff the new asset index against on-disk hashes, fetch only what's missing. Same idea for libraries (immutable by Maven coordinate).
- Checksum-validate everything; re-download corrupt files. Progress with speed + ETA.

### 4.6 Performance & JVM Tuning (honest version)
- **Default heap: 4 GB per instance.** More heap ≠ more FPS; oversized G1 heaps lengthen pauses. Scale to RAM + mod profile; clamp to ≤50% of detected RAM. (Matters more once multi-instance exists — see §4.8.)
- **Default GC: G1 + Aikar's flags.** Key the flag set off the **actual bundled JRE major version.**
  - **Do NOT mix ZGC with Aikar/G1 flags** — Aikar flags (`G1NewSizePercent`, `MaxGCPauseMillis`, `AllocatePrefetchStyle=3`, etc.) are G1-only and are ignored or harmful under ZGC.
  - If offering ZGC as an opt-in for high-RAM/high-core machines: on JDK 21/22 you must add `-XX:+ZGenerational` (plain `-XX:+UseZGC` there = the older, ~15–25% slower variant). Drop `+ZGenerational` on JDK 23+ (deprecated/removed).
- **Cut "JVM warmup"** — JIT state lives in the process and dies on exit; a separate warmup JVM transfers nothing. If you want a real startup win later, generate an **AppCDS / dynamic-CDS archive per bundled JRE** (helps startup time, not in-game FPS).
- **Cut "Chunky pre-launch world pre-generation"** — Chunky runs *in-game* and needs a loaded world + OP. Doing it from the launcher would mean spinning up a headless server + driving Chunky over RCON (a minutes-to-hours background job), not a launch-time step.

### 4.7 Updates & Notifications (your spec)
- **The launcher never updates while you're playing.**
- It checks GitHub Releases for a newer version and shows an **"Update available"** indicator.
- The user gets a **"Restart Launcher to Update"** button. Clicking it: closes the app → a tiny separate **bootstrapper/helper** swaps the files while the main app is closed → relaunches.
  - This is required because Windows file-locks the running app and you can't overwrite it in place.
  - The bootstrapper itself is never updated (it's the stable outer shell). With the `.msi` install model, self-update only the inner app jars, or re-run the installer for major releases.
- **Minecraft version updates:** poll the Mojang version manifest daily; show a badge for new releases.
- **Mod updates:** ✅ shipped — per-instance tracking (`.kano-mods.json`) + a gold **"Update All (N)"** button that appears only when newer compatible builds exist (see §11). *Still wanted: a per-mod row indicator/button so individual mods can be updated without the bulk action.*

### 4.8 Multi-Account & Multi-Instance — DEFERRED
- v1 is single-account, single running instance. Add later.
- When added: up to 2 accounts, hard cap of **2–3 concurrent instances** gated on a runtime RAM check (4 GB × 3 = 12 GB — don't let users oversubscribe).
- **No "auto-assigned ports."** A Minecraft client opens *outbound* connections to join servers; it only listens on a port when the user clicks "Open to LAN," and the game picks that itself. There's nothing for the launcher to assign. Isolation = separate game dirs + shared read-only cache, not ports.

### 4.9 Skin Changer — DEFERRED
- The official skin API is current and works: `POST api.minecraftservices.com/minecraft/profile/skins` (multipart: `variant=classic|slim` + PNG, Bearer MC token); `DELETE …/skins/active` resets.
- Inherits the Azure-approval gate from §1.
- Validate 64×64 / 64×32 PNG client-side. Throttle uploads; honor `429` / `Retry-After` (abuse can suspend the account).
- Target `api.minecraftservices.com` only — do NOT build an `api.mojang.com` legacy fallback.
- **Live preview:** render the local PNG yourself right after upload. Crafatar is fine for browsing *other* accounts' skins but lags (~20 min refresh + CDN cache) and is effectively unmaintained — don't rely on it for your own post-upload preview. Add cache-busting + graceful 5xx fallback.

### 4.10 In-Game Overlay — small FPS + coordinates (your spec)
- Goal: a small, out-of-the-way **FPS + coordinates** readout, top-left, visible while playing but not intrusive.
- **Do NOT build a bespoke overlay mod.** "One injected overlay mod" is secretly N separate mod projects — Fabric and Forge/NeoForge can't load the same jar, and Mixin hooks break across MC versions. That's a permanent rebuild treadmill.
- **Instead:** have the launcher install an existing, maintained FPS/coords HUD mod, matched to the instance's loader + version. One-click "enable overlay" that just adds the right mod to that instance.

---

## 5. Mod Loaders

- **v1: Vanilla + Fabric.** Fabric automates cleanly (official installer/CLI + Fabric Meta). Quilt runs the Fabric overlay jar anyway — low priority.
- **Later, add NeoForge as first-class.** NeoForge is the de-facto standard for MC 1.21+ (most popular Forge-family mods are NeoForge-first; NeoForge and Forge mods are mutually incompatible on 1.21+). Listing "Forge" but not "NeoForge" would miss most current content.
- **Forge/NeoForge install is real work:** there's no official headless *client* installer (the client path opens a Swing GUI and throws `HeadlessException`; only `--installServer` is headless). Replicate mature launchers: run the installer's processors, then parse the produced version JSON. ✅ **Done** — `ForgeSupport`/`ForgeVersions` do exactly this (see §11). Install engine verified end-to-end; in-game boot still needs real-world testing.
- **Quilt:** keep cheap install support if you want, but don't build a dedicated Quilt overlay (it dropped Fabric-API-compat maintenance Dec 2025; Fabric jars run on it). Spend that effort on NeoForge.

---

## 6. UI/UX — "Red Heavy on Black with Silver Accents"

- **Palette:** background `#0A0A0A`; primary red `#D32F2F` (buttons, active tabs, progress, sidebar highlights); secondary red `#B71C1C` (hover, headers); silver `#C0C0C0` (text, icons, borders); secondary text `#9E9E9E`.
- **Type:** clean sans-serif (Inter / Segoe UI) — bundle the font for Ubuntu.
- **Layout (Modrinth-inspired):** left sidebar (220px, collapsible: Library / Accounts / Settings); main tabs: **Play** (big red Play, RAM/resolution sliders, perf toggles), **Mods** (list w/ thumbnails + red update badges + "+ Add Mod" panel), **Resource Packs / Shaders**. Top bar: minimal, account switcher w/ avatar.
- **Transitions:** 250ms fade/slide, red ripple on buttons. No news feed, no ads.

---

## 7. Data & File Layout

```
KanoLauncher/                 (%APPDATA%/KanoLauncher on Windows, ~/.config/KanoLauncher on Linux)
├── bootstrapper(.exe)        stable outer shell — performs self-update, never updated itself
├── app/                      the updatable inner app jars
├── config.json
├── accounts.enc              AES-256-GCM, key wrapped in OS keystore
├── runtimes/                 shared JREs by major version (8 / 17 / 21), from Adoptium
├── cache/
│   ├── libraries/            immutable by Maven coordinate
│   ├── assets/objects/       SHA-1 content-addressed store
│   └── mod-cache/
├── instances/
│   └── <version>/<name>/     full per-instance game dir
└── logs/
```

---

## 8. Roadmap — Realistic, Buffered

> The old "17 weeks for everything, solo" target was the core planning failure. This is a vertical slice first, then everything else as its own deferred, buffered milestone.

### Phase 1 (wks 1–5) — Launch loop + the approval clock running
- **Week 1, in parallel:** register the Azure app (§1), do a real device-code login to generate activity, submit `aka.ms/mce-reviewappid`. The review clock now runs while you build. Keep an auth stub so non-auth work isn't blocked.
- Full MSA → XBL → XSTS → MC token chain → AES-256-GCM token store (OS-keystore-wrapped) → per-account state machine.
- Runtime asset fetch (content-addressed cache). **Never bundle Mojang files.**
- Launch **Vanilla** on Windows.

### Phase 2 (wks 6–10) — Mods + Fabric + second OS + packaging
- Fabric install (official CLI + Fabric Meta). Performance Pack toggle (Fabric-only, loader-aware).
- Modrinth browse/search/install with **Modrinth-only** dependency resolution; `.mrpack` import (sha-validated).
- Ubuntu parity. Package **MSI (Windows) + .deb (Ubuntu)** via jpackage. Shared per-major JREs from Adoptium.

### Phase 3 (wks 11–14) — Polish + hardening
- Auto heap from detected RAM (**default 4 GB**, conservative; **G1 + Aikar**, JDK-version-aware flags), config UI.
- **Update-notify + "Restart Launcher to Update"** button via bootstrapper (NOT live self-update).
- Small FPS + coords overlay = install an existing maintained HUD mod per instance.
- Real end-to-end testing across loader / version / OS combos; failure handling (partial downloads, corrupt cache, offline mode, "app not yet approved").

### Deferred — each its own buffered milestone
CurseForge (user key + `null` downloadUrl fallback + host-scoped `x-api-key`) · Forge/NeoForge install (processor-based) · NeoForge as first-class · multi-account · multi-instance (2–3 cap, RAM-gated) · skin changer · AppCDS startup optimization.

### Cut outright
Bespoke cross-loader overlay mod · "JVM warmup" · Chunky "pre-launch" pre-generation · "delta" asset updates · "auto-assigned ports."

---

## 9. Quick Reference — What Changed From v1

| v1 said | v2 does |
|---|---|
| Self-update by replacing the jar live | Notify + "Restart Launcher to Update" via a bootstrapper (Windows locks the running file) |
| Default 8 GB RAM | Default **4 GB**, scaled to hardware, clamped ≤50% RAM |
| ZGC + standard preset | **G1 + Aikar**, JDK-version-aware; never mix the two flag families |
| Modrinth + CurseForge equally, one-click everything | **Modrinth-first**; CurseForge deferred (modpacks only, user key) |
| Custom injected overlay mod | Install an existing maintained **FPS + coords** HUD mod |
| jpackage → AppImage | jpackage → **.deb** on Ubuntu |
| "Delta updates for assets" | Content-addressed SHA-1 dedup (assets are immutable) |
| Per-instance bundled JREs | Shared **per-major-version** JREs |
| Forge listed, NeoForge absent | Add **NeoForge** as first-class (later); Forge = legacy |
| JVM warmup / Chunky pre-launch | Cut (don't work as described); AppCDS later for real startup gains |
| "Auto-assigned ports" | Removed (meaningless for a client) |
| Approval gate barely mentioned | **Front-loaded to week 1** as the top project risk |

---

## 10. Expanded Feature Backlog (post-v1)

Captured from later design passes. Difficulty: 🟢 straightforward · 🟡 moderate · 🔴 hard / needs research. These come **after** the v1 vertical slice and the approval gate; ordering within is rough priority.

### Performance (a core goal, not an afterthought)
The launcher should make Minecraft run as fast as possible out of the box — higher FPS, lower memory pressure.
- 🟢 **Curated Performance Pack** (Fabric): Sodium, Lithium, Ferrite Core / ModernFix, Entity Culling, etc. One toggle per instance (already in §4.3). Default-on for new Fabric instances.
- 🟢 **Smart JVM tuning**: G1 + Aikar flags, heap sized to hardware + mod profile (§4.6).
- 🟡 **AppCDS archive per JRE** for faster startup (§4.6).
- 🟡 **Per-instance OptiFine toggle** — see dedicated note below.

### OptiFine toggle (per instance)
- 🟡/🔴 Each instance gets an "Enable OptiFine" toggle that installs + wires OptiFine correctly for that version/loader.
- **Honest caveats (must surface in the UI):**
  - OptiFine on **Fabric** requires **OptiFabric**, and it **conflicts with Sodium** (the Performance Pack) — can't run both. The toggle must auto-disable the conflicting performance mods and warn.
  - OptiFine often **lags new MC versions** by weeks/months; for the latest versions it may not exist yet.
  - For most users **Sodium + Iris** (shaders) beats OptiFine on performance — recommend it in the toggle's help text, but honor the user's choice.
  - Headless OptiFine install needs running its installer jar; budget real work (similar to Forge automation).

### Smart mod browsing & management
- 🟡 **Multiple trusted sources (resilience + coverage)**: don't depend on one API. Primary = Modrinth; secondary = CurseForge. If one source's search is down (observed: Modrinth's search index returned 0 for everything for a stretch while the rest of its API worked) or a mod only exists on the other platform, fall back automatically. Already shipped a same-source resilience step: a **slug-fallback** that does a direct Modrinth project lookup when search returns empty. **CurseForge requires the user's own approved API key** (their 3rd-party API is gated — never bundle a key; see §4.4); add a settings field for it, then merge/label results by source and dedupe. Build the browser around a small `ContentSource` interface so adding sources is cheap.
- 🟢 **Exact-compatibility browsing**: only show mods / resource packs / shaders compatible with the instance's exact version **and** loader. Incompatible content doesn't appear (filter by declared compatibility — see §4.3 honesty note).
- 🟢 **One-click dependency resolution**: required libs/APIs/sub-deps added automatically in the background (Modrinth-only for clean graphs in v1 — §4.3/§4.4).
- 🟡 **Conflict detection + suggestions**: warn when Mod A breaks Mod B; suggest alternatives/reordering (scope to what metadata supports — declared incompatibilities, dup mod-ids; "suggest alternatives" is best-effort).
- 🟡 **Bulk operations**: ✅ *update all mods in one click* shipped (gold conditional "Update All" button, §11) + ✅ per-group bulk update/RAM (§11) + ✅ **per-mod-row update button**. ✅ **Export instance as .mrpack** (self-contained: mods + config bundled under overrides/, worlds excluded — round-trip verified). ✅ **Clone instance** (copies files, optional keep-worlds). Remaining: version-locked files[] export (refs instead of bundled jars).
- 🟢 **Drag-and-drop offline content**: ✅ shipped — drop any jar/pack onto an instance's content tab and it lands in that folder + appears in the list.

### Accounts
- 🟡 **Multi-account elegance**: multiple Microsoft accounts, avatars in the top bar, one-click switch, silent token refresh behind the scenes (never re-login unless required). (Builds on the deferred multi-account work + skin/avatar rendering.)

### Worlds & servers
- 🟡 **World management**: ✅ list worlds (size + last played), quick-launch (`--quickPlaySingleplayer`), open folder, delete — plus **backup** (zip a world into `backups/`) and **restore** (unzip a backup back into `saves/`, never clobbering an existing world; zip-slip-guarded). Remaining: seed display.
- 🟡 **Server sidebar**: ✅ shipped as a **Servers tab** per instance — add/remove favourites (`.kano-servers.json`), live status (online/max players, latency, version) via the standard Server List Ping protocol (`ServerPing`, verified live against Hypixel/CubeCraft), and **▶ Play = quick-join** (`--quickPlayMultiplayer host[:port]`).
- 🔴 **Shareable modpack links**: generate a one-click private install link for friends (needs a tiny hosting/redirect component even if the launcher stays personal).

### Power-user UX
- 🟡 **Command palette (Ctrl+K)**: universal smart search — type "launch SMP world", "update all mods", "upload new skin" and it runs.
- 🟡 **Customizable layout**: rearrange panels, per-list **grid/list toggle** (instances, mods), resizable sidebar. Persist layout to config.
- 🟡 **Instance groups**: tag instances ("Performance Testing", "Multiplayer", "Creative") and do bulk ops (update all, allocate RAM to a whole group).
- 🟡 **Scheduled tasks**: auto-update mods on a schedule (e.g. Friday night), pre-warm a favorite instance each morning. (OS scheduler or an in-app timer; launcher must be running for in-app.)
- 🟢 **Stats bar (bottom of launcher)**: track + display total hours played and worlds created.
- 🟡 **Privacy mode hotkey**: instantly minimize all game windows + mute game audio, restoring a neutral desktop.

### In-game look (matching the launcher)
The launcher can't theme Minecraft's OS window frame or 3D rendering (separate process/engine), but it can make the in-game UI feel consistent:
- 🟡 **Kano resource pack** — a bundled pack that restyles in-game menus (buttons, panels, fonts, backgrounds) in red/black/gold, auto-applied/enabled on new instances so the title screen, pause menu, and inventory match the launcher.
- 🟢 **Launcher window/taskbar icon = the king** (done — uses `king-avatar.png` if present, else `king-bg.png`).
- 🔴 **Game window icon** = the king: harder — Minecraft sets its own window icon from its assets; overriding needs an asset swap or a small mod. Defer.

### Hard / research-flagged (high payoff, real complexity)
- 🔴 **Instance-aware mod profiles (per-world / per-server)**: within one instance, define "mod sets" that auto-activate based on the world loaded or server joined (e.g. full questing pack solo, lean perf/QoL set on a server) — no duplicate instances. **Why it's hard:** requires per-launch mod-folder swapping or a custom loader injector; activating "on server connect" mid-session is the genuinely hard part (likely simplified to *choose a profile at launch* for v1 of this feature).
- 🔴 **Live instance dashboard**: real-time FPS / memory / GC pause / network graphs while the game runs. **Why it's hard:** the launcher can't see inside the game process — needs a small companion mod (per loader/version) feeding telemetry back over a local socket. This is the same per-loader/per-version maintenance treadmill flagged for the in-game overlay (§4.10); reuse one telemetry mod rather than building bespoke per version.

### Sequencing note
Most of the 🔴 items (per-world profiles, live dashboard, shareable links) depend on infrastructure that doesn't exist until the core loop + mod management are solid. Build the 🟢/🟡 wins first; treat 🔴 as their own researched milestones so they don't stall the launcher.

---

## 11. Built So Far (implementation status — 2026-06)

The launcher is a working JavaFX app (`gradlew run`). Implemented and verified:

**Core loop:** create instance → download (client/libs/natives/assets, content-addressed, SHA-1 verified, parallel) → auto-fetch the right Java (Adoptium) → launch (offline mode). Vanilla + Fabric boot.

**NeoForge / Forge (✅ install engine shipped; boot needs real-world testing):** `core/ForgeVersions.java` resolves the newest NeoForge/Forge build for an MC version off each project's Maven metadata (verified live: 1.21.1 → neoforge 21.1.234 / forge 1.21.1-52.1.14; correctly returns no NeoForge for 1.20.1). `core/ForgeSupport.java` drives the modern (MC 1.13+) installer format both projects share: downloads the installer jar, extracts `install_profile.json` + `version.json`, copies bundled `maven/` artifacts, downloads all profile + launch libraries, builds the `data` token map (`[maven]`/`'literal'`/`/extracted` + built-ins `{MINECRAFT_JAR}` `{SIDE}` `{LIBRARY_DIR}` `{ROOT}` `{INSTALLER}`), then runs each **client-side** processor as a subprocess (binpatcher / jarsplitter / ART renamer / installertools — server-only processors skipped) to patch the vanilla jar into the modded client. Cached behind a per-loader+version marker so it runs once. Parses `version.json` into a `Profile` (mainClass, libraries, jvm/game args) fed into `GameLauncher`, which now adds the Forge libs to the classpath, sets the module-path tokens (`${library_directory}`, `${classpath_separator}`), and appends Forge's jvm/game args + main class (`cpw.mods.bootstraplauncher.BootstrapLauncher`). Wired into `onPlay` (runs after Java is fetched, before launch) and the create dialog (NeoForge + Forge selectable). **Headless-verified end-to-end for NeoForge 1.21.1: all 10 processors ran, 47/47 libraries materialized on disk, correct main class — in 26s.** The only unverified step is the actual in-game boot (needs a display + full assets); first Play also takes longer (installer + processors run then).

**Accounts/auth:** full MS device-code chain (MSA→XBL→XSTS→Minecraft), AES-256-GCM token store, multi-account avatar bar with active-account switching. Online play gated on Microsoft app approval (form submitted; `login_with_xbox` 403 until approved).

**Content:** Modrinth browse (popular list, debounced search-as-you-type, sort + type filters, pagination, mod icons w/ WebP decode, gold download counts, about page) + install with recursive deps. CurseForge as a second source via `ContentSource` (needs user's own API key — user's key currently 403s on CF's side). One-click Performance Pack (Sodium/Lithium/etc.).

**Instances:** uniform cards (click to open), tabbed detail (Mods / Resource Packs / Shaders / Data Packs / Worlds / Settings), per-type "+ Add" → Browse preselected, enable/disable + remove per file, install routed by type. Per-instance settings: rename, RAM slider, resolution, fullscreen, extra JVM args, profile-block icon. Worlds tab: list + quick-launch + open folder + delete. OptiFine = manual 2-jar flow.

**Modpack (.mrpack) import (✅ shipped):** `core/ModpackInstaller.java` parses `modrinth.index.json`, downloads each `files[]` entry to its declared path (SHA-1 verified) honoring Modrinth's download-URL whitelist (modrinth.com / github.com / githubusercontent.com / gitlab.com, subdomains incl.), then extracts `overrides/` + `client-overrides/` into the instance (`server-overrides/` skipped; zip-slip / path-traversal rejected). Two entry points in Browse: (1) clicking **Install** on a Modpacks search hit resolves the newest `.mrpack` for the selected version, and (2) a **"📦 Import .mrpack file"** button for a local pack. Either way it creates a *new* instance from the pack's own declared MC version + loader. Fabric/Vanilla packs are fully bootable; Forge/NeoForge packs import their files but warn they won't launch until those loaders are wired (#4). CurseForge packs aren't the .mrpack format → rejected with guidance. Headless-verified (index parse, loader detection, overrides extraction, server-overrides skip, zip-slip guard).

**Mod updates & tracking:** every install (Browse + Performance Pack) is recorded to `<instanceDir>/.kano-mods.json` (`ModTracker`: projectId, source, type, folder, filename, name). The Mods tab runs a background check on open and shows a **gold "⬆ Update All (N)" button only when N>1 builds are actually out of date** (hidden + unmanaged otherwise). Clicking it re-resolves each tracked mod against its source (Modrinth/CurseForge) with the instance's real loader, deletes the old jar, installs the newer build, and re-records. Badge count and the update action share `sourceForTracked()` + `modLoaderTag()` so they agree. *Caveat: only mods installed via Browse from now on are tracked — pre-existing mods must be reinstalled once to appear.*

**Customization:** changeable launcher name; **full accent theming** (every accent color — solid, glow, border, tint — flows from looked-up CSS vars set live by `applyTheme()`); 5 presets + **custom color picker**; **background image upload + size + visibility sliders**; Ctrl+K command palette; themed dialogs; stats bar (worlds/playtime/launches). Maximize keeps the taskbar visible.

**Playing Now:** a sidebar tab listing every running game (grouped by player, then version) with live uptime and a **Kill** button; instances show a green "● Running" indicator on their card + detail header. The sidebar entry shows a live count.

**Performance:** launch no longer re-hashes already-cached files every start — libraries/natives/assets/Forge libs are immutable, so they're verified once on download then trusted on existence (`Downloader.downloadIfAbsent`). Big startup speedup, especially for Forge's large classpath.

**Performance Pack (researched + live-verified, per loader):** a multi-agent research pass (45 agents) enumerated every worthwhile perf mod by category, then **adversarially verified each against the live Modrinth API** (exists? declared loaders? maintained? conflicts?); 26 survived. Final conflict-free, loader-correct stacks (every slug confirmed to resolve a 1.21.1 build except where noted), button is gold/yellow:
- **Fabric/Quilt (18):** sodium + sodium-extra + reese's, lithium, scalablelux, c2me-fabric, ferrite-core, modernfix, threadtweak, krypton, moreculling, entityculling, immediatelyfast, dynamic-fps, debugify, fastquit, fast-ip-ping, vmp-fabric.
- **NeoForge (12):** embeddium (NOT Sodium → so the Sodium-only companions are excluded), lithium, scalablelux, c2me-neoforge, ferrite-core, modernfix, moreculling, entityculling, immediatelyfast, dynamic-fps, enhanced-block-entities-neoforged, fast-ip-ping.
- **Forge (10):** embeddium, ferrite-core, modernfix, cull-leaves, entityculling, immediatelyfast, dynamic-fps, c2mef, debugify, fast-ip-ping.
- Conflicts resolved: Sodium↔Embeddium (Fabric=Sodium, Forge/NeoForge=Embeddium); one leaf-culler only (MoreCulling on Fabric/NeoForge, Cull Leaves on Forge); ScalableLux↔Starlight; FerriteCore↔Hydrogen; ThreadTweak↔SmoothBoot; C2ME↔Noisium (kept C2ME per loader).
- **Key finding:** most Forge-family perf mods (Embeddium, Lithium, ScalableLux, MoreCulling, C2ME) **stopped shipping for *Forge* after ~1.20.1** and moved to NeoForge — a Forge **1.21.x** instance can only install ~4 of them, which is why Forge 1.21 tops out ~110fps while NeoForge 1.21 hits 800. The pack surfaces this and recommends NeoForge for 1.21+. Mods are version-filtered, so any slug without a build for the instance's version is skipped gracefully.

**Other:** self-update banner (GitHub release check, notify-only). Instance custom image (upload → `icon.png`, shown on card + detail). World **Create Backup** (timestamped zip, keeps newest 3 per world) + Restore. OptiFine setup now auto-installs OptiFabric from Modrinth (best-effort) and gives clickable download links (OptiFine itself is gated on optifine.net). Open Folder / Clone / Export surfaced in the instance detail header. **Expanded settings:** global defaults (default RAM, default loader, minimize-on-play, confirm-before-delete) + appearance (10 theme presets, custom color, animations toggle, background image + sliders). Visual polish: 170ms view fade-in (toggleable), card hover-lift, filled-button hover glow.

**Drafted, NOT yet integrated** (agent-produced, pending): scheduled tasks, live dashboard, packaging (.msi/.deb via jpackage — needs WiX + jmods + icons).

**Still wanted:** per-letter colored/bold launcher name (hex codes); deeper per-component customization options.

---

*End of KanoLauncher build plan v2.*
