# Multi-Game Hub — Plan 1A: Detect & Launch (core) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make KanoLauncher open to a game library that detects the user's installed Steam games + Minecraft, lets them add standalone games, launches any of them, and routes the Minecraft tile into the existing Minecraft UI — without changing any current Minecraft behavior.

**Architecture:** A new additive core layer under `com.kano.launcher.core.games`: a `Game` record + `GameKind` enum, a `GamesStore` (persists user-added games and last-played to `games.json`), three `GameSource` scanners (Steam, Standalone, Minecraft), a `GameLibrary` that aggregates + merges, and a `GameRunner` that launches by kind. A new `GameLibraryView` UI class becomes the launcher's startup view; the Minecraft tile calls the existing `showHome()`. Existing Minecraft code is untouched.

**Tech Stack:** Java 21, JavaFX 21, Gson 2.11 (already a dependency), JUnit 5 (added in Task 1), Gradle.

## Global Constraints

- **Java 21** toolchain; JavaFX 21.0.4 (`javafx.controls`, `javafx.fxml`, `javafx.swing`). Copy verbatim — do not change versions.
- **Data dir:** all persistence under `resolveDataDir()` = `%APPDATA%\KanoLauncher` (Windows) / `~/.kanolauncher`. New file: `games.json` in that dir.
- **Backward-compatible:** do NOT modify, move, or read existing Minecraft data (`instances/`, `config.json`, `accounts.enc`). The Minecraft experience must behave exactly as before, reached by opening the Minecraft tile.
- **No new runtime network deps in 1A** (Steam Workshop/Thunderstore/Nexus discovery is Plan 1C). Cover art in 1A: Steam local cache + Steam CDN by appid only.
- **Reuse, don't bloat:** hub UI lives in new classes (`GameLibraryView`, etc.), NOT in `MainApp.java`.
- **Package:** new code under `com.kano.launcher.core.games` (logic) and `com.kano.launcher` (the view), matching existing layout.
- **Commit style:** end commit messages with `Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>`. Commit after each task; push is the user's call (they commit→push by default, but the executor commits per-task and lets the user push).

---

### Task 1: JUnit 5 harness + `GameKind` enum + `Game` model

**Files:**
- Modify: `build.gradle.kts` (add JUnit 5 test deps + `useJUnitPlatform()`)
- Create: `src/main/java/com/kano/launcher/core/games/GameKind.java`
- Create: `src/main/java/com/kano/launcher/core/games/Game.java`
- Test: `src/test/java/com/kano/launcher/core/games/GameTest.java`

**Interfaces:**
- Produces: `enum GameKind { STEAM, EPIC, GOG, STANDALONE, MINECRAFT }`
- Produces: `record Game(String id, String name, GameKind kind, String installDir, String launchTarget, String coverArtPath, long lastPlayedEpoch, boolean favorite, boolean startupGame, java.util.List<String> categories, String accentHex, boolean moddable, String modSource, String launchOptions, String workingDirOverride)` with: `String key()` returning `kind + ":" + id`; withers `withLastPlayed(long)`, `withCoverArt(String)`. A static `Game.basic(String id, String name, GameKind kind, String installDir, String launchTarget)` factory that fills the rest with sensible defaults (`categories` = empty list, booleans false, `accentHex`/`modSource`/`launchOptions`/`workingDirOverride`/`coverArtPath` = null, `lastPlayedEpoch` = 0). *Later plans (1B/1C) populate favorite/categories/accentHex/modSource — defined now so the record never reshapes.*

- [ ] **Step 1: Add JUnit 5 to `build.gradle.kts`**

In the `dependencies { }` block (after the webp line), add:

```kotlin
    testImplementation(platform("org.junit:junit-bom:5.10.2"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
```

After the `application { }` block, add:

```kotlin
tasks.named<Test>("test") {
    useJUnitPlatform()
}
```

- [ ] **Step 2: Write the failing test**

`src/test/java/com/kano/launcher/core/games/GameTest.java`:

```java
package com.kano.launcher.core.games;

import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class GameTest {
    @Test
    void basicFillsDefaultsAndKey() {
        Game g = Game.basic("440", "Team Fortress 2", GameKind.STEAM, "C:/games/tf2", "steam://rungameid/440");
        assertEquals("STEAM:440", g.key());
        assertEquals("Team Fortress 2", g.name());
        assertFalse(g.favorite());
        assertEquals(0L, g.lastPlayedEpoch());
        assertNotNull(g.categories());
        assertTrue(g.categories().isEmpty());
    }

    @Test
    void withersReturnUpdatedCopies() {
        Game g = Game.basic("x", "X", GameKind.STANDALONE, "C:/x", "C:/x/x.exe");
        assertEquals(123L, g.withLastPlayed(123L).lastPlayedEpoch());
        assertEquals("a.png", g.withCoverArt("a.png").coverArtPath());
        assertEquals(0L, g.lastPlayedEpoch(), "original unchanged (record immutability)");
    }
}
```

- [ ] **Step 3: Run the test to verify it fails**

Run: `gradlew test --tests "com.kano.launcher.core.games.GameTest" --no-daemon`
Expected: FAIL — `GameKind`/`Game` do not exist (compilation error).

- [ ] **Step 4: Create `GameKind.java`**

```java
package com.kano.launcher.core.games;

/** Where a game came from. Steam/Standalone/Minecraft are implemented in Plan 1A; Epic/GOG are Plan 1B+. */
public enum GameKind { STEAM, EPIC, GOG, STANDALONE, MINECRAFT }
```

- [ ] **Step 5: Create `Game.java`**

