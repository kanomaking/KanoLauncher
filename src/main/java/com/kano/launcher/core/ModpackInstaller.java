package com.kano.launcher.core;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

/**
 * Imports a Modrinth modpack (.mrpack). An .mrpack is a zip containing {@code modrinth.index.json}
 * plus optional {@code overrides/} (and {@code client-overrides/}). We parse the index, download each
 * declared file to its path (SHA-1 verified) honoring Modrinth's download-URL whitelist, then extract
 * the overrides into the instance.
 *
 * Modrinth-only by design: the format's whitelist forbids arbitrary hosts (and CurseForge), so this
 * never reaches outside the trusted set.
 */
public final class ModpackInstaller {

    /**
     * Hosts Modrinth permits .mrpack downloads from (matched by suffix, so subdomains like
     * {@code cdn.modrinth.com} / {@code raw.githubusercontent.com} / {@code objects.githubusercontent.com}
     * are covered). Anything else is rejected.
     */
    private static final Set<String> ALLOWED_HOSTS = Set.of(
            "modrinth.com", "github.com", "githubusercontent.com", "gitlab.com");

    public interface Log { void line(String message); }

    /** One file the pack wants placed at {@code path}. */
    public record PackFile(String path, String sha1, List<String> downloads, boolean clientNeeded) {}

    /** Parsed {@code modrinth.index.json}. */
    public record Index(String name, String versionId, String mcVersion,
                        Loader loader, String loaderVersion, List<PackFile> files) {}

    private ModpackInstaller() {}

    // ---- parse ----

    public static Index readIndex(Path mrpack) throws Exception {
        String json = readEntry(mrpack, "modrinth.index.json");
        if (json == null)
            throw new RuntimeException("Not a Modrinth modpack — modrinth.index.json is missing.");
        JsonObject root = new Gson().fromJson(json, JsonObject.class);

        String name = str(root, "name");
        String versionId = str(root, "versionId");

        JsonObject deps = root.has("dependencies") ? root.getAsJsonObject("dependencies") : new JsonObject();
        String mc = deps.has("minecraft") ? deps.get("minecraft").getAsString() : null;
        if (mc == null || mc.isBlank())
            throw new RuntimeException("Modpack does not declare a Minecraft version.");

        Loader loader = Loader.VANILLA;
        String loaderVersion = null;
        if (deps.has("fabric-loader"))      { loader = Loader.FABRIC;   loaderVersion = deps.get("fabric-loader").getAsString(); }
        else if (deps.has("quilt-loader"))  { loader = Loader.QUILT;    loaderVersion = deps.get("quilt-loader").getAsString(); }
        else if (deps.has("neoforge"))      { loader = Loader.NEOFORGE; loaderVersion = deps.get("neoforge").getAsString(); }
        else if (deps.has("forge"))         { loader = Loader.FORGE;    loaderVersion = deps.get("forge").getAsString(); }

        List<PackFile> files = new ArrayList<>();
        if (root.has("files")) {
            for (JsonElement el : root.getAsJsonArray("files")) {
                JsonObject f = el.getAsJsonObject();
                String path = f.get("path").getAsString();
                String sha1 = null;
                if (f.has("hashes")) {
                    JsonObject h = f.getAsJsonObject("hashes");
                    if (h.has("sha1") && !h.get("sha1").isJsonNull()) sha1 = h.get("sha1").getAsString();
                }
                List<String> dls = new ArrayList<>();
                if (f.has("downloads")) for (JsonElement d : f.getAsJsonArray("downloads")) dls.add(d.getAsString());
                boolean clientNeeded = true;
                if (f.has("env")) {
                    JsonObject env = f.getAsJsonObject("env");
                    if (env.has("client")) clientNeeded = !"unsupported".equals(env.get("client").getAsString());
                }
                files.add(new PackFile(path, sha1, dls, clientNeeded));
            }
        }
        return new Index(name, versionId, mc, loader, loaderVersion, files);
    }

    // ---- install ----

    /** Download every client-side file to its path and extract overrides into {@code instanceDir}. */
    public static int installInto(Path mrpack, Index index, Path instanceDir, Log log) throws Exception {
        Files.createDirectories(instanceDir);
        int n = 0;
        for (PackFile f : index.files()) {
            if (!f.clientNeeded()) continue;
            String url = pickAllowed(f.downloads());
            if (url == null) {
                throw new RuntimeException("\"" + f.path()
                        + "\" has no download from a Modrinth-approved host — skipping the pack to stay safe.");
            }
            Path dest = safeResolve(instanceDir, f.path());
            Downloader.download(url, f.sha1(), dest);
            log.line("Downloaded " + f.path());
            n++;
        }
        n += extractOverrides(mrpack, instanceDir, log);
        return n;
    }

