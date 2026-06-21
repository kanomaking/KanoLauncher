package com.kano.launcher.core;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Resolves the newest NeoForge / Forge build for a given Minecraft version from each project's
 * Maven metadata. NeoForge versions look like {@code 21.1.73} (MC {@code 1.21.1} → prefix
 * {@code 21.1.}); Forge versions look like {@code 1.21.1-52.0.63} (full {@code mc-forge}).
 */
public final class ForgeVersions {

    private static final String NEOFORGE_META =
            "https://maven.neoforged.net/releases/net/neoforged/neoforge/maven-metadata.xml";
    private static final String FORGE_META =
            "https://maven.minecraftforge.net/net/minecraftforge/forge/maven-metadata.xml";

    private static final Pattern VERSION = Pattern.compile("<version>([^<]+)</version>");

    private ForgeVersions() {}

    /** NeoForge build version (e.g. {@code 21.1.73}) for an MC version, or null if none. */
    public static String latestNeoForge(String mcVersion) throws Exception {
        String prefix = neoForgePrefix(mcVersion);
        if (prefix == null) return null;
        String best = null;
        for (String v : versionsFrom(NEOFORGE_META)) {
            if (v.startsWith(prefix) && !v.toLowerCase().contains("beta")) {
                if (best == null || compareVersions(v, best) > 0) best = v;
            }
        }
        // Fall back to including beta builds if no stable build exists for this MC version.
        if (best == null) {
            for (String v : versionsFrom(NEOFORGE_META)) {
                if (v.startsWith(prefix) && (best == null || compareVersions(v, best) > 0)) best = v;
            }
        }
        return best;
    }

    /** Forge build version, full {@code mc-forge} string (e.g. {@code 1.21.1-52.0.63}), or null. */
    public static String latestForge(String mcVersion) throws Exception {
        String prefix = mcVersion + "-";
        String best = null;
        for (String v : versionsFrom(FORGE_META)) {
            if (v.startsWith(prefix)) {
                if (best == null || compareVersions(forgePart(v), forgePart(best)) > 0) best = v;
            }
        }
        return best;
    }

    /**
     * True when NeoForge is the better loader choice for this MC version. NeoForge forked at 1.20.2
     * and most Forge-family performance mods (Embeddium, Lithium, C2ME, …) moved there afterwards, so
     * Forge on 1.20.2+ is performance-starved. 1.20.1 and earlier keep a healthy Forge mod scene.
     */
    public static boolean neoForgePreferred(String mcVersion) {
        try { return compareVersions(mcVersion, "1.20.2") >= 0; }
        catch (Exception e) { return false; }
    }

    /** MC "1.21.1" → "21.1.", "1.21" → "21.0.", "1.20.4" → "20.4." */
    static String neoForgePrefix(String mcVersion) {
        String[] p = mcVersion.split("\\.");
        if (p.length < 2 || !p[0].equals("1")) return null;
        String minor = p.length > 2 ? p[2] : "0";
        return p[1] + "." + minor + ".";
    }

    private static String forgePart(String full) {
        int dash = full.indexOf('-');
        return dash >= 0 ? full.substring(dash + 1) : full;
    }

    private static List<String> versionsFrom(String metaUrl) throws Exception {
        HttpResponse<String> r = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create(metaUrl)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        if (r.statusCode() != 200) throw new RuntimeException("Metadata " + r.statusCode() + ": " + metaUrl);
        List<String> out = new ArrayList<>();
        Matcher m = VERSION.matcher(r.body());
        while (m.find()) out.add(m.group(1));
        return out;
    }

    /** Numeric-aware compare of dotted versions ("21.1.9" < "21.1.73"). */
    static int compareVersions(String a, String b) {
        String[] pa = a.split("[.\\-]"), pb = b.split("[.\\-]");
        int n = Math.max(pa.length, pb.length);
        for (int i = 0; i < n; i++) {
            long va = i < pa.length ? parse(pa[i]) : 0;
            long vb = i < pb.length ? parse(pb[i]) : 0;
            if (va != vb) return Long.compare(va, vb);
        }
        return 0;
    }

    private static long parse(String s) {
        try { return Long.parseLong(s.replaceAll("\\D", "").isEmpty() ? "0" : s.replaceAll("\\D", "")); }
        catch (Exception e) { return 0; }
    }
}