```java
package com.kano.launcher.core.games;

import java.util.ArrayList;
import java.util.List;

/**
 * A game in the unified library. One immutable record; later plans populate the richer fields
 * (favorite, categories, accentHex, modSource). Persistence of user-edited fields lives in GamesStore.
 */
public record Game(
        String id, String name, GameKind kind, String installDir, String launchTarget,
        String coverArtPath, long lastPlayedEpoch, boolean favorite, boolean startupGame,
        List<String> categories, String accentHex, boolean moddable, String modSource,
        String launchOptions, String workingDirOverride) {

    public Game {
        categories = categories == null ? List.of() : List.copyOf(categories);
    }

    /** Stable identity across rescans: e.g. "STEAM:440". */
    public String key() { return kind + ":" + id; }

    public static Game basic(String id, String name, GameKind kind, String installDir, String launchTarget) {
        return new Game(id, name, kind, installDir, launchTarget, null, 0L, false, false,
                new ArrayList<>(), null, false, null, null, null);
    }

    public Game withLastPlayed(long epoch) {
        return new Game(id, name, kind, installDir, launchTarget, coverArtPath, epoch, favorite, startupGame,
                categories, accentHex, moddable, modSource, launchOptions, workingDirOverride);
    }

    public Game withCoverArt(String path) {
        return new Game(id, name, kind, installDir, launchTarget, path, lastPlayedEpoch, favorite, startupGame,
                categories, accentHex, moddable, modSource, launchOptions, workingDirOverride);
    }
}
```

- [ ] **Step 6: Run the test to verify it passes**

Run: `gradlew test --tests "com.kano.launcher.core.games.GameTest" --no-daemon`
Expected: PASS (2 tests).

- [ ] **Step 7: Commit**

```bash
git add build.gradle.kts src/main/java/com/kano/launcher/core/games/ src/test/java/com/kano/launcher/core/games/GameTest.java
git commit -m "feat(hub): add JUnit5, GameKind, Game model

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

### Task 2: `GamesStore` — persist user data to `games.json`

**Files:**
- Create: `src/main/java/com/kano/launcher/core/games/GamesStore.java`
- Test: `src/test/java/com/kano/launcher/core/games/GamesStoreTest.java`

**Interfaces:**
- Consumes: `Game`, `GameKind` (Task 1).
- Produces: `class GamesStore` with constructor `GamesStore(java.nio.file.Path dataDir)` (reads `dataDir/games.json` if present); methods `List<Game> manualGames()`, `void addManual(Game g)` (persists), `void removeManual(String key)` (persists), `long lastPlayedFor(String key)` (0 if none), `void recordPlayed(String key, long epoch)` (persists). JSON shape: `{"manualGames":[<Game>...],"lastPlayed":{"<key>":<epoch>}}`. Writes atomically (temp file → move), mirroring `Config.save()`.

- [ ] **Step 1: Write the failing test**

`src/test/java/com/kano/launcher/core/games/GamesStoreTest.java`:

```java
package com.kano.launcher.core.games;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;

class GamesStoreTest {
    @Test
    void manualGamesRoundTripAcrossInstances(@TempDir Path dir) {
        GamesStore a = new GamesStore(dir);
        assertTrue(a.manualGames().isEmpty());
        a.addManual(Game.basic("ds3", "Dark Souls III", GameKind.STANDALONE, "C:/ds3", "C:/ds3/ds3.exe"));

        GamesStore b = new GamesStore(dir); // re-read from disk
        assertEquals(1, b.manualGames().size());
        assertEquals("Dark Souls III", b.manualGames().get(0).name());
    }

    @Test
    void lastPlayedPersists(@TempDir Path dir) {
        GamesStore a = new GamesStore(dir);
        a.recordPlayed("STEAM:440", 1700000000L);
        assertEquals(1700000000L, new GamesStore(dir).lastPlayedFor("STEAM:440"));
        assertEquals(0L, new GamesStore(dir).lastPlayedFor("STEAM:999"));
    }

    @Test
    void removeManualPersists(@TempDir Path dir) {
        GamesStore a = new GamesStore(dir);
        a.addManual(Game.basic("x", "X", GameKind.STANDALONE, "C:/x", "C:/x/x.exe"));
        a.removeManual("STANDALONE:x");
        assertTrue(new GamesStore(dir).manualGames().isEmpty());
    }
}
```

- [ ] **Step 2: Run to verify it fails**

Run: `gradlew test --tests "com.kano.launcher.core.games.GamesStoreTest" --no-daemon`
Expected: FAIL — `GamesStore` does not exist.

- [ ] **Step 3: Create `GamesStore.java`**

```java
package com.kano.launcher.core.games;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.reflect.TypeToken;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Persists the user-owned slice of the library (manually-added games + last-played) to games.json. */
public final class GamesStore {

    private final Path file;
    private final List<Game> manualGames = new ArrayList<>();
    private final Map<String, Long> lastPlayed = new LinkedHashMap<>();
    private static final Gson GSON = new Gson();

    public GamesStore(Path dataDir) {
        this.file = dataDir.resolve("games.json");
        load();
    }

    public synchronized List<Game> manualGames() { return List.copyOf(manualGames); }

    public synchronized void addManual(Game g) {
        manualGames.removeIf(m -> m.key().equals(g.key()));
        manualGames.add(g);
        save();
    }

    public synchronized void removeManual(String key) {
        manualGames.removeIf(m -> m.key().equals(key));
        save();
    }

    public synchronized long lastPlayedFor(String key) {
        Long v = lastPlayed.get(key);
        return v == null ? 0L : v;
    }

    public synchronized void recordPlayed(String key, long epoch) {
        lastPlayed.put(key, epoch);
        save();
    }

    private void load() {
        try {
            if (!Files.exists(file)) return;
            JsonObject o = GSON.fromJson(Files.readString(file, StandardCharsets.UTF_8), JsonObject.class);
            if (o == null) return;
            if (o.has("manualGames")) {
                List<Game> list = GSON.fromJson(o.get("manualGames"),
                        new TypeToken<ArrayList<Game>>() {}.getType());
                if (list != null) { manualGames.clear(); manualGames.addAll(list); }
            }
            if (o.has("lastPlayed")) {
                Map<String, Double> raw = GSON.fromJson(o.get("lastPlayed"),
                        new TypeToken<LinkedHashMap<String, Double>>() {}.getType());
                if (raw != null) raw.forEach((k, v) -> lastPlayed.put(k, v.longValue()));
            }
        } catch (Exception ignored) {
            // Corrupt file: start empty rather than crash the launcher.
        }
    }

