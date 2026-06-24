# KanoLauncher — User Guide

A clean, fast Minecraft (Java Edition) launcher for Windows. Create as many game
setups as you want, add mods and modpacks with a couple of clicks, and play —
all from one window. This guide covers everything: getting started, every
feature, and all the ways you can customize it.

---

## 1. Getting started (for first-timers / friends)

1. **Download** the `KanoLauncher-<version>-win.zip` you were sent.
2. **Unzip it** anywhere (Desktop, Documents — wherever). Keep the whole folder
   together.
3. Open the folder and run **`KanoLauncher.exe`**.
   - **Windows may show "Windows protected your PC."** That's just because the
     app isn't code-signed (signing costs money; this is a hobby app). Click
     **More info → Run anyway**. It's safe.
   - You do **not** need Java installed — a Java runtime is bundled inside.
4. The launcher opens. Click **Sign in** (top-left of the sidebar) to log into
   your Microsoft account, or skip it and play offline/singleplayer right away.

**You need:** Windows, an internet connection on first launch (it downloads the
game files automatically), and — for online play — your **own Microsoft account
that owns Minecraft: Java Edition**.

> **Online login note:** signing in with Microsoft is pending Microsoft's
> approval of the app. Until that lands, **offline + singleplayer + LAN work
> fully**; online sign-in may say "not approved yet." Once approved, sign-in
> starts working automatically — no new download needed.

---

## 2. Creating and playing a game

### Make an instance
An "instance" is one self-contained game setup (its own version, mods, worlds,
settings). Go to **Instances → Create Instance**:

- **Minecraft version** — pick any version. Tick **snapshots** to see those too;
  the full official list is pulled live.
- **Loader** — how mods load:
  - **Vanilla** — no mods, pure Minecraft.
  - **Fabric** — lightweight, most performance/QoL mods.
  - **NeoForge** — the modern Forge-family standard for 1.21+.
  - **Forge** — legacy. *On 1.20.2+ the launcher offers to make it NeoForge
    instead, because Forge has almost no performance mods there.*
- **Group** (optional) — tag instances ("Testing", "SMP") to organize them.

### Play
- **Home → Continue** launches your most recently played instance.
- Each instance card and detail page has a **Play** button.
- **Playing more than one account?** The Play button gets a **▾ "Play as…"**
  menu so two accounts can launch side by side (great for joining a server
  together).
- **Playing Now** (sidebar) lists every running game with uptime and a **Kill**
  button.

First launch of a new version is slower — it downloads the client, libraries,
assets, and the correct Java automatically. After that it's quick.

---

## 3. Mods, modpacks & content

### Browse and install (Modrinth — no setup)
Open **Browse Mods**. Search mods, modpacks, resource packs, shaders, and data
packs from **Modrinth** (no account or key needed). Filter by type, sort, and
"Load more" to page through. Click **Install** — required dependencies come
along automatically. Click a card to read its full description first.

### One-click Performance Pack
On a Fabric/NeoForge/Forge instance, hit **Performance Pack** to install a
curated, conflict-free set of optimization mods matched to that loader and
version (Sodium/Embeddium, Lithium, etc.). Big FPS boost with no manual picking.

### Modpacks
- **Import modpack** accepts both **Modrinth `.mrpack`** and **CurseForge `.zip`**
  files. It creates a new instance with the pack's exact version + loader,
  downloads the mods, and applies the pack's configs.
- **Export** any instance as a self-contained `.mrpack` to share or back up.
- **Clone** an instance (optionally with its worlds).

### CurseForge — no key needed
- **Drag any CurseForge `.jar`** you downloaded onto an instance's **Mods** tab.
- **Import a CurseForge modpack `.zip`** with **Import modpack** — it fetches the
  mods for you, no API key required. (A few mods whose authors blocked off-site
  downloads will be listed with links to grab manually and drag in.)
- *Optional:* if you want to **browse** CurseForge inside the launcher, you can
  add your own free API key in Settings (see §7). Importing and drag-drop never
  need one.

### Managing installed mods
On an instance's **Mods** tab you can enable/disable, remove, drag in `.jar`s,
and **Update All** (or update a single mod) when newer builds exist. Two quick
switches: **"Disable all mods"** (play vanilla) and **"Optimization mods only."**

---

## 4. Worlds & servers

Each instance's detail page has tabs:

- **Worlds** — list saves with size + last played, **quick-launch** straight into
  a world, open its folder, delete, and **Backup / Restore** (timestamped zips,
  keeps the newest few).
- **Servers** — save favourite servers, see **live status** (players online,
  ping, version), and **▶ quick-join** straight into one.
