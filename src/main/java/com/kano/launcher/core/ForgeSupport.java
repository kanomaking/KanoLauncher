package com.kano.launcher.core;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.jar.Manifest;
import java.util.zip.ZipFile;

/**
 * Installs and resolves NeoForge / Forge for an instance. Both projects share the modern
 * (MC 1.13+) installer format: an installer jar containing {@code install_profile.json} (libraries +
 * data + processors) and a {@code version.json} (the launch profile). We download the installer,
 * fetch its libraries, run the client-side processors that patch the vanilla jar into the modded
 * client, then parse {@code version.json} into a {@link Profile} the launcher feeds into the command.
 *
 * <p>The processor step is real work (it runs binpatcher / mappings / renamer tools as subprocesses)
 * and is cached behind a marker so it only runs once per loader+version.
 */
public final class ForgeSupport {

    /** Everything the loader adds on top of vanilla — mirrors {@link FabricSupport.Profile}. */
    public record Profile(String versionId, String mainClass,
                          List<VersionDetail.Dl> libraries,
                          List<String> jvmArgs, List<String> gameArgs) {}

    public interface Log { void line(String message); }

    private static final Gson GSON = new Gson();

    private ForgeSupport() {}

    /**
     * Ensure the loader is installed for {@code mcVersion}, then return its launch profile.
     *
     * @param vanillaClientJar path to the already-downloaded vanilla client jar (the processors'
     *                         {@code {MINECRAFT_JAR}} input)
     */
    public static Profile resolve(Path dataDir, String mcVersion, Loader loader,
                                  Path javaExe, Path vanillaClientJar, Log log) throws Exception {
        String loaderVersion = (loader == Loader.NEOFORGE)
                ? ForgeVersions.latestNeoForge(mcVersion)
                : ForgeVersions.latestForge(mcVersion);
        if (loaderVersion == null)
            throw new RuntimeException(loader.display() + " has no build for Minecraft " + mcVersion + ".");

        Path libDir = GameInstaller.librariesDir(dataDir);
        Path versionsDir = dataDir.resolve("cache").resolve("forge-versions");
        Path marker = versionsDir.resolve(loader.name().toLowerCase() + "-" + mcVersion + "-" + loaderVersion + ".json");

        JsonObject versionJson;
        if (Files.exists(marker)) {
            versionJson = GSON.fromJson(Files.readString(marker, StandardCharsets.UTF_8), JsonObject.class);
        } else {
            versionJson = install(dataDir, mcVersion, loader, loaderVersion, javaExe, vanillaClientJar, libDir, log);
            Files.createDirectories(versionsDir);
            Files.writeString(marker, GSON.toJson(versionJson), StandardCharsets.UTF_8);
        }
        return toProfile(versionJson, loaderVersion);
    }

    // ---- install ----

    private static JsonObject install(Path dataDir, String mcVersion, Loader loader, String loaderVersion,
                                      Path javaExe, Path vanillaClientJar, Path libDir, Log log) throws Exception {
        String installerUrl = (loader == Loader.NEOFORGE)
                ? "https://maven.neoforged.net/releases/net/neoforged/neoforge/" + loaderVersion
                  + "/neoforge-" + loaderVersion + "-installer.jar"
                : "https://maven.minecraftforge.net/net/minecraftforge/forge/" + loaderVersion
                  + "/forge-" + loaderVersion + "-installer.jar";
        Path installer = dataDir.resolve("cache").resolve("forge-installers")
                .resolve(loader.name().toLowerCase() + "-" + loaderVersion + "-installer.jar");
        log.line("Downloading " + loader.display() + " " + loaderVersion + " installer…");
        Downloader.download(installerUrl, null, installer);

        JsonObject installProfile, versionJson;
        try (ZipFile zip = new ZipFile(installer.toFile())) {
            installProfile = readJson(zip, "install_profile.json");
            if (installProfile == null) throw new RuntimeException("Installer has no install_profile.json.");
            String jsonPath = installProfile.has("json") ? installProfile.get("json").getAsString() : "/version.json";
            versionJson = readJson(zip, strip(jsonPath));
            if (versionJson == null) throw new RuntimeException("Installer has no " + jsonPath + ".");

            // Bundled maven artifacts (the universal/client jars) → copy into the libraries store.
            extractTree(zip, "maven/", libDir, log);
        }

        // Download libraries declared by both the install profile and the launch profile.
        downloadLibraries(installProfile, libDir, log);
        downloadLibraries(versionJson, libDir, log);

        // Run the client processors that produce the patched client + mapped libraries.
        runProcessors(installProfile, installer, javaExe, libDir, dataDir, vanillaClientJar, log);
        return versionJson;
    }