    private void save() {
        try {
            JsonObject o = new JsonObject();
            o.add("manualGames", GSON.toJsonTree(manualGames));
            o.add("lastPlayed", GSON.toJsonTree(lastPlayed));
            Files.createDirectories(file.getParent());
            Path tmp = file.resolveSibling("games.json.tmp");
            Files.writeString(tmp, GSON.toJson(o), StandardCharsets.UTF_8);
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
        } catch (Exception ignored) {
        }
    }
}
```

- [ ] **Step 4: Run to verify it passes**

Run: `gradlew test --tests "com.kano.launcher.core.games.GamesStoreTest" --no-daemon`
Expected: PASS (3 tests).

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/kano/launcher/core/games/GamesStore.java src/test/java/com/kano/launcher/core/games/GamesStoreTest.java
git commit -m "feat(hub): GamesStore persists manual games + last-played to games.json

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

### Task 3: `GameSource` interface + `MinecraftSource`

**Files:**
- Create: `src/main/java/com/kano/launcher/core/games/GameSource.java`
- Create: `src/main/java/com/kano/launcher/core/games/MinecraftSource.java`
- Test: `src/test/java/com/kano/launcher/core/games/MinecraftSourceTest.java`

**Interfaces:**
- Consumes: `Game`, `GameKind`.
- Produces: `interface GameSource { GameKind kind(); List<Game> scan(); }` (scan never throws — returns empty on failure).
- Produces: `class MinecraftSource implements GameSource` — `scan()` always returns exactly one `Game` (`id="minecraft"`, `kind=MINECRAFT`, `name="Minecraft"`, `moddable=true`, `installDir`/`launchTarget` = null because it's launched natively, not by a path). The Minecraft tile's launch is handled specially by `GameRunner` (Task 7) and the view (Task 8), not via `launchTarget`.

- [ ] **Step 1: Write the failing test**

`src/test/java/com/kano/launcher/core/games/MinecraftSourceTest.java`:

```java
package com.kano.launcher.core.games;

import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class MinecraftSourceTest {
    @Test
    void alwaysReturnsExactlyOneModdableMinecraft() {
        List<Game> games = new MinecraftSource().scan();
        assertEquals(1, games.size());
        Game mc = games.get(0);
        assertEquals(GameKind.MINECRAFT, mc.kind());
        assertEquals("minecraft", mc.id());
        assertEquals("Minecraft", mc.name());
        assertTrue(mc.moddable());
        assertEquals("MINECRAFT:minecraft", mc.key());
    }
}
```

- [ ] **Step 2: Run to verify it fails**

Run: `gradlew test --tests "com.kano.launcher.core.games.MinecraftSourceTest" --no-daemon`
Expected: FAIL — types do not exist.

- [ ] **Step 3: Create `GameSource.java`**

```java
package com.kano.launcher.core.games;

import java.util.List;

/** A place games can be discovered. Implementations must never throw from scan() — return empty instead. */
public interface GameSource {
    GameKind kind();
    List<Game> scan();
}
```

- [ ] **Step 4: Create `MinecraftSource.java`**

```java
package com.kano.launcher.core.games;

import java.util.List;

/** Minecraft is always present as a first-class game; it's launched natively (no install path needed). */
public final class MinecraftSource implements GameSource {
    @Override public GameKind kind() { return GameKind.MINECRAFT; }

    @Override public List<Game> scan() {
        Game mc = new Game("minecraft", "Minecraft", GameKind.MINECRAFT,
                null, null, null, 0L, false, false, List.of(), "#3FA34D", true, null, null, null);
        return List.of(mc);
    }
}
```

- [ ] **Step 5: Run to verify it passes**

Run: `gradlew test --tests "com.kano.launcher.core.games.MinecraftSourceTest" --no-daemon`
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/kano/launcher/core/games/GameSource.java src/main/java/com/kano/launcher/core/games/MinecraftSource.java src/test/java/com/kano/launcher/core/games/MinecraftSourceTest.java
git commit -m "feat(hub): GameSource interface + always-present MinecraftSource

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

### Task 4: `SteamSource` — detect installed Steam games

**Files:**
- Create: `src/main/java/com/kano/launcher/core/games/SteamSource.java`
- Test: `src/test/java/com/kano/launcher/core/games/SteamSourceTest.java`

**Interfaces:**
- Consumes: `Game`, `GameKind`, `GameSource`.
- Produces: `class SteamSource implements GameSource`. Pure, unit-testable statics:
  - `record AppManifest(String appId, String name, String installDir)`
  - `static AppManifest parseAppManifest(String acfText)` — returns null if no appid.
  - `static List<String> parseLibraryPaths(String libraryFoldersVdfText)` — all `"path"` values, with `\\` unescaped to `\`.
  - `static boolean isRealGame(String appId, String name)` — false for known Steam tooling (a small denylist of redistributable/runtime appids).
- `scan()` locates Steam (registry `HKCU\Software\Valve\Steam SteamPath`, then default install dirs), reads `libraryfolders.vdf`, walks each library's `appmanifest_*.acf`, and builds `Game`s (`launchTarget = "steam://rungameid/<appid>"`, `installDir = <lib>/steamapps/common/<installdir>`, `coverArtPath` = local librarycache image if present else null). Skips non-games and entries whose install dir is missing. Never throws.

- [ ] **Step 1: Write the failing test**

`src/test/java/com/kano/launcher/core/games/SteamSourceTest.java`:

```java
package com.kano.launcher.core.games;

