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
 * Modrinth API v2 client: search (sortable, paginated), project details, and compatible versions —
 * filtered by the instance's exact Minecraft version (+ loader for mods). Sends the required UA.
 */
public final class ModrinthClient {

    private static final String API = "https://api.modrinth.com/v2/";
    private static final String UA = "ChaosCraft/KanoLauncher/1.0 (kanomaking.em@gmail.com)";

    public record Hit(String projectId, String slug, String title, String description,
                      int downloads, String iconUrl, String projectType) {}
    public record SearchResult(List<Hit> hits, int totalHits) {}
    public record ProjectDetail(String title, String summary, String body, int downloads, int followers,
                                List<String> categories, String iconUrl, String sourceUrl, String slug) {}
    public record ModFile(String url, String filename, boolean primary) {}
    public record Dep(String projectId, String type) {}
    public record ModVersion(String id, String name, List<ModFile> files, List<Dep> dependencies) {}

    private final HttpClient http = HttpClient.newHttpClient();
    private final Gson gson = new Gson();

    /**
     * @param sort  one of: relevance, downloads, follows, newest, updated
     * @param loader pass null for loader-agnostic types (resource packs, shaders)
     */
    public SearchResult search(String query, String gameVersion, String loader, String projectType,
                               String sort, int offset, int limit) throws Exception {
        String facets = "[[\"project_type:" + projectType + "\"],[\"versions:" + gameVersion + "\"]"
                + (loader != null && !loader.isBlank() ? ",[\"categories:" + loader + "\"]" : "") + "]";
        String url = API + "search?limit=" + limit + "&offset=" + offset
                + "&index=" + sort
                + "&query=" + enc(query == null ? "" : query)
                + "&facets=" + enc(facets);
        JsonObject root = gson.fromJson(get(url), JsonObject.class);
        List<Hit> out = new ArrayList<>();
        for (JsonElement el : root.getAsJsonArray("hits")) {
            JsonObject h = el.getAsJsonObject();
            out.add(new Hit(str(h, "project_id"), str(h, "slug"), str(h, "title"), str(h, "description"),
                    h.has("downloads") ? h.get("downloads").getAsInt() : 0,
                    str(h, "icon_url"), str(h, "project_type")));
        }
        int total = root.has("total_hits") ? root.get("total_hits").getAsInt() : out.size();
        return new SearchResult(out, total);
    }

    public ProjectDetail project(String idOrSlug) throws Exception {
        JsonObject p = gson.fromJson(get(API + "project/" + idOrSlug), JsonObject.class);
        List<String> cats = new ArrayList<>();
        if (p.has("categories")) for (JsonElement c : p.getAsJsonArray("categories")) cats.add(c.getAsString());
        return new ProjectDetail(
                str(p, "title"), str(p, "description"), str(p, "body"),
                p.has("downloads") ? p.get("downloads").getAsInt() : 0,
                p.has("followers") ? p.get("followers").getAsInt() : 0,
                cats, str(p, "icon_url"), str(p, "source_url"), str(p, "slug"));
    }

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
