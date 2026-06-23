package com.kano.launcher.core;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Imports a CurseForge modpack (.zip with {@code manifest.json}) WITHOUT a CurseForge developer API
 * key. The gated developer API (api.curseforge.com) needs an approved key that CurseForge revokes
 * when it's shared/bundled — so instead we use the same PUBLIC endpoints a browser uses:
 * <ol>
 *   <li>{@code manifest.json} lists each mod by {@code projectID + fileID} (no name, no URL).</li>
 *   <li>{@code https://www.curseforge.com/api/v1/mods/{projectID}/files/{fileID}} → the real fileName.</li>
 *   <li>Build the forgecdn URL from {@code fileID + fileName} and download it (the CDN is key-free
 *       when you have the full path).</li>
 * </ol>
 * Then extract {@code overrides/}. Files whose authors blocked off-site distribution may 404 — those
 * are collected and returned so the caller can tell the user to grab them manually.
 */
public final class CfModpackInstaller {

    private static final String SITE_API = "https://www.curseforge.com/api/v1/mods/";
    private static final String UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120 Safari/537.36";

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(20))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    /** One mod the pack references (CurseForge identifies files by project + file id, not by name). */
    public record CfFile(long projectId, long fileId, boolean required) {}

    /** Parsed {@code manifest.json}. */
    public record CfIndex(String name, String mcVersion, Loader loader, List<CfFile> files, String overridesDir) {}

    /** A mod that couldn't be fetched (author blocked off-site distribution, or it 404'd). */
    public record Missing(long projectId, long fileId, String reason) {}

    private CfModpackInstaller() {}

    /** True if the zip looks like a CurseForge modpack (has a {@code manifest.json} with files + minecraft). */
    public static boolean isCfModpack(Path zip) {
        try {
            String json = readEntry(zip, "manifest.json");
            if (json == null) return false;
            JsonObject o = new Gson().fromJson(json, JsonObject.class);
            return o != null && o.has("minecraft") && o.has("files");
        } catch (Exception e) {
            return false;
        }
    }

    // ---- parse ----

    public static CfIndex readManifest(Path zip) throws Exception {
        String json = readEntry(zip, "manifest.json");
        if (json == null) throw new RuntimeException("Not a CurseForge modpack — manifest.json is missing.");
        JsonObject root = new Gson().fromJson(json, JsonObject.class);

        String name = root.has("name") && !root.get("name").isJsonNull() ? root.get("name").getAsString() : "";
        JsonObject mcObj = root.has("minecraft") ? root.getAsJsonObject("minecraft") : new JsonObject();
        String mc = mcObj.has("version") && !mcObj.get("version").isJsonNull() ? mcObj.get("version").getAsString() : null;
        if (mc == null || mc.isBlank()) throw new RuntimeException("Modpack does not declare a Minecraft version.");

        Loader loader = Loader.VANILLA;
        if (mcObj.has("modLoaders")) {
            String id = null;
            for (JsonElement el : mcObj.getAsJsonArray("modLoaders")) {
                JsonObject ml = el.getAsJsonObject();
                String mlId = ml.has("id") ? ml.get("id").getAsString() : "";
                if (ml.has("primary") && ml.get("primary").getAsBoolean()) { id = mlId; break; }
                if (id == null) id = mlId; // fall back to the first loader if none is flagged primary
            }
            if (id != null) {
                String low = id.toLowerCase(Locale.ROOT);
                if (low.startsWith("neoforge")) loader = Loader.NEOFORGE;
                else if (low.startsWith("forge")) loader = Loader.FORGE;
                else if (low.startsWith("fabric")) loader = Loader.FABRIC;
                else if (low.startsWith("quilt")) loader = Loader.QUILT;
            }
        }

        List<CfFile> files = new ArrayList<>();
        if (root.has("files")) {
            for (JsonElement el : root.getAsJsonArray("files")) {
                JsonObject f = el.getAsJsonObject();
                long pid = f.get("projectID").getAsLong();
                long fid = f.get("fileID").getAsLong();
                boolean req = !f.has("required") || f.get("required").getAsBoolean();
                files.add(new CfFile(pid, fid, req));
            }
        }
        String overrides = root.has("overrides") && !root.get("overrides").isJsonNull()
                ? root.get("overrides").getAsString() : "overrides";
        return new CfIndex(name, mc, loader, files, overrides);
    }

    // ---- resolve + download (key-free) ----

    /** Resolve the real fileName for a (projectId, fileId) via the public website API. Null if not found. */
    public static String resolveFileName(long projectId, long fileId) throws Exception {
        HttpResponse<String> r = HTTP.send(
                HttpRequest.newBuilder(URI.create(SITE_API + projectId + "/files/" + fileId))
                        .header("User-Agent", UA).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        if (r.statusCode() != 200) return null;
        JsonObject o = new Gson().fromJson(r.body(), JsonObject.class);
        if (o == null || !o.has("data") || o.get("data").isJsonNull()) return null;
        JsonObject data = o.getAsJsonObject("data");
        return data.has("fileName") && !data.get("fileName").isJsonNull() ? data.get("fileName").getAsString() : null;
    }

    /** Build the forgecdn URL for a file. fileId 8300448 → /files/8300/448/&lt;name&gt;. */
    public static String cdnUrl(long fileId, String fileName) throws Exception {
        long p1 = fileId / 1000;
        long p2 = fileId % 1000;
        // The URI constructor percent-encodes the path (spaces → %20 etc.) while leaving valid path chars.
        return new URI("https", "mediafilez.forgecdn.net", "/files/" + p1 + "/" + p2 + "/" + fileName, null)
                .toASCIIString();
    }

    /**
     * Download every referenced mod into {@code instanceDir/mods} and extract {@code overrides/}.
     * Returns the files that couldn't be fetched (caller reports them); throws only on a hard failure.
     */
    public static List<Missing> installInto(Path zip, CfIndex index, Path instanceDir, ModpackInstaller.Log log) throws Exception {
        Files.createDirectories(instanceDir);
        Files.createDirectories(instanceDir.resolve("mods"));
        List<Missing> missing = new ArrayList<>();
        for (CfFile f : index.files()) {
            try {
                String fn = resolveFileName(f.projectId(), f.fileId());
                if (fn == null) {
                    missing.add(new Missing(f.projectId(), f.fileId(), "metadata not found"));
                    log.line("Could not resolve project " + f.projectId() + " file " + f.fileId());
                } else {
                    Path dest = safeResolve(instanceDir, "mods/" + fn);
                    Downloader.download(cdnUrl(f.fileId(), fn), null, dest);
                    log.line("Downloaded " + fn);
                }
            } catch (Exception ex) {
                missing.add(new Missing(f.projectId(), f.fileId(), String.valueOf(ex.getMessage())));
                log.line("Failed project " + f.projectId() + " file " + f.fileId() + ": " + ex.getMessage());
            }
            try { Thread.sleep(120); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
        }
        extractOverrides(zip, index.overridesDir(), instanceDir, log);
        return missing;
    }

    private static void extractOverrides(Path zip, String overridesDir, Path instanceDir, ModpackInstaller.Log log) throws Exception {
        String prefix = (overridesDir == null || overridesDir.isBlank() ? "overrides" : overridesDir) + "/";
        try (ZipFile z = new ZipFile(zip.toFile())) {
            var entries = z.entries();
            while (entries.hasMoreElements()) {
                ZipEntry e = entries.nextElement();
                String n = e.getName().replace('\\', '/');
                if (!n.startsWith(prefix)) continue;
                String rel = n.substring(prefix.length());
                if (rel.isEmpty()) continue;
                Path dest = safeResolve(instanceDir, rel);
                if (e.isDirectory()) { Files.createDirectories(dest); continue; }
                Files.createDirectories(dest.getParent());
                try (InputStream in = z.getInputStream(e)) {
                    Files.copy(in, dest, StandardCopyOption.REPLACE_EXISTING);
                }
                log.line("Override " + rel);
            }
        }
    }

    // ---- helpers ----

    /** Resolve {@code rel} under {@code base}, refusing any path that escapes the instance dir (zip-slip). */
    private static Path safeResolve(Path base, String rel) {
        Path b = base.normalize();
        Path p = b.resolve(rel).normalize();
        if (!p.startsWith(b)) throw new RuntimeException("Unsafe path in modpack: " + rel);
        return p;
    }

    private static String readEntry(Path zip, String entryName) throws Exception {
        try (ZipFile z = new ZipFile(zip.toFile())) {
            ZipEntry e = z.getEntry(entryName);
            if (e == null) return null;
            try (InputStream in = z.getInputStream(e)) {
                return new String(in.readAllBytes(), StandardCharsets.UTF_8);
            }
        }
    }
}