    private static void downloadLibraries(JsonObject profile, Path libDir, Log log) throws Exception {
        if (!profile.has("libraries")) return;
        for (JsonElement el : profile.getAsJsonArray("libraries")) {
            JsonObject lib = el.getAsJsonObject();
            if (!lib.has("downloads")) continue;
            JsonObject dls = lib.getAsJsonObject("downloads");
            if (!dls.has("artifact")) continue;
            JsonObject a = dls.getAsJsonObject("artifact");
            String url = a.has("url") ? a.get("url").getAsString() : "";
            String path = a.has("path") ? a.get("path").getAsString() : "";
            if (url.isBlank() || path.isBlank()) continue; // produced by a processor, not downloaded
            String sha1 = a.has("sha1") && !a.get("sha1").getAsString().isBlank() ? a.get("sha1").getAsString() : null;
            Downloader.download(url, sha1, libDir.resolve(path));
        }
    }

    private static void runProcessors(JsonObject installProfile, Path installer, Path javaExe,
                                      Path libDir, Path dataDir, Path vanillaClientJar, Log log) throws Exception {
        if (!installProfile.has("processors")) return;

        Map<String, String> data = buildDataMap(installProfile, installer, libDir, dataDir, vanillaClientJar);

        JsonArray processors = installProfile.getAsJsonArray("processors");
        int idx = 0, n = processors.size();
        for (JsonElement el : processors) {
            idx++;
            JsonObject p = el.getAsJsonObject();
            if (p.has("sides")) {
                boolean client = false;
                for (JsonElement s : p.getAsJsonArray("sides")) if ("client".equals(s.getAsString())) client = true;
                if (!client) continue;
            }
            String jarCoord = p.get("jar").getAsString();
            Path jarPath = libDir.resolve(coordToPath(jarCoord));

            List<String> cp = new ArrayList<>();
            cp.add(jarPath.toString());
            if (p.has("classpath"))
                for (JsonElement c : p.getAsJsonArray("classpath")) cp.add(libDir.resolve(coordToPath(c.getAsString())).toString());

            String mainClass = mainClassOf(jarPath);
            if (mainClass == null) throw new RuntimeException("No Main-Class in processor " + jarCoord);

            List<String> cmd = new ArrayList<>();
            cmd.add(javaExe.toString());
            cmd.add("-cp");
            cmd.add(String.join(java.io.File.pathSeparator, cp));
            cmd.add(mainClass);
            if (p.has("args"))
                for (JsonElement a : p.getAsJsonArray("args")) cmd.add(resolveArg(a.getAsString(), data, libDir));

            log.line("Processor " + idx + "/" + n + " (" + mainClass + ")…");
            Process proc = new ProcessBuilder(cmd).redirectErrorStream(true).start();
            String out = new String(proc.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            int code = proc.waitFor();
            if (code != 0)
                throw new RuntimeException("Processor failed (" + mainClass + ", exit " + code + "):\n"
                        + tail(out, 1500));
        }
    }

    /** Resolve install_profile {@code data} entries (client side) plus the built-in path tokens. */
    private static Map<String, String> buildDataMap(JsonObject installProfile, Path installer,
                                                    Path libDir, Path dataDir, Path vanillaClientJar) throws Exception {
        Map<String, String> data = new HashMap<>();
        if (installProfile.has("data")) {
            JsonObject d = installProfile.getAsJsonObject("data");
            for (String key : d.keySet()) {
                JsonObject pair = d.getAsJsonObject(key);
                if (!pair.has("client")) continue;
                data.put(key, resolveDataValue(pair.get("client").getAsString(), installer, libDir, dataDir));
            }
        }
        data.put("SIDE", "client");
        data.put("MINECRAFT_JAR", vanillaClientJar.toString());
        data.put("INSTALLER", installer.toString());
        data.put("ROOT", dataDir.toString());
        data.put("LIBRARY_DIR", libDir.toString());
        return data;
    }

    /** A {@code data} value: {@code [maven]} → lib path, {@code 'x'} → literal, {@code /e} → extracted file. */
    private static String resolveDataValue(String v, Path installer, Path libDir, Path dataDir) throws Exception {
        if (v == null || v.isEmpty()) return "";
        if (v.startsWith("[") && v.endsWith("]")) return libDir.resolve(coordToPath(v.substring(1, v.length() - 1))).toString();
        if (v.startsWith("'") && v.endsWith("'")) return v.substring(1, v.length() - 1);
        if (v.startsWith("/")) {
            Path out = dataDir.resolve("cache").resolve("forge-work").resolve(strip(v).replace('/', '_'));
            Files.createDirectories(out.getParent());
            try (ZipFile zip = new ZipFile(installer.toFile())) {
                var e = zip.getEntry(strip(v));
                if (e == null) return v;
                try (InputStream in = zip.getInputStream(e)) { Files.copy(in, out, StandardCopyOption.REPLACE_EXISTING); }
            }
            return out.toString();
        }
        return v;
    }

    /** A processor arg: substitute {@code {TOKENS}}, then handle a bare {@code [maven]} / {@code 'literal'}. */
    private static String resolveArg(String a, Map<String, String> data, Path libDir) {
        for (Map.Entry<String, String> e : data.entrySet()) a = a.replace("{" + e.getKey() + "}", e.getValue());
        if (a.startsWith("[") && a.endsWith("]")) return libDir.resolve(coordToPath(a.substring(1, a.length() - 1))).toString();
        if (a.startsWith("'") && a.endsWith("'")) return a.substring(1, a.length() - 1);
        return a;
    }

    // ---- version.json → Profile ----

    private static Profile toProfile(JsonObject versionJson, String loaderVersion) {
        String mainClass = versionJson.get("mainClass").getAsString();
        List<VersionDetail.Dl> libs = new ArrayList<>();
        if (versionJson.has("libraries")) {
            for (JsonElement el : versionJson.getAsJsonArray("libraries")) {
                JsonObject lib = el.getAsJsonObject();
                String path;
                if (lib.has("downloads") && lib.getAsJsonObject("downloads").has("artifact")) {
                    JsonObject a = lib.getAsJsonObject("downloads").getAsJsonObject("artifact");
                    path = a.has("path") ? a.get("path").getAsString() : coordToPath(lib.get("name").getAsString());
                } else {
                    path = coordToPath(lib.get("name").getAsString());
                }
                libs.add(new VersionDetail.Dl(null, null, 0, path));
            }
        }
        List<String> jvm = new ArrayList<>();
        List<String> game = new ArrayList<>();
        if (versionJson.has("arguments")) {
            JsonObject args = versionJson.getAsJsonObject("arguments");
            if (args.has("jvm")) collectPrimitives(args.getAsJsonArray("jvm"), jvm);
            if (args.has("game")) collectPrimitives(args.getAsJsonArray("game"), game);
        }
        String id = versionJson.has("id") ? versionJson.get("id").getAsString() : loaderVersion;
        return new Profile(id, mainClass, libs, jvm, game);
    }

    /** Keep only plain-string args; OS/feature-gated objects are evaluated later by GameLauncher's subs. */
    private static void collectPrimitives(JsonArray arr, List<String> out) {
        for (JsonElement el : arr) if (el.isJsonPrimitive()) out.add(el.getAsString());
    }

    // ---- helpers ----

    /** Maven coord {@code g:a:v[:classifier][@ext]} → repo-relative path. */
    static String coordToPath(String coord) {
        String ext = "jar";
        int at = coord.indexOf('@');
        if (at >= 0) { ext = coord.substring(at + 1); coord = coord.substring(0, at); }
        String[] p = coord.split(":");
        String group = p[0].replace('.', '/');
        String artifact = p[1];
        String version = p[2];
        String classifier = p.length > 3 ? "-" + p[3] : "";
        return group + "/" + artifact + "/" + version + "/" + artifact + "-" + version + classifier + "." + ext;
    }

    private static String mainClassOf(Path jar) throws Exception {
        try (ZipFile zip = new ZipFile(jar.toFile())) {
            var e = zip.getEntry("META-INF/MANIFEST.MF");
            if (e == null) return null;
            try (InputStream in = zip.getInputStream(e)) {
                return new Manifest(in).getMainAttributes().getValue("Main-Class");
            }
        }
    }

    private static JsonObject readJson(ZipFile zip, String entry) throws Exception {
        var e = zip.getEntry(entry);
        if (e == null) return null;
        try (InputStream in = zip.getInputStream(e)) {
            return GSON.fromJson(new String(in.readAllBytes(), StandardCharsets.UTF_8), JsonObject.class);
        }
    }

    private static void extractTree(ZipFile zip, String prefix, Path destRoot, Log log) throws Exception {
        var entries = zip.entries();
        while (entries.hasMoreElements()) {
            var e = entries.nextElement();
            String name = e.getName();
            if (e.isDirectory() || !name.startsWith(prefix)) continue;
            String rel = name.substring(prefix.length());
            if (rel.isEmpty()) continue;
            Path out = destRoot.resolve(rel);
            Files.createDirectories(out.getParent());
            try (InputStream in = zip.getInputStream(e)) { Files.copy(in, out, StandardCopyOption.REPLACE_EXISTING); }
        }
    }

    private static String strip(String s) { return s.startsWith("/") ? s.substring(1) : s; }

    private static String tail(String s, int max) { return s.length() > max ? "…" + s.substring(s.length() - max) : s; }
}