import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class SteamSourceTest {
    @Test
    void parsesAppManifest() {
        String acf = """
            "AppState"
            {
              "appid"  "440"
              "name"  "Team Fortress 2"
              "StateFlags"  "4"
              "installdir"  "Team Fortress 2"
              "LastUpdated"  "1700000000"
            }
            """;
        SteamSource.AppManifest m = SteamSource.parseAppManifest(acf);
        assertNotNull(m);
        assertEquals("440", m.appId());
        assertEquals("Team Fortress 2", m.name());
        assertEquals("Team Fortress 2", m.installDir());
    }

    @Test
    void parseAppManifestReturnsNullWhenNoAppId() {
        assertNull(SteamSource.parseAppManifest("\"AppState\" { \"name\" \"x\" }"));
    }

    @Test
    void parsesLibraryPathsAndUnescapes() {
        String vdf = """
            "libraryfolders"
            {
              "0"
              {
                "path"  "C:\\\\Program Files (x86)\\\\Steam"
              }
              "1"
              {
                "path"  "D:\\\\SteamLibrary"
              }
            }
            """;
        List<String> paths = SteamSource.parseLibraryPaths(vdf);
        assertEquals(2, paths.size());
        assertEquals("C:\\Program Files (x86)\\Steam", paths.get(0));
        assertEquals("D:\\SteamLibrary", paths.get(1));
    }

    @Test
    void filtersKnownSteamTooling() {
        assertFalse(SteamSource.isRealGame("228980", "Steamworks Common Redistributables"));
        assertTrue(SteamSource.isRealGame("440", "Team Fortress 2"));
    }
}
```

> Note: in the Java text blocks above, `\\\\` is the source escaping for a literal `\\` in the file content (what a real `.vdf` contains), and `parseLibraryPaths` collapses that to a single `\`.

- [ ] **Step 2: Run to verify it fails**

Run: `gradlew test --tests "com.kano.launcher.core.games.SteamSourceTest" --no-daemon`
Expected: FAIL — `SteamSource` does not exist.

- [ ] **Step 3: Create `SteamSource.java`**

```java
package com.kano.launcher.core.games;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Detects installed Steam games by reading Steam's libraryfolders.vdf + appmanifest_*.acf files. */
public final class SteamSource implements GameSource {

    public record AppManifest(String appId, String name, String installDir) {}

    // Quoted "key" "value" pairs, the Valve KeyValues shape used by .acf/.vdf.
    private static final Pattern KV = Pattern.compile("\"([^\"]+)\"\\s+\"([^\"]*)\"");

    // Steam tooling that shows up as appmanifests but isn't a game.
    private static final Set<String> NON_GAMES = Set.of(
            "228980", // Steamworks Common Redistributables
            "1070560", "1391110", "1493710" // Steam Linux Runtime variants
    );

    @Override public GameKind kind() { return GameKind.STEAM; }

    public static AppManifest parseAppManifest(String acfText) {
        String appId = null, name = null, installDir = null;
        Matcher m = KV.matcher(acfText);
        while (m.find()) {
            String k = m.group(1), v = m.group(2);
            switch (k) {
                case "appid" -> { if (appId == null) appId = v; }
                case "name" -> { if (name == null) name = v; }
                case "installdir" -> { if (installDir == null) installDir = v; }
                default -> { }
            }
        }
        if (appId == null) return null;
        return new AppManifest(appId, name == null ? appId : name, installDir == null ? "" : installDir);
    }

    public static List<String> parseLibraryPaths(String vdfText) {
        List<String> paths = new ArrayList<>();
        Matcher m = KV.matcher(vdfText);
        while (m.find()) {
            if (m.group(1).equals("path")) paths.add(m.group(2).replace("\\\\", "\\"));
        }
        return paths;
    }

    public static boolean isRealGame(String appId, String name) {
        if (NON_GAMES.contains(appId)) return false;
        return name != null && !name.isBlank();
    }

    @Override public List<Game> scan() {
        List<Game> out = new ArrayList<>();
        try {
            Path steamRoot = findSteamRoot();
            if (steamRoot == null) return out;
            List<String> libs = new ArrayList<>();
            libs.add(steamRoot.toString());
            Path lf = steamRoot.resolve("steamapps").resolve("libraryfolders.vdf");
            if (Files.exists(lf)) libs.addAll(parseLibraryPaths(Files.readString(lf, StandardCharsets.UTF_8)));

            Set<String> seen = new java.util.HashSet<>();
            for (String lib : libs) {
                Path apps = Path.of(lib).resolve("steamapps");
                if (!Files.isDirectory(apps)) continue;
                try (var stream = Files.newDirectoryStream(apps, "appmanifest_*.acf")) {
                    for (Path acf : stream) {
                        AppManifest mf = parseAppManifest(Files.readString(acf, StandardCharsets.UTF_8));
                        if (mf == null || !isRealGame(mf.appId(), mf.name()) || !seen.add(mf.appId())) continue;
                        Path installDir = apps.resolve("common").resolve(mf.installDir());
                        if (!mf.installDir().isEmpty() && !Files.exists(installDir)) continue;
                        String cover = findCover(apps, mf.appId());
                        out.add(new Game(mf.appId(), mf.name(), GameKind.STEAM,
                                installDir.toString(), "steam://rungameid/" + mf.appId(),
                                cover, 0L, false, false, List.of(), null, false, null, null, null));
                    }
                }
            }
        } catch (Exception ignored) {
            // Detection is best-effort; never break the launcher.
        }
        return out;
    }

    private static String findCover(Path steamApps, String appId) {
        Path[] candidates = {
                steamApps.resolve("librarycache").resolve(appId + "_library_600x900.jpg"),
                steamApps.resolve("librarycache").resolve(appId).resolve("library_600x900.jpg")
        };
        for (Path p : candidates) if (Files.exists(p)) return p.toString();
        return null;
    }

