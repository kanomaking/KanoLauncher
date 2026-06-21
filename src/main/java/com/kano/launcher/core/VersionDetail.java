package com.kano.launcher.core;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The per-version JSON from the manifest: client jar, libraries, asset index, Java requirement,
 * main class, and launch arguments. OS rules are applied so {@link #libraries()} and
 * {@link #natives()} only contain what this machine needs.
 */
public final class VersionDetail {

    /** A downloadable file. {@code path} is the relative library/object path (may be null for the client). */
    public record Dl(String url, String sha1, long size, String path) {}

    private final String id;
    private final String mainClass;
    private final String assetIndexId;
    private final String assetIndexUrl;
    private final String assetIndexSha1;
    private final int javaMajor;
    private final Dl client;
    private final List<Dl> libraries;
    private final List<Dl> natives;
    private final JsonObject raw;

    private VersionDetail(String id, String mainClass, String assetIndexId, String assetIndexUrl,
                          String assetIndexSha1, int javaMajor, Dl client,
                          List<Dl> libraries, List<Dl> natives, JsonObject raw) {
        this.id = id;
        this.mainClass = mainClass;
        this.assetIndexId = assetIndexId;
        this.assetIndexUrl = assetIndexUrl;
        this.assetIndexSha1 = assetIndexSha1;
        this.javaMajor = javaMajor;
        this.client = client;
        this.libraries = libraries;
        this.natives = natives;
        this.raw = raw;
    }

    public static VersionDetail fetch(VersionManifest.VersionEntry entry) throws Exception {
        HttpClient http = HttpClient.newHttpClient();
        HttpResponse<String> r = http.send(
                HttpRequest.newBuilder(URI.create(entry.url())).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        if (r.statusCode() != 200) throw new RuntimeException("Version JSON fetch failed: " + r.statusCode());
        JsonObject root = new Gson().fromJson(r.body(), JsonObject.class);

        String id = root.get("id").getAsString();
        String mainClass = root.get("mainClass").getAsString();

        JsonObject clientObj = root.getAsJsonObject("downloads").getAsJsonObject("client");
        Dl client = new Dl(clientObj.get("url").getAsString(), clientObj.get("sha1").getAsString(),
                clientObj.get("size").getAsLong(), null);

        JsonObject ai = root.getAsJsonObject("assetIndex");
        String assetIndexId = ai.get("id").getAsString();
        String assetIndexUrl = ai.get("url").getAsString();
        String assetIndexSha1 = ai.get("sha1").getAsString();

        int javaMajor = 8;
        if (root.has("javaVersion")) {
            javaMajor = root.getAsJsonObject("javaVersion").get("majorVersion").getAsInt();
        }

        String osTag = osTag();
        String wantNative = nativeClassifier(); // exact, arch-correct (e.g. "natives-windows", not -windows-x86)
        List<Dl> libraries = new ArrayList<>();
        List<Dl> natives = new ArrayList<>();
        for (var el : root.getAsJsonArray("libraries")) {
            JsonObject lib = el.getAsJsonObject();
            if (!allowed(lib, osTag)) continue;
            if (!lib.has("downloads")) continue;
            JsonObject dls = lib.getAsJsonObject("downloads");

            if (dls.has("artifact")) {
                JsonObject a = dls.getAsJsonObject("artifact");
                String path = a.has("path") ? a.get("path").getAsString() : "";
                Dl dl = new Dl(a.get("url").getAsString(), a.get("sha1").getAsString(),
                        a.get("size").getAsLong(), path);
                if (path.contains("-natives-")) {
                    // Only the exact classifier for this OS+arch — endsWith avoids matching
                    // "-natives-windows-arm64.jar" / "-natives-windows-x86.jar" for an x64 host.
                    if (path.endsWith(wantNative + ".jar")) natives.add(dl);
                } else {
                    libraries.add(dl);
                }
            }
            // legacy native style: downloads.classifiers + natives map
            if (dls.has("classifiers") && lib.has("natives")) {
                JsonObject nm = lib.getAsJsonObject("natives");
                if (nm.has(osTag)) {
                    String classifier = nm.get(osTag).getAsString().replace("${arch}", "64");
                    JsonObject classifiers = dls.getAsJsonObject("classifiers");
                    if (classifiers.has(classifier)) {
                        JsonObject c = classifiers.getAsJsonObject(classifier);
                        natives.add(new Dl(c.get("url").getAsString(), c.get("sha1").getAsString(),
                                c.get("size").getAsLong(), c.has("path") ? c.get("path").getAsString() : ""));
                    }
                }
            }
        }
        return new VersionDetail(id, mainClass, assetIndexId, assetIndexUrl, assetIndexSha1,
                javaMajor, client, libraries, natives, root);
    }

    /** Evaluate Mojang library {@code rules} against the current OS. */
    private static boolean allowed(JsonObject lib, String osTag) {
        if (!lib.has("rules")) return true;
        boolean allow = false;
        JsonArray rules = lib.getAsJsonArray("rules");
        for (var el : rules) {
            JsonObject rule = el.getAsJsonObject();
            boolean matches = true;
            if (rule.has("os")) {
                JsonObject os = rule.getAsJsonObject("os");
                if (os.has("name")) matches = os.get("name").getAsString().equals(osTag);
            }
            if (matches) allow = "allow".equals(rule.get("action").getAsString());
        }
        return allow;
    }

    public static String osTag() {
        String os = System.getProperty("os.name").toLowerCase(Locale.ROOT);
        if (os.contains("win")) return "windows";
        if (os.contains("mac") || os.contains("darwin")) return "osx";
        return "linux";
    }

    private static boolean isArm64() {
        String a = System.getProperty("os.arch").toLowerCase(Locale.ROOT);
        return a.contains("aarch64") || a.contains("arm64");
    }

    /** The exact Mojang native classifier for this OS + CPU arch. */
    private static String nativeClassifier() {
        return switch (osTag()) {
            case "windows" -> isArm64() ? "natives-windows-arm64" : "natives-windows";
            case "osx" -> isArm64() ? "natives-macos-arm64" : "natives-macos";
            default -> isArm64() ? "natives-linux-arm64" : "natives-linux";
        };
    }

    public String id() { return id; }
    public String mainClass() { return mainClass; }
    public String assetIndexId() { return assetIndexId; }
    public String assetIndexUrl() { return assetIndexUrl; }
    public String assetIndexSha1() { return assetIndexSha1; }
    public int javaMajor() { return javaMajor; }
    public Dl client() { return client; }
    public List<Dl> libraries() { return libraries; }
    public List<Dl> natives() { return natives; }
    public JsonObject raw() { return raw; }
}
