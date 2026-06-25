package com.kano.launcher.core.games;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.reflect.TypeToken;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Persists the user-owned slice of the library (manually-added games + last-played) to games.json. */
public final class GamesStore {

    private final Path file;
    private final List<Game> manualGames = new ArrayList<>();
    private final Map<String, Long> lastPlayed = new LinkedHashMap<>();
    private static final Gson GSON = new Gson();

    public GamesStore(Path dataDir) {
        this.file = dataDir.resolve("games.json");
        load();
    }

    public synchronized List<Game> manualGames() { return List.copyOf(manualGames); }

    public synchronized void addManual(Game g) {
        manualGames.removeIf(m -> m.key().equals(g.key()));
        manualGames.add(g);
        save();
    }

    public synchronized void removeManual(String key) {
        manualGames.removeIf(m -> m.key().equals(key));
        save();
    }

    public synchronized long lastPlayedFor(String key) {
        Long v = lastPlayed.get(key);
        return v == null ? 0L : v;
    }

    public synchronized void recordPlayed(String key, long epoch) {
        lastPlayed.put(key, epoch);
        save();
    }

    private void load() {
        try {
            if (!Files.exists(file)) return;
            JsonObject o = GSON.fromJson(Files.readString(file, StandardCharsets.UTF_8), JsonObject.class);
            if (o == null) return;
            if (o.has("manualGames")) {
                List<Game> list = GSON.fromJson(o.get("manualGames"),
                        new TypeToken<ArrayList<Game>>() {}.getType());
                if (list != null) { manualGames.clear(); manualGames.addAll(list); }
            }
            if (o.has("lastPlayed")) {
                Map<String, Double> raw = GSON.fromJson(o.get("lastPlayed"),
                        new TypeToken<LinkedHashMap<String, Double>>() {}.getType());
                if (raw != null) raw.forEach((k, v) -> lastPlayed.put(k, v.longValue()));
            }
        } catch (Exception ignored) {
            // Corrupt file: start empty rather than crash the launcher.
        }
    }

    private void save() {
        try {
            JsonObject o = new JsonObject();
            o.add("manualGames", GSON.toJsonTree(manualGames));
            o.add("lastPlayed", GSON.toJsonTree(lastPlayed));
            Files.createDirectories(file.getParent());
            Path tmp = file.resolveSibling("games.json.tmp");
            Files.writeString(tmp, GSON.toJson(o), StandardCharsets.UTF_8);
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
        } catch (Exception ignored) {
        }
    }
}
