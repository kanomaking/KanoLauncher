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