    private static int extractOverrides(Path mrpack, Path instanceDir, Log log) throws Exception {
        int n = 0;
        try (ZipFile zip = new ZipFile(mrpack.toFile())) {
            var entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry e = entries.nextElement();
                String name = e.getName().replace('\\', '/');
                String rel;
                if (name.startsWith("overrides/")) rel = name.substring("overrides/".length());
                else if (name.startsWith("client-overrides/")) rel = name.substring("client-overrides/".length());
                else continue; // server-overrides/ and modrinth.index.json are skipped for a client install
                if (rel.isEmpty()) continue;
                Path dest = safeResolve(instanceDir, rel);
                if (e.isDirectory()) { Files.createDirectories(dest); continue; }
                Files.createDirectories(dest.getParent());
                try (InputStream in = zip.getInputStream(e)) {
                    Files.copy(in, dest, StandardCopyOption.REPLACE_EXISTING);
                }
                log.line("Override " + rel);
                n++;
            }
        }
        return n;
    }

    // ---- export ----

    /** Instance subfolders worth shipping in an exported pack. */
    private static final String[] EXPORT_DIRS =
            {"mods", "config", "resourcepacks", "shaderpacks", "datapacks", "kubejs", "defaultconfigs", "scripts"};
    private static final String[] EXPORT_FILES = {"options.txt"};

    /**
     * Export an instance as a self-contained {@code .mrpack}: a valid Modrinth index (with the MC
     * version + loader) plus every mod/config/pack zipped under {@code overrides/}. Self-contained
     * means it re-imports anywhere with no external downloads (worlds and logs are excluded;
     * disabled mods are skipped).
     */
    public static Path export(Path instanceDir, String name, String mcVersion, Loader loader, Path outFile) throws Exception {
        JsonObject root = new JsonObject();
        root.addProperty("formatVersion", 1);
        root.addProperty("game", "minecraft");
        root.addProperty("versionId", "1.0.0");
        root.addProperty("name", name == null || name.isBlank() ? "Exported Pack" : name);
        root.add("files", new JsonArray()); // self-contained: everything is in overrides/
        JsonObject deps = new JsonObject();
        deps.addProperty("minecraft", mcVersion);
        String lk = loaderKey(loader);
        if (lk != null) deps.addProperty(lk, "*");
        root.add("dependencies", deps);

        Files.createDirectories(outFile.getParent());
        String index = new GsonBuilder().setPrettyPrinting().create().toJson(root);
        try (ZipOutputStream z = new ZipOutputStream(Files.newOutputStream(outFile))) {
            z.putNextEntry(new ZipEntry("modrinth.index.json"));
            z.write(index.getBytes(StandardCharsets.UTF_8));
            z.closeEntry();
            for (String d : EXPORT_DIRS) zipTree(z, instanceDir, instanceDir.resolve(d));
            for (String f : EXPORT_FILES) {
                Path p = instanceDir.resolve(f);
                if (Files.isRegularFile(p)) {
                    z.putNextEntry(new ZipEntry("overrides/" + f));
                    Files.copy(p, z);
                    z.closeEntry();
                }
            }
        }
        return outFile;
    }

    private static void zipTree(ZipOutputStream z, Path instanceDir, Path dir) throws Exception {
        if (!Files.isDirectory(dir)) return;
        try (var walk = Files.walk(dir)) {
            for (Path p : (Iterable<Path>) walk::iterator) {
                if (!Files.isRegularFile(p)) continue;
                if (p.getFileName().toString().endsWith(".disabled")) continue;
                String rel = instanceDir.relativize(p).toString().replace('\\', '/');
                z.putNextEntry(new ZipEntry("overrides/" + rel));
                Files.copy(p, z);
                z.closeEntry();
            }
        }
    }

    private static String loaderKey(Loader loader) {
        return switch (loader) {
            case FABRIC -> "fabric-loader";
            case QUILT -> "quilt-loader";
            case NEOFORGE -> "neoforge";
            case FORGE -> "forge";
            default -> null;
        };
    }

    // ---- helpers ----

    private static String pickAllowed(List<String> urls) {
        for (String u : urls) {
            try {
                String host = URI.create(u).getHost();
                if (hostAllowed(host)) return u;
            } catch (Exception ignore) {
            }
        }
        return null;
    }

    private static boolean hostAllowed(String host) {
        if (host == null) return false;
        host = host.toLowerCase(Locale.ROOT);
        for (String a : ALLOWED_HOSTS) {
            if (host.equals(a) || host.endsWith("." + a)) return true;
        }
        return false;
    }

    /** Resolve {@code rel} under {@code base}, refusing any path that escapes the instance dir. */
    private static Path safeResolve(Path base, String rel) {
        Path b = base.normalize();
        Path p = b.resolve(rel).normalize();
        if (!p.startsWith(b)) throw new RuntimeException("Unsafe path in modpack: " + rel);
        return p;
    }

    private static String readEntry(Path mrpack, String entryName) throws Exception {
        try (ZipFile zip = new ZipFile(mrpack.toFile())) {
            ZipEntry e = zip.getEntry(entryName);
            if (e == null) return null;
            try (InputStream in = zip.getInputStream(e)) {
                return new String(in.readAllBytes(), StandardCharsets.UTF_8);
            }
        }
    }

    private static String str(JsonObject o, String k) {
        return o.has(k) && !o.get(k).isJsonNull() ? o.get(k).getAsString() : "";
    }
}
