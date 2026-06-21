package com.kano.launcher.core;

import com.google.gson.Gson;
import com.google.gson.JsonObject;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;

/**
 * The official Mojang version manifest — the authoritative list of every Minecraft version and
 * where to fetch each one's metadata. Fetched once; used to populate version pickers and, later,
 * to drive downloads.
 */
public final class VersionManifest {

    private static final String MANIFEST_URL =
            "https://piston-meta.mojang.com/mc/game/version_manifest_v2.json";

    /** @param type one of: release, snapshot, old_beta, old_alpha. {@code url} points to the per-version JSON. */
    public record VersionEntry(String id, String type, String url, String releaseTime) {}

    private final List<VersionEntry> versions;
    private final String latestRelease;
    private final String latestSnapshot;

    private VersionManifest(List<VersionEntry> versions, String latestRelease, String latestSnapshot) {
        this.versions = versions;
        this.latestRelease = latestRelease;
        this.latestSnapshot = latestSnapshot;
    }

    public static VersionManifest fetch() throws Exception {
        HttpClient http = HttpClient.newHttpClient();
        HttpResponse<String> r = http.send(
                HttpRequest.newBuilder(URI.create(MANIFEST_URL)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        if (r.statusCode() != 200) throw new RuntimeException("Manifest fetch failed: " + r.statusCode());

        JsonObject root = new Gson().fromJson(r.body(), JsonObject.class);
        JsonObject latest = root.getAsJsonObject("latest");
        String release = latest.get("release").getAsString();
        String snapshot = latest.get("snapshot").getAsString();

        List<VersionEntry> list = new ArrayList<>();
        for (var el : root.getAsJsonArray("versions")) {
            JsonObject o = el.getAsJsonObject();
            list.add(new VersionEntry(
                    o.get("id").getAsString(),
                    o.get("type").getAsString(),
                    o.get("url").getAsString(),
                    o.has("releaseTime") ? o.get("releaseTime").getAsString() : ""));
        }
        return new VersionManifest(list, release, snapshot);
    }

    public List<VersionEntry> all() {
        return List.copyOf(versions);
    }

    public VersionEntry find(String id) {
        return versions.stream().filter(v -> v.id().equals(id)).findFirst().orElse(null);
    }

    /** Release IDs only, newest first (manifest order). */
    public List<String> releaseIds() {
        List<String> out = new ArrayList<>();
        for (VersionEntry v : versions) if ("release".equals(v.type())) out.add(v.id());
        return out;
    }

    /** All IDs (every type) when {@code includeSnapshots}, otherwise releases only. Newest first. */
    public List<String> ids(boolean includeSnapshots) {
        if (!includeSnapshots) return releaseIds();
        List<String> out = new ArrayList<>();
        for (VersionEntry v : versions) out.add(v.id());
        return out;
    }

    public String latestRelease() { return latestRelease; }
    public String latestSnapshot() { return latestSnapshot; }
}