    private static Path findSteamRoot() {
        // 1) Registry (most reliable on Windows).
        try {
            Process p = new ProcessBuilder("reg", "query",
                    "HKCU\\Software\\Valve\\Steam", "/v", "SteamPath").redirectErrorStream(true).start();
            try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = r.readLine()) != null) {
                    int i = line.indexOf("REG_SZ");
                    if (i >= 0) {
                        Path path = Path.of(line.substring(i + "REG_SZ".length()).trim());
                        if (Files.isDirectory(path)) return path;
                    }
                }
            }
            p.waitFor();
        } catch (Exception ignored) { }
        // 2) Default install locations.
        for (String d : new String[]{
                "C:\\Program Files (x86)\\Steam", "C:\\Program Files\\Steam"}) {
            Path path = Path.of(d);
            if (Files.isDirectory(path)) return path;
        }
        return null;
    }
}
```

- [ ] **Step 4: Run to verify it passes**

Run: `gradlew test --tests "com.kano.launcher.core.games.SteamSourceTest" --no-daemon`
Expected: PASS (4 tests).

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/kano/launcher/core/games/SteamSource.java src/test/java/com/kano/launcher/core/games/SteamSourceTest.java
git commit -m "feat(hub): SteamSource detects installed games via vdf/acf parsing

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

### Task 5: `StandaloneSource` — user-added games

**Files:**
- Create: `src/main/java/com/kano/launcher/core/games/StandaloneSource.java`
- Test: `src/test/java/com/kano/launcher/core/games/StandaloneSourceTest.java`

**Interfaces:**
- Consumes: `Game`, `GameKind`, `GameSource`, `GamesStore`.
- Produces: `class StandaloneSource implements GameSource` with constructor `StandaloneSource(GamesStore store)`; `scan()` returns `store.manualGames()` (the games the user added by hand). `kind()` = `STANDALONE`.

- [ ] **Step 1: Write the failing test**

`src/test/java/com/kano/launcher/core/games/StandaloneSourceTest.java`:

```java
package com.kano.launcher.core.games;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class StandaloneSourceTest {
    @Test
    void returnsManuallyAddedGames(@TempDir Path dir) {
        GamesStore store = new GamesStore(dir);
        store.addManual(Game.basic("notepad", "Notepad", GameKind.STANDALONE, "C:/Windows", "C:/Windows/notepad.exe"));
        List<Game> games = new StandaloneSource(store).scan();
        assertEquals(1, games.size());
        assertEquals("Notepad", games.get(0).name());
        assertEquals(GameKind.STANDALONE, new StandaloneSource(store).kind());
    }
}
```

- [ ] **Step 2: Run to verify it fails**

Run: `gradlew test --tests "com.kano.launcher.core.games.StandaloneSourceTest" --no-daemon`
Expected: FAIL.

- [ ] **Step 3: Create `StandaloneSource.java`**

```java
package com.kano.launcher.core.games;

import java.util.List;

/** Games the user added by hand (any .exe). Backed by GamesStore's manual list. */
public final class StandaloneSource implements GameSource {
    private final GamesStore store;

    public StandaloneSource(GamesStore store) { this.store = store; }

    @Override public GameKind kind() { return GameKind.STANDALONE; }

    @Override public List<Game> scan() { return store.manualGames(); }
}
```

- [ ] **Step 4: Run to verify it passes**

Run: `gradlew test --tests "com.kano.launcher.core.games.StandaloneSourceTest" --no-daemon`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/kano/launcher/core/games/StandaloneSource.java src/test/java/com/kano/launcher/core/games/StandaloneSourceTest.java
git commit -m "feat(hub): StandaloneSource exposes user-added games

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

### Task 6: `GameLibrary` — aggregate sources + merge persisted data

**Files:**
- Create: `src/main/java/com/kano/launcher/core/games/GameLibrary.java`
- Test: `src/test/java/com/kano/launcher/core/games/GameLibraryTest.java`

**Interfaces:**
- Consumes: `Game`, `GameSource`, `GamesStore`.
- Produces: `class GameLibrary` with constructor `GameLibrary(List<GameSource> sources, GamesStore store)`; `List<Game> load()` — scans every source, concatenates, dedupes by `key()` (first source wins), then stamps each game's `lastPlayedEpoch` from `store.lastPlayedFor(key)`; sorted by name (case-insensitive) for a stable default order. `void recordPlayed(Game g)` — delegates to `store.recordPlayed(g.key(), System.currentTimeMillis())`.

- [ ] **Step 1: Write the failing test**

`src/test/java/com/kano/launcher/core/games/GameLibraryTest.java`:

```java
package com.kano.launcher.core.games;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class GameLibraryTest {
    private static GameSource source(GameKind kind, Game... games) {
        return new GameSource() {
            public GameKind kind() { return kind; }
            public List<Game> scan() { return List.of(games); }
        };
    }

    @Test
    void aggregatesDedupesAndAppliesLastPlayed(@TempDir Path dir) {
        GamesStore store = new GamesStore(dir);
        store.recordPlayed("STEAM:440", 1700000000L);
        GameSource a = source(GameKind.STEAM, Game.basic("440", "TF2", GameKind.STEAM, "x", "steam://rungameid/440"));
        GameSource dup = source(GameKind.STEAM, Game.basic("440", "TF2 dup", GameKind.STEAM, "y", "z"));
        GameSource mc = source(GameKind.MINECRAFT, Game.basic("minecraft", "Minecraft", GameKind.MINECRAFT, null, null));

        List<Game> games = new GameLibrary(List.of(a, dup, mc), store).load();
        assertEquals(2, games.size(), "duplicate STEAM:440 collapsed to one");
        Game tf2 = games.stream().filter(g -> g.key().equals("STEAM:440")).findFirst().orElseThrow();
        assertEquals("TF2", tf2.name(), "first source wins on dedupe");
        assertEquals(1700000000L, tf2.lastPlayedEpoch(), "last-played stamped from store");
    }

    @Test
    void sortedByNameCaseInsensitive(@TempDir Path dir) {
        GameSource s = source(GameKind.STANDALONE,
                Game.basic("b", "banjo", GameKind.STANDALONE, "x", "x"),
                Game.basic("a", "Azure", GameKind.STANDALONE, "y", "y"));
        List<Game> games = new GameLibrary(List.of(s), new GamesStore(dir)).load();
        assertEquals("Azure", games.get(0).name());
        assertEquals("banjo", games.get(1).name());
    }
}
```

- [ ] **Step 2: Run to verify it fails**

Run: `gradlew test --tests "com.kano.launcher.core.games.GameLibraryTest" --no-daemon`
Expected: FAIL.

- [ ] **Step 3: Create `GameLibrary.java`**

```java
package com.kano.launcher.core.games;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Aggregates every GameSource and merges in the user's persisted data (last-played). */
public final class GameLibrary {
    private final List<GameSource> sources;
    private final GamesStore store;

