package com.kano.launcher.core;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * CurseForge API v1 source. Requires the user's own API key (the CF 3rd-party API is gated — we
 * never bundle one). Maps CF responses onto the common Modrinth-shaped DTOs.
 */
public final class CurseForgeClient implements ContentSource {

    private static final String API = "https://api.curseforge.com/v1/";
    private static final int MINECRAFT = 432;

    private final String apiKey;
    private final HttpClient http = HttpClient.newHttpClient();
    private final Gson gson = new Gson();

    public CurseForgeClient(String apiKey) {
        this.apiKey = apiKey == null ? "" : apiKey.trim();
    }

    @Override public String id() { return "curseforge"; }

    @Override
    public ModrinthClient.SearchResult search(String query, String gameVersion, String loader,
                                              String projectType, String sort, int offset, int limit) throws Exception {
        if (apiKey.isBlank()) throw new RuntimeException("No CurseForge API key set (Settings).");
        int classId = classId(projectType);
        int loaderType = loaderType(loader);
        StringBuilder url = new StringBuilder(API + "mods/search?gameId=" + MINECRAFT
                + "&classId=" + classId + "&pageSize=" + limit + "&index=" + offset
                + "&sortField=" + sortField(sort) + "&sortOrder=desc");
        if (query != null && !query.isBlank()) url.append("&searchFilter=").append(enc(query));
        if (gameVersion != null && !gameVersion.isBlank()) url.append("&gameVersion=").append(enc(gameVersion));
        if (loaderType > 0) url.append("&modLoaderType=").append(loaderType);

        JsonObject root = gson.fromJson(get(url.toString()), JsonObject.class);
        List<ModrinthClient.Hit> hits = new ArrayList<>();
        for (JsonElement el : root.getAsJsonArray("data")) {
            JsonObject m = el.getAsJsonObject();
            hits.add(new ModrinthClient.Hit(
                    m.get("id").getAsString(),
                    str(m, "slug"),
                    str(m, "name"),
                    str(m, "summary"),
                    m.has("downloadCount") ? (int) m.get("downloadCount").getAsLong() : 0,
                    logoUrl(m),
                    projectType));
        }
        int total = root.has("pagination") && root.getAsJsonObject("pagination").has("totalCount")
                ? root.getAsJsonObject("pagination").get("totalCount").getAsInt() : hits.size();
        return new ModrinthClient.SearchResult(hits, total);
    }

    @Override
    public List<ModrinthClient.ModVersion> versions(String modId, String gameVersion, String loader) throws Exception {
        if (apiKey.isBlank()) throw new RuntimeException("No CurseForge API key set (Settings).");
        int loaderType = loaderType(loader);
        StringBuilder url = new StringBuilder(API + "mods/" + modId + "/files?pageSize=50");
        if (gameVersion != null && !gameVersion.isBlank()) url.append("&gameVersion=").append(enc(gameVersion));
        if (loaderType > 0) url.append("&modLoaderType=").append(loaderType);

        JsonObject root = gson.fromJson(get(url.toString()), JsonObject.class);
        List<ModrinthClient.ModVersion> out = new ArrayList<>();
        for (JsonElement el : root.getAsJsonArray("data")) {
            JsonObject f = el.getAsJsonObject();
            String dl = f.has("downloadUrl") && !f.get("downloadUrl").isJsonNull()
                    ? f.get("downloadUrl").getAsString() : null;
            List<ModrinthClient.ModFile> files = List.of(
                    new ModrinthClient.ModFile(dl, str(f, "fileName"), true));
            List<ModrinthClient.Dep> deps = new ArrayList<>();
            if (f.has("dependencies")) {
                for (JsonElement de : f.getAsJsonArray("dependencies")) {
                    JsonObject d = de.getAsJsonObject();
                    // relationType 3 = RequiredDependency
                    if (d.has("relationType") && d.get("relationType").getAsInt() == 3 && d.has("modId")) {
                        deps.add(new ModrinthClient.Dep(d.get("modId").getAsString(), "required"));
                    }
                }
            }
            out.add(new ModrinthClient.ModVersion(f.get("id").getAsString(), str(f, "fileName"), files, deps));
        }
        return out;
    }

    @Override
    public ModrinthClient.ProjectDetail project(String modId) throws Exception {
        if (apiKey.isBlank()) throw new RuntimeException("No CurseForge API key set (Settings).");
        JsonObject m = gson.fromJson(get(API + "mods/" + modId), JsonObject.class).getAsJsonObject("data");
        List<String> cats = new ArrayList<>();
        if (m.has("categories")) for (JsonElement c : m.getAsJsonArray("categories")) cats.add(str(c.getAsJsonObject(), "name"));
        String source = "";
        if (m.has("links") && !m.get("links").isJsonNull()) source = str(m.getAsJsonObject("links"), "sourceUrl");
        String body;
        try {
            JsonObject d = gson.fromJson(get(API + "mods/" + modId + "/description"), JsonObject.class);
            body = d.has("data") ? d.get("data").getAsString() : str(m, "summary");
        } catch (Exception e) {
            body = str(m, "summary");
        }
        return new ModrinthClient.ProjectDetail(
                str(m, "name"), str(m, "summary"), body,
                m.has("downloadCount") ? (int) m.get("downloadCount").getAsLong() : 0,
                m.has("thumbsUpCount") ? m.get("thumbsUpCount").getAsInt() : 0,
                cats, logoUrl(m), source, str(m, "slug"), m.get("id").getAsString());
    }

    private static int classId(String type) {
        return switch (type) {
            case "resourcepack" -> 12;
            case "shader" -> 6552;
            case "datapack" -> 6945;
            case "modpack" -> 4471;
            default -> 6; // mod
        };
    }

    private static int loaderType(String loader) {
        if (loader == null) return 0;
        return switch (loader) {
            case "forge" -> 1;
            case "fabric" -> 4;
            case "quilt" -> 5;
            case "neoforge" -> 6;
            default -> 0;
        };
    }

    private static int sortField(String sort) {
        return switch (sort) {
            case "downloads" -> 6;   // TotalDownloads
            case "newest", "updated" -> 3; // LastUpdated
            default -> 2;            // Popularity
        };
    }

    private static String logoUrl(JsonObject m) {
        if (m.has("logo") && !m.get("logo").isJsonNull()) {
            JsonObject logo = m.getAsJsonObject("logo");
            if (logo.has("url")) return logo.get("url").getAsString();
        }
        return "";
    }

    private String get(String url) throws Exception {
        HttpResponse<String> r = http.send(
                HttpRequest.newBuilder(URI.create(url))
                        .header("x-api-key", apiKey)
                        .header("Accept", "application/json")
                        .header("User-Agent", "KanoLauncher/1.0").GET().build(),
                HttpResponse.BodyHandlers.ofString());
        if (r.statusCode() == 403) {
            String body = r.body() == null ? "" : r.body().strip();
            throw new RuntimeException("CurseForge rejected the request (403). The key must be a Minecraft "
                    + "API key from console.curseforge.com → API Keys (their API is gated). "
                    + (body.isEmpty() ? "" : "CF said: " + (body.length() > 200 ? body.substring(0, 200) : body)));
        }
        if (r.statusCode() != 200) {
            String body = r.body() == null ? "" : r.body().strip();
            throw new RuntimeException("CurseForge " + r.statusCode()
                    + (body.isEmpty() ? "" : ": " + (body.length() > 200 ? body.substring(0, 200) : body)));
        }
        return r.body();
    }

    private static String str(JsonObject o, String k) {
        return o.has(k) && !o.get(k).isJsonNull() ? o.get(k).getAsString() : "";
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }
}
