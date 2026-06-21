package com.kano.launcher.core;

import com.google.gson.Gson;
import com.google.gson.JsonObject;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.stream.Stream;

/**
 * Lightweight usage stats shown at the bottom of the launcher: total play time and launch count,
 * persisted to stats.json. World count is computed live from each instance's saves/ folder.
 */
public final class Stats {

    private final Path file;
    private long totalPlayMillis;
    private int launches;

    public Stats(Path dataDir) {
        this.file = dataDir.resolve("stats.json");
        load();
    }

    public long totalPlayMillis() { return totalPlayMillis; }
    public int launches() { return launches; }

    /** Record one game session. */
    public void addSession(long millis) {
        if (millis < 0) millis = 0;
        totalPlayMillis += millis;
        launches++;
        save();
    }

    /** Count world folders across all instances' saves/ directories. */
    public static int countWorlds(Path instancesDir) {
        if (!Files.isDirectory(instancesDir)) return 0;
        int n = 0;
        try (Stream<Path> insts = Files.list(instancesDir)) {
            for (Path inst : (Iterable<Path>) insts::iterator) {
                Path saves = inst.resolve("saves");
                if (Files.isDirectory(saves)) {
                    try (Stream<Path> worlds = Files.list(saves)) {
                        n += (int) worlds.filter(Files::isDirectory).count();
                    }
                }
            }
        } catch (Exception ignored) {
        }
        return n;
    }

    /** "12h 34m" style. */
    public String playtimeText() {
        long totalMin = totalPlayMillis / 60000;
        long h = totalMin / 60;
        long m = totalMin % 60;
        return h + "h " + m + "m";
    }

    private void load() {
        try {
            if (Files.exists(file)) {
                JsonObject o = new Gson().fromJson(Files.readString(file, StandardCharsets.UTF_8), JsonObject.class);
                if (o != null) {
                    if (o.has("totalPlayMillis")) totalPlayMillis = o.get("totalPlayMillis").getAsLong();
                    if (o.has("launches")) launches = o.get("launches").getAsInt();
                }
            }
        } catch (Exception ignored) {
        }
    }

    private void save() {
        try {
            JsonObject o = new JsonObject();
            o.addProperty("totalPlayMillis", totalPlayMillis);
            o.addProperty("launches", launches);
            Path tmp = file.resolveSibling("stats.json.tmp");
            Files.writeString(tmp, new Gson().toJson(o), StandardCharsets.UTF_8);
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
        } catch (Exception ignored) {
        }
    }
}
