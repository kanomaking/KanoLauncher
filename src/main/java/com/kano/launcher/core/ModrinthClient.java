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
 * Minimal Modrinth API v2 client: search projects and list compatible versions, filtered by the
 * instance's exact Minecraft version + loader. Sends the required descriptive User-Agent.
 */
public final class ModrinthClient {

    private static final String API = "https://api.modrinth.com/v2/";
    // Modrinth requires a unique, contactable User-Agent.
    private static final String UA = "ChaosCraft/KanoLauncher/1.0 (kanomaking.em@gmail.com)";

    public record Hit(String projectId, String slug, String title, String description,
                      int downloads, String iconUrl, String projectType) {}
    public record ModFile(String url, String filename, boolean primary) {}
    public record Dep(String projectId, String type) {}
    public record ModVersion(String id, String name, List<ModFile> files, List<Dep> dependencies) {}

    private final HttpClient http = HttpClient.newHttpClient();
    private final Gson gson = new Gson();

    /** Search projects compatible with the given version+loader. projectType e.g. "mod", "modpack", "shader". */
    public List<Hit> search(String query, String gameVersion, String loader, String projectType) throws Exception {
        String facets = "[[\"project_type:" + projectType + "\"],[\"versions:" + gameVersion + "\"]"
                + (loader != null && !loader.isBlank() ? ",[\"categories:" + loader + "\"]" : "") + "]";
        String url = API + "search?limit=30&index=relevance"
                + "&query=" + enc(query == null ? "" : query)
                + "&facets=" + enc(facets);
        JsonObject root = gson.fromJson(get(url), JsonObject.class);
        List<Hit> out = new ArrayList<>();
        for (JsonElement el : root.getAsJsonArray("hits")) {
            JsonObject h = el.getAsJsonObject();
            out.add(new Hit(
                    str(h, "project_id"), str(h, "slug"), str(h, "title"), str(h, "description"),
                    h.has("downloads") ? h.get("downloads").getAsInt() : 0,
                    str(h, "icon_url"), str(h, "project_type")));
        }
        return out;
    }

    /** Compatible versions for a project, newest first. */
    public List<ModVersion> versions(String idOrSlug, String gameVersion, String loader) throws Exception {
        String url = API + "project/" + idOrSlug + "/version"
                + "?game_versions=" + enc("[\"" + gameVersion + "\"]")
                + (loader != null && !loader.isBlank() ? "&loaders=" + enc("[\"" + loader + "\"]") : "");
        JsonArray arr = gson.fromJson(get(url), JsonArray.class);
        List<ModVersion> out = new ArrayList<>();
        for (JsonElement el : arr) {
            JsonObject v = el.getAsJsonObject();
            List<ModFile> files = new ArrayList<>();
            for (JsonElement fe : v.getAsJsonArray("files")) {
                JsonObject f = fe.getAsJsonObject();
                files.add(new ModFile(f.get("url").getAsString(), f.get("filename").getAsString(),
                        f.has("primary") && f.get("primary").getAsBoolean()));
            }
            List<Dep> deps = new ArrayList<>();
            if (v.has("dependencies")) {
                for (JsonElement de : v.getAsJsonArray("dependencies")) {
                    JsonObject d = de.getAsJsonObject();
                    if (d.has("project_id") && !d.get("project_id").isJsonNull()) {
                        deps.add(new Dep(d.get("project_id").getAsString(),
                                d.has("dependency_type") ? d.get("dependency_type").getAsString() : "required"));
                    }
                }
            }
            out.add(new ModVersion(v.get("id").getAsString(),
                    v.has("name") ? v.get("name").getAsString() : "", files, deps));
        }
        return out;
    }

    private String get(String url) throws Exception {
        HttpResponse<String> r = http.send(
                HttpRequest.newBuilder(URI.create(url)).header("User-Agent", UA).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        if (r.statusCode() != 200) throw new RuntimeException("Modrinth " + r.statusCode() + ": " + url);
        return r.body();
    }

    private static String str(JsonObject o, String k) {
        return o.has(k) && !o.get(k).isJsonNull() ? o.get(k).getAsString() : "";
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }
}