    public GameLibrary(List<GameSource> sources, GamesStore store) {
        this.sources = sources;
        this.store = store;
    }

    public List<Game> load() {
        Map<String, Game> byKey = new LinkedHashMap<>();
        for (GameSource src : sources) {
            for (Game g : src.scan()) {
                byKey.putIfAbsent(g.key(), g); // first source wins
            }
        }
        List<Game> games = new ArrayList<>();
        for (Game g : byKey.values()) {
            long lp = store.lastPlayedFor(g.key());
            games.add(lp > 0 ? g.withLastPlayed(lp) : g);
        }
        games.sort(Comparator.comparing(g -> g.name() == null ? "" : g.name().toLowerCase()));
        return games;
    }

    public void recordPlayed(Game g) {
        store.recordPlayed(g.key(), System.currentTimeMillis());
    }
}
```

- [ ] **Step 4: Run to verify it passes**

Run: `gradlew test --tests "com.kano.launcher.core.games.GameLibraryTest" --no-daemon`
Expected: PASS (2 tests).

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/kano/launcher/core/games/GameLibrary.java src/test/java/com/kano/launcher/core/games/GameLibraryTest.java
git commit -m "feat(hub): GameLibrary aggregates sources + merges last-played

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

### Task 7: `GameRunner` — launch a game by kind

**Files:**
- Create: `src/main/java/com/kano/launcher/core/games/GameRunner.java`
- Test: `src/test/java/com/kano/launcher/core/games/GameRunnerTest.java`

**Interfaces:**
- Consumes: `Game`, `GameKind`.
- Produces: `class GameRunner` with:
  - `static List<String> buildCommand(Game g)` (pure, testable): STEAM/EPIC/GOG → `["cmd","/c","start","",<launchTarget protocol url>]`; STANDALONE → `[<launchTarget exe>]` + space-split `launchOptions` (ignored if blank); MINECRAFT → throws `IllegalArgumentException` ("Minecraft launches natively — use the onMinecraft callback").
  - `void launch(Game g, Runnable onMinecraft)`: if `kind()==MINECRAFT` runs `onMinecraft`; otherwise starts a process from `buildCommand`, with working dir = `workingDirOverride` (if set) else the exe's parent for STANDALONE. Throws `java.io.IOException` on process-start failure so the UI can surface it.

- [ ] **Step 1: Write the failing test**

`src/test/java/com/kano/launcher/core/games/GameRunnerTest.java`:

```java
package com.kano.launcher.core.games;

import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class GameRunnerTest {
    @Test
    void steamUsesProtocolStart() {
        Game g = Game.basic("440", "TF2", GameKind.STEAM, "x", "steam://rungameid/440");
        assertEquals(List.of("cmd", "/c", "start", "", "steam://rungameid/440"), GameRunner.buildCommand(g));
    }

    @Test
    void standaloneUsesExePlusSplitOptions() {
        Game base = Game.basic("x", "X", GameKind.STANDALONE, "C:/x", "C:/x/x.exe");
        Game withOpts = new Game(base.id(), base.name(), base.kind(), base.installDir(), base.launchTarget(),
                null, 0L, false, false, List.of(), null, false, null, "-windowed -dx11", null);
        assertEquals(List.of("C:/x/x.exe", "-windowed", "-dx11"), GameRunner.buildCommand(withOpts));
        assertEquals(List.of("C:/x/x.exe"), GameRunner.buildCommand(base));
    }

    @Test
    void minecraftCommandIsRejected() {
        Game mc = Game.basic("minecraft", "Minecraft", GameKind.MINECRAFT, null, null);
        assertThrows(IllegalArgumentException.class, () -> GameRunner.buildCommand(mc));
    }

    @Test
    void launchRoutesMinecraftToCallback() {
        Game mc = Game.basic("minecraft", "Minecraft", GameKind.MINECRAFT, null, null);
        boolean[] called = {false};
        try { new GameRunner().launch(mc, () -> called[0] = true); } catch (Exception e) { fail(e); }
        assertTrue(called[0]);
    }
}
```

- [ ] **Step 2: Run to verify it fails**

Run: `gradlew test --tests "com.kano.launcher.core.games.GameRunnerTest" --no-daemon`
Expected: FAIL.

- [ ] **Step 3: Create `GameRunner.java`**

```java
package com.kano.launcher.core.games;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/** Launches a game by kind: store protocols via "start", standalone exes directly, Minecraft via callback. */
public final class GameRunner {

    public static List<String> buildCommand(Game g) {
        return switch (g.kind()) {
            case STEAM, EPIC, GOG -> List.of("cmd", "/c", "start", "", g.launchTarget());
            case STANDALONE -> {
                List<String> cmd = new ArrayList<>();
                cmd.add(g.launchTarget());
                String opts = g.launchOptions();
                if (opts != null && !opts.isBlank()) {
                    for (String part : opts.trim().split("\\s+")) cmd.add(part);
                }
                yield List.copyOf(cmd);
            }
            case MINECRAFT -> throw new IllegalArgumentException(
                    "Minecraft launches natively — use the onMinecraft callback");
        };
    }

    /** Launch {@code g}. Minecraft runs {@code onMinecraft}; everything else starts a process. */
    public void launch(Game g, Runnable onMinecraft) throws IOException {
        if (g.kind() == GameKind.MINECRAFT) { onMinecraft.run(); return; }
        ProcessBuilder pb = new ProcessBuilder(buildCommand(g));
        File wd = workingDir(g);
        if (wd != null && wd.isDirectory()) pb.directory(wd);
        pb.start();
    }