- Plus **Resource Packs**, **Shaders**, **Data Packs** tabs — each with its own
  "+ Add" that opens Browse pre-filtered to that type and your version.

---

## 5. Accounts & online play

- Sign in with **Microsoft** (top of the sidebar). Add **multiple accounts** —
  their avatars show in the sidebar; click to switch the active one.
- The active account is who launches by default; the **Play as…** menu lets you
  launch a specific account.
- **Online play (premium servers, skins) needs the app's Microsoft approval**
  (pending). Offline/singleplayer/LAN don't.

---

## 6. Performance notes

- The launcher renders its UI in **software mode** on purpose — it's a UI, it
  doesn't need your GPU, and this avoids a Windows graphics-driver quirk. It does
  **not** affect in-game FPS at all.
- In-game speed comes from the **Performance Pack** and your **RAM/JVM settings**
  (below). Default heap is 4 GB; raise it for heavy modpacks.

---

## 7. Customization — everything you can change

Open **Settings** (sidebar). Changes apply live and are saved automatically.

### Appearance
- **Color scheme** — 10 built-in themes: **Crimson, Ocean, Forest, Amethyst,
  Gold, Sunset, Rose, Teal, Slate, Cyber** — *plus* a **Custom** color picker to
  set any accent color you like. The whole UI re-tints instantly.
- **Launcher name & colours** — rename the launcher, and split the name into
  **multiple parts, each with its own color** (shown in the sidebar brand). Add
  or remove parts freely.
- **Launcher background** — replace the corner king watermark with **your own
  image** (PNG/JPG/GIF), or reset to default. Two sliders control the
  background's **size** and **visibility (opacity)**.
- **UI animations** — toggle the fade-between-views animation on/off.

### Global defaults (applied to new instances)
- **Default RAM** — slider, 1–16 GB, used for new instances.
- **Default loader** — Vanilla / Fabric / NeoForge / Forge.
- **Minimize launcher when a game launches** — on/off.
- **Confirm before deleting an instance** — on/off.
- **Auto-create as NeoForge instead of Forge on 1.20.2+** — on/off.
- **Open the launcher maximized on startup** — on/off (off = normal window).

### Per-instance settings (each instance's **Settings** tab)
- **Rename** and **group**.
- **RAM** slider for that instance.
- **Resolution** (width × height) and **fullscreen** toggle.
- **Extra JVM arguments** for power users.
- **Instance icon** — pick a colored block, or upload your own image.
- **Convert to NeoForge** button on performance-starved Forge instances.

### CurseForge (optional in-app browsing)
No key is needed to import packs or drag in mods. If you *want* to browse
CurseForge as a second source inside the launcher, add your own free key:
1. Go to **console.curseforge.com** and sign in (free — Overwolf/Google/Discord).
2. Open **API Keys** in the left menu.
3. Copy your key (a long line starting with `$2a$`).
4. Paste it into **Settings → CurseForge** and **Save Key**.
5. On **Browse**, choose **CurseForge** in the Source dropdown.

Keep your key private — it's tied to your account and **must not be shared**
(CurseForge revokes shared keys). If browsing ever says "forbidden (403)," your
key was deactivated — regenerate it on that same page and paste the new one in;
the launcher will prompt you when this happens.

---

## 8. Keyboard shortcuts

| Shortcut | Action |
|---|---|
| **Ctrl + K** | Command palette — jump to any view or action by typing |
| **Ctrl + Shift + R** | Re-center the window (rescue if it ends up off-screen) |
| **Ctrl + Q / Ctrl + W** | Close the launcher |

---

## 9. Troubleshooting

- **"Windows protected your PC" on launch** → More info → Run anyway (unsigned
  app, expected).
- **Online sign-in fails / "not approved"** → the Microsoft approval is still
  pending; use offline/singleplayer until it lands.
- **CurseForge browse says "forbidden (403)"** → your CurseForge key was
  deactivated; regenerate it at console.curseforge.com and re-paste it in
  Settings. (Import/drag-drop are unaffected.)
- **A modpack imported but a few mods are missing** → those authors blocked
  off-site downloads; the launcher lists them with links — download those `.jar`s
  from CurseForge and drag them onto the Mods tab.
- **First launch is slow** → it's downloading the game files + Java; subsequent
  launches are fast.

---

## 10. Where things live

Your data lives in `%APPDATA%\KanoLauncher\`:
- `config.json` — your settings
- `instances\` — each game setup (mods, worlds, configs)
- `cache\` — shared, de-duplicated game files (downloaded once, reused)
- `runtimes\` — bundled Java versions, by major version

The exact path is shown at the bottom of the **Settings** page.

---

*Made by ChaosCraft. Have fun.*
