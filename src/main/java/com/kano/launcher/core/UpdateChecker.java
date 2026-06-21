package com.kano.launcher.core;

import com.google.gson.Gson;
import com.google.gson.JsonObject;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

/**
 * Checks the latest GitHub release of the launcher and reports whether a newer version exists.
 *
 * <p>Notification only — it does not download or replace anything (on Windows the running app is
 * file-locked while open, so a live in-place replace would fail). A future two-process bootstrapper
 * (tiny updater that waits for the launcher to exit, swaps files, relaunches) is the path for real
 * self-update; until then this points the user at the releases page.
 */
public final class UpdateChecker {

    public static final String REPO = "kanomaking/KanoLauncher";
    private static final String LATEST_RELEASE_API =
            "https://api.github.com/repos/" + REPO + "/releases/latest";

    private UpdateChecker() {}

    /** {@code newer} is true when {@code latestTag} > {@code currentVersion}. */
    public record Result(boolean newer, String currentVersion, String latestTag, String htmlUrl) {}

    public static String releasesPageUrl() {
        return "https://github.com/" + REPO + "/releases";
    }

    /** Hits the GitHub latest-release endpoint and compares its tag to {@code currentVersion}. */
    public static Result check(String currentVersion) throws Exception {
        HttpClient http = HttpClient.newHttpClient();
        HttpRequest req = HttpRequest.newBuilder(URI.create(LATEST_RELEASE_API))
                .header("Accept", "application/vnd.github+json")
                .header("User-Agent", "KanoLauncher")
                .GET().build();
        HttpResponse<String> r = http.send(req, HttpResponse.BodyHandlers.ofString());
        if (r.statusCode() != 200) throw new RuntimeException("GitHub release check failed: " + r.statusCode());

        JsonObject root = new Gson().fromJson(r.body(), JsonObject.class);
        String tag = root.has("tag_name") && !root.get("tag_name").isJsonNull()
                ? root.get("tag_name").getAsString() : "";
        String url = root.has("html_url") && !root.get("html_url").isJsonNull()
                ? root.get("html_url").getAsString() : releasesPageUrl();
        if (tag.isBlank()) throw new RuntimeException("Release has no tag_name.");
        return new Result(compare(tag, currentVersion) > 0, currentVersion, tag, url);
    }

    /** Compares version strings (tolerates leading 'v', differing segment counts, suffixes). >0 if a newer than b. */
    public static int compare(String a, String b) {
        int[] pa = parse(a);
        int[] pb = parse(b);
        int n = Math.max(pa.length, pb.length);
        for (int i = 0; i < n; i++) {
            int va = i < pa.length ? pa[i] : 0;
            int vb = i < pb.length ? pb[i] : 0;
            if (va != vb) return Integer.compare(va, vb);
        }
        return 0;
    }

    private static int[] parse(String v) {
        if (v == null) return new int[0];
        String s = v.trim();
        if (s.startsWith("v") || s.startsWith("V")) s = s.substring(1);
        int dash = s.indexOf('-');
        if (dash >= 0) s = s.substring(0, dash);
        String[] parts = s.split("\\.");
        int[] out = new int[parts.length];
        for (int i = 0; i < parts.length; i++) {
            String digits = parts[i].replaceAll("[^0-9].*$", "");
            out[i] = digits.isEmpty() ? 0 : Integer.parseInt(digits);
        }
        return out;
    }
}