    private static File workingDir(Game g) {
        if (g.workingDirOverride() != null && !g.workingDirOverride().isBlank())
            return new File(g.workingDirOverride());
        if (g.kind() == GameKind.STANDALONE && g.launchTarget() != null)
            return new File(g.launchTarget()).getParentFile();
        return null;
    }
}
```

- [ ] **Step 4: Run to verify it passes**

Run: `gradlew test --tests "com.kano.launcher.core.games.GameRunnerTest" --no-daemon`
Expected: PASS (4 tests).

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/kano/launcher/core/games/GameRunner.java src/test/java/com/kano/launcher/core/games/GameRunnerTest.java
git commit -m "feat(hub): GameRunner launches games by kind (protocol/exe/native)

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

### Task 8: `GameLibraryView` hub + wire into `MainApp` (manual verification)

> **Testing note:** JavaFX UI isn't unit-tested in this project (no TestFX harness). This task is verified by building, launching, and visually confirming — consistent with how the launcher's UI has been verified to date. The logic it depends on (Tasks 1–7) is already covered by automated tests. Keep all hub UI in `GameLibraryView`; only minimal wiring goes in `MainApp`.

**Files:**
- Create: `src/main/java/com/kano/launcher/GameLibraryView.java`
- Modify: `src/main/java/com/kano/launcher/MainApp.java` (add games fields; init them in `initData()`; add `showGameLibrary()` + `addGameDialog()`; add a "Hub" nav item in `buildSidebar()`; open the hub on startup instead of `showHome()`).

**Interfaces:**
- Consumes: `Game`, `GameKind`, `GameLibrary`, `GamesStore`, `GameRunner`, `StandaloneSource`, `SteamSource`, `MinecraftSource` (Tasks 1–7); existing `MainApp.content`, `MainApp.nav(...)`, `MainApp.showHome()`, `MainApp.resolveDataDir()`, `MainApp.applyTheme()`.
- Produces: `class GameLibraryView` with constructor `GameLibraryView(List<Game> games, java.util.function.Consumer<Game> onOpen, Runnable onAddGame)` and `javafx.scene.layout.Region build()` returning the hub node (a scrollable flow of game tiles + an "+ Add game" tile). `onOpen` is called for any tile (the caller decides launch-vs-open-Minecraft).

- [ ] **Step 1: Create `GameLibraryView.java`**

```java
package com.kano.launcher;

import com.kano.launcher.core.games.Game;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.*;
import javafx.scene.paint.Color;

import java.io.File;
import java.util.List;
import java.util.function.Consumer;

/** The unified game hub: a grid of game tiles (cover + name) plus an "Add game" tile. */
public final class GameLibraryView {
    private final List<Game> games;
    private final Consumer<Game> onOpen;
    private final Runnable onAddGame;

    public GameLibraryView(List<Game> games, Consumer<Game> onOpen, Runnable onAddGame) {
        this.games = games;
        this.onOpen = onOpen;
        this.onAddGame = onAddGame;
    }

    public Region build() {
        FlowPane grid = new FlowPane(18, 18);
        grid.setPadding(new Insets(20));
        for (Game g : games) grid.getChildren().add(tile(g));
        grid.getChildren().add(addTile());

        Label header = new Label("YOUR GAMES");
        header.getStyleClass().add("page-eyebrow");
        Label title = new Label("Library");
        title.getStyleClass().add("page-title");
        VBox top = new VBox(2, header, title);
        top.setPadding(new Insets(20, 20, 0, 20));

        VBox wrap = new VBox(top, grid);
        ScrollPane scroll = new ScrollPane(wrap);
        scroll.setFitToWidth(true);
        scroll.getStyleClass().add("scroll-pane");
        return scroll;
    }

    private Region tile(Game g) {
        VBox card = new VBox(8);
        card.getStyleClass().add("card");
        card.setPrefWidth(180);
        card.setAlignment(Pos.CENTER);
        card.setPadding(new Insets(10));

        StackPane cover = new StackPane();
        cover.setPrefSize(150, 200);
        Color accent = g.accentHex() != null ? safeColor(g.accentHex()) : Color.web("#6E7681");
        cover.setBackground(new Background(new BackgroundFill(accent.deriveColor(0, 1, 0.5, 0.35), new CornerRadii(8), Insets.EMPTY)));
        if (g.coverArtPath() != null && new File(g.coverArtPath()).exists()) {
            ImageView iv = new ImageView(new Image(new File(g.coverArtPath()).toURI().toString(), 150, 200, true, true));
            cover.getChildren().add(iv);
        } else {
            Label initial = new Label(g.name() == null || g.name().isEmpty() ? "?" : g.name().substring(0, 1).toUpperCase());
            initial.setStyle("-fx-font-size: 48px; -fx-font-weight: bold; -fx-text-fill: white;");
            cover.getChildren().add(initial);
        }

        Label name = new Label(g.name());
        name.getStyleClass().add("card-title-sm");
        name.setWrapText(true);
        name.setAlignment(Pos.CENTER);

        card.getChildren().addAll(cover, name);
        card.setOnMouseClicked(e -> onOpen.accept(g));
        return card;
    }

    private Region addTile() {
        VBox card = new VBox();
        card.getStyleClass().add("card");
        card.setPrefSize(180, 240);
        card.setAlignment(Pos.CENTER);
        Label plus = new Label("+ Add game");
        plus.getStyleClass().add("card-title-sm");
        card.getChildren().add(plus);
        card.setOnMouseClicked(e -> onAddGame.run());
        return card;
    }

    private static Color safeColor(String hex) {
        try { return Color.web(hex); } catch (Exception e) { return Color.web("#6E7681"); }
    }
}
```

- [ ] **Step 2: Add games fields + init in `MainApp`**

Near the other field declarations (around `private InstanceManager instanceManager;`, ~line 118), add:

```java
    private com.kano.launcher.core.games.GamesStore gamesStore;
    private com.kano.launcher.core.games.GameLibrary gameLibrary;
    private final com.kano.launcher.core.games.GameRunner gameRunner = new com.kano.launcher.core.games.GameRunner();
