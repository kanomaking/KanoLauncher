package com.kano.launcher.core;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;

/**
 * Resolves a Fabric loader install for a given Minecraft version via the Fabric Meta API.
 * Returns the extra classpath libraries, the Fabric main class, and any extra launch args; these
 * are layered on top of the vanilla {@link VersionDetail} at install + launch time.
 */
public final class FabricSupport {

    private static final String META = "https://meta.fabricmc.net/v2/versions/loader/";

    /** Everything Fabric adds on top of vanilla. */
    public record Profile(String loaderVersion, String mainClass,
                          List<VersionDetail.Dl> libraries,
                          List<String> jvmArgs, List<String> gameArgs) {}

    private FabricSupport() {}

    public static Profile resolve(String gameVersion) throws Exception {
        HttpClient http = HttpClient.newHttpClient();
        Gson gson = new Gson();

        // Pick the recommended (first stable) loader for this game version.
        JsonArray loaders = gson.fromJson(get(http, META + gameVersion), JsonArray.class);
        if (loaders == null || loaders.isEmpty())
            throw new RuntimeException("No Fabric loader available for " + gameVersion);
        String loaderVersion = null;
        for (JsonElement el : loaders) {
            JsonObject loader = el.getAsJsonObject().getAsJsonObject("loader");
            if (loader.has("stable") && loader.get("stable").getAsBoolean()) {
                loaderVersion = loader.get("version").getAsString();
                break;
            }
        }
        if (loaderVersion == null)
            loaderVersion = loaders.get(0).getAsJsonObject().getAsJsonObject("loader").get("version").getAsString();

        // Full profile JSON (vanilla-like) describing the Fabric install.
        JsonObject profile = gson.fromJson(
                get(http, META + gameVersion + "/" + loaderVersion + "/profile/json"), JsonObject.class);

        String mainClass = profile.get("mainClass").getAsString();

        List<VersionDetail.Dl> libs = new ArrayList<>();
        for (JsonElement el : profile.getAsJsonArray("libraries")) {
            JsonObject lib = el.getAsJsonObject();
            String name = lib.get("name").getAsString();
            String base = lib.has("url") ? lib.get("url").getAsString() : "https://maven.fabricmc.net/";
            if (!base.endsWith("/")) base += "/";
            String path = mavenPath(name);
            String sha1 = lib.has("sha1") ? lib.get("sha1").getAsString() : null;
            libs.add(new VersionDetail.Dl(base + path, sha1, 0, path));
        }

        List<String> jvm = new ArrayList<>();
        List<String> game = new ArrayList<>();
        if (profile.has("arguments")) {
            JsonObject args = profile.getAsJsonObject("arguments");
            if (args.has("jvm")) for (JsonElement e : args.getAsJsonArray("jvm")) if (e.isJsonPrimitive()) jvm.add(e.getAsString());
            if (args.has("game")) for (JsonElement e : args.getAsJsonArray("game")) if (e.isJsonPrimitive()) game.add(e.getAsString());
        }
        return new Profile(loaderVersion, mainClass, libs, jvm, game);
    }

    /** Maven coordinate "g:a:v[:classifier]" -> repo-relative path. */
    static String mavenPath(String name) {
        String[] p = name.split(":");
        String group = p[0].replace('.', '/');
        String artifact = p[1];
        String version = p[2];
        String classifier = p.length > 3 ? "-" + p[3] : "";
        return group + "/" + artifact + "/" + version + "/" + artifact + "-" + version + classifier + ".jar";
    }

    private static String get(HttpClient http, String url) throws Exception {
        HttpResponse<String> r = http.send(
                HttpRequest.newBuilder(URI.create(url)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        if (r.statusCode() != 200) throw new RuntimeException("Fabric meta " + r.statusCode() + ": " + url);
        return r.body();
    }
}
