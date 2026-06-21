package com.kano.launcher.core;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Assembles the launch command (classpath, JVM + game args with placeholder substitution, extracted
 * natives) and starts the game process. Phase 1 launches in OFFLINE mode (no auth required) so it
 * works before Microsoft approves the app; a real session is dropped in once login works.
 */
public final class GameLauncher {

    private GameLauncher() {}

    public static Process launch(Instance inst, VersionDetail vd, Path javaExe, Path dataDir,
                                 String playerName) throws Exception {
        Path libsDir = GameInstaller.librariesDir(dataDir);
        Path assets = GameInstaller.assetsDir(dataDir);
        Path clientJar = GameInstaller.clientJar(dataDir, vd.id());
        Path gameDir = dataDir.resolve("instances").resolve(inst.dirName());
        Path nativesDir = dataDir.resolve("cache").resolve("natives").resolve(vd.id());
        Files.createDirectories(gameDir);

        extractNatives(vd, libsDir, nativesDir);

        // Classpath = all library jars + the client jar.
        List<String> cp = new ArrayList<>();
        for (VersionDetail.Dl lib : vd.libraries()) cp.add(libsDir.resolve(lib.path()).toString());
        cp.add(clientJar.toString());
        String classpath = String.join(File.pathSeparator, cp);

        String name = (playerName == null || playerName.isBlank()) ? "Player" : playerName;
        String uuid = UUID.nameUUIDFromBytes(("OfflinePlayer:" + name).getBytes(StandardCharsets.UTF_8))
                .toString().replace("-", "");

        Map<String, String> subs = new HashMap<>();
        subs.put("auth_player_name", name);
        subs.put("version_name", vd.id());
        subs.put("game_directory", gameDir.toString());
        subs.put("assets_root", assets.toString());
        subs.put("game_assets", assets.toString());
        subs.put("assets_index_name", vd.assetIndexId());
        subs.put("auth_uuid", uuid);
        subs.put("auth_access_token", "0");
        subs.put("auth_session", "0");
        subs.put("auth_xuid", "0");
        subs.put("clientid", "0");
        subs.put("user_type", "legacy");
        subs.put("user_properties", "{}");
        subs.put("version_type", "release");
        subs.put("natives_directory", nativesDir.toString());
        subs.put("launcher_name", "KanoLauncher");
        subs.put("launcher_version", "1.0");
        subs.put("classpath", classpath);

        List<String> jvmArgs = new ArrayList<>();
        List<String> gameArgs = new ArrayList<>();
        JsonObject raw = vd.raw();
        if (raw.has("arguments")) {
            JsonObject a = raw.getAsJsonObject("arguments");
            if (a.has("jvm")) collectArgs(a.getAsJsonArray("jvm"), subs, jvmArgs);
            if (a.has("game")) collectArgs(a.getAsJsonArray("game"), subs, gameArgs);
        } else {
            // Legacy (pre-1.13): no jvm args block; build the essentials ourselves.
            jvmArgs.add("-Djava.library.path=" + nativesDir);
            jvmArgs.add("-cp");
            jvmArgs.add(classpath);
            String mc = raw.has("minecraftArguments") ? raw.get("minecraftArguments").getAsString() : "";
            for (String tok : mc.split(" ")) if (!tok.isBlank()) gameArgs.add(sub(tok, subs));
        }

        List<String> command = new ArrayList<>();
        command.add(javaExe.toString());
        command.add("-Xmx" + inst.ramMb() + "m");
        command.add("-Xms" + Math.min(512, inst.ramMb()) + "m");
        command.addAll(jvmArgs);
        command.add(vd.mainClass());
        command.addAll(gameArgs);

        Path log = gameDir.resolve("launcher-run.log");
        ProcessBuilder pb = new ProcessBuilder(command);
        pb.directory(gameDir.toFile());
        pb.redirectErrorStream(true);
        pb.redirectOutput(log.toFile());
        return pb.start();
    }

    private static void extractNatives(VersionDetail vd, Path libsDir, Path nativesDir) throws Exception {
        Files.createDirectories(nativesDir);
        for (VersionDetail.Dl nat : vd.natives()) {
            Path jar = libsDir.resolve(nat.path());
            if (!Files.exists(jar)) continue;
            try (ZipInputStream zis = new ZipInputStream(Files.newInputStream(jar))) {
                ZipEntry e;
                while ((e = zis.getNextEntry()) != null) {
                    if (e.isDirectory()) continue;
                    String n = e.getName();
                    if (n.startsWith("META-INF")) continue;
                    String low = n.toLowerCase(Locale.ROOT);
                    if (!(low.endsWith(".dll") || low.endsWith(".so") || low.endsWith(".dylib")
                            || low.endsWith(".jnilib"))) continue;
                    Path out = nativesDir.resolve(Path.of(n).getFileName().toString());
                    Files.copy(zis, out, StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
    }

    private static void collectArgs(JsonArray arr, Map<String, String> subs, List<String> out) {
        for (JsonElement el : arr) {
            if (el.isJsonPrimitive()) {
                out.add(sub(el.getAsString(), subs));
            } else if (el.isJsonObject()) {
                JsonObject o = el.getAsJsonObject();
                boolean ok = !o.has("rules") || rulesAllow(o.getAsJsonArray("rules"));
                if (!ok) continue;
                JsonElement val = o.get("value");
                if (val.isJsonPrimitive()) {
                    out.add(sub(val.getAsString(), subs));
                } else if (val.isJsonArray()) {
                    for (JsonElement v : val.getAsJsonArray()) out.add(sub(v.getAsString(), subs));
                }
            }
        }
    }

    /** Allow only os-gated args for this OS; skip anything gated on a feature (we enable none). */
    private static boolean rulesAllow(JsonArray rules) {
        boolean allowed = false;
        String osTag = VersionDetail.osTag();
        for (JsonElement el : rules) {
            JsonObject rule = el.getAsJsonObject();
            boolean matches = true;
            if (rule.has("features")) matches = false;
            if (rule.has("os")) {
                JsonObject os = rule.getAsJsonObject("os");
                if (os.has("name")) matches = matches && os.get("name").getAsString().equals(osTag);
            }
            if (matches) allowed = "allow".equals(rule.get("action").getAsString());
        }
        return allowed;
    }

    private static String sub(String s, Map<String, String> subs) {
        for (Map.Entry<String, String> e : subs.entrySet()) {
            s = s.replace("${" + e.getKey() + "}", e.getValue());
        }
        return s;
    }
}