```

In `initData()` (after `config = new Config(dir);`, ~line 482), add:

```java
            gamesStore = new com.kano.launcher.core.games.GamesStore(dir);
            gameLibrary = new com.kano.launcher.core.games.GameLibrary(java.util.List.of(
                    new com.kano.launcher.core.games.SteamSource(),
                    new com.kano.launcher.core.games.StandaloneSource(gamesStore),
                    new com.kano.launcher.core.games.MinecraftSource()), gamesStore);
```

- [ ] **Step 3: Add `showGameLibrary()` + `addGameDialog()` to `MainApp`**

Add these methods (next to `showHome()`):

```java
    private void showGameLibrary() {
        selectNav(navByName.get("Hub"));
        java.util.List<com.kano.launcher.core.games.Game> games =
                gameLibrary != null ? gameLibrary.load() : java.util.List.of();
        GameLibraryView view = new GameLibraryView(games, this::openGame, this::addGameDialog);
        content.getChildren().setAll(view.build());
    }

    private void openGame(com.kano.launcher.core.games.Game g) {
        if (g.kind() == com.kano.launcher.core.games.GameKind.MINECRAFT) { showHome(); return; }
        try {
            gameRunner.launch(g, this::showHome);
            if (gameLibrary != null) gameLibrary.recordPlayed(g);
        } catch (Exception ex) {
            alert(Alert.AlertType.ERROR, "Couldn't launch", g.name() + ":\n" + ex.getMessage());
        }
    }

    private void addGameDialog() {
        FileChooser fc = new FileChooser();
        fc.setTitle("Add a game — pick its .exe");
        fc.getExtensionFilters().add(new FileChooser.ExtensionFilter("Programs", "*.exe"));
        java.io.File f = fc.showOpenDialog(stage);
        if (f == null || gamesStore == null) return;
        String name = f.getName().replaceAll("(?i)\\.exe$", "");
        String id = name.toLowerCase().replaceAll("[^a-z0-9]+", "-");
        gamesStore.addManual(com.kano.launcher.core.games.Game.basic(
                id, name, com.kano.launcher.core.games.GameKind.STANDALONE,
                f.getParent(), f.getAbsolutePath()));
        showGameLibrary();
    }
```

- [ ] **Step 4: Add a "Hub" nav item in `buildSidebar()`**

In `buildSidebar()`, add a Hub entry as the FIRST nav item (before "Home"):

```java
                nav("▦", "Hub", this::showGameLibrary),
```

(Insert it in the existing `side.getChildren().addAll(...)` nav block, before the `nav("⌂", "Home", ...)` line.)

- [ ] **Step 5: Open the hub on startup**

In `start()`, find the startup view call `showHome();` (the "dashboard landing" line) and change it to:

```java
        showGameLibrary();   // hub is the new landing view; Minecraft is one tile inside it
```

- [ ] **Step 6: Build + run + verify**

Run: `gradlew test --no-daemon` → Expected: ALL tests pass (Tasks 1–7).
Run: `gradlew compileJava --no-daemon` → Expected: BUILD SUCCESSFUL.
Run the app (`gradlew run --no-daemon`) and confirm by sight:
- Launcher opens to the **Hub** showing a **Minecraft** tile + any installed **Steam** games (with cover art where Steam cached it, a colored initial otherwise).
- Clicking the **Minecraft** tile (or the sidebar **Home**) opens the existing Minecraft home unchanged.
- Clicking **Hub** in the sidebar returns to the library.
- **+ Add game** → pick an `.exe` → it appears as a tile; clicking it launches that program.
- Clicking a **Steam** game launches it through Steam.
- Existing Minecraft instances/settings are intact (open Instances — your instances are still there).

Capture a screenshot of the Hub to confirm (the project's screen-capture method).

- [ ] **Step 7: Commit**

```bash
git add src/main/java/com/kano/launcher/GameLibraryView.java src/main/java/com/kano/launcher/MainApp.java
git commit -m "feat(hub): game library view as the launcher's landing screen

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

## Self-Review (against the spec)

- **Spec coverage (Phase 1 steps 1–6):** ✅ Game model + games.json + GameLibrary merge (Tasks 1, 2, 6); SteamSource + StandaloneSource + MinecraftSource + GameRunner (Tasks 3–5, 7); minimal hub UI + Minecraft routing + "back to Hub" via the Hub nav (Task 8). **Deferred to Plan 1B** (called out, not silently dropped): favorites/★, recents row, custom collections, platform groups, sort toggle, per-game accent *theming-on-open* + per-game settings page + startup-target/jump-straight-in. **Deferred to Plan 1C:** ModSourceResolver + Add-Game mod-source discovery. *(`accentHex` and `moddable` fields exist on `Game` now so 1B/1C don't reshape it.)*
- **Backward-compat:** ✅ no existing Minecraft file is read or modified; Minecraft tile calls existing `showHome()`; `games.json` is new.
- **Placeholders:** none — every code step is complete.
- **Type consistency:** `Game.key()`, `Game.basic(...)`, `withLastPlayed`, `GameKind` values, `GameSource.scan()/kind()`, `GamesStore` method names, `GameLibrary(List<GameSource>, GamesStore).load()/recordPlayed(Game)`, `GameRunner.buildCommand(Game)/launch(Game, Runnable)` are used identically across tasks and the MainApp wiring.

## Notes for the executor
- This is **Plan 1A of 3** for Phase 1. After it merges, brainstorm/plan **1B (organize + per-game profiles)** then **1C (mod-source discovery)** — each its own spec→plan→build cycle.
- Windows-only commands (`reg query`, `cmd /c start`) match the app's Windows-only packaging.
- Keep the user's commit→push habit in mind, but let the user push.
