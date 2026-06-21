package com.kano.launcher.core;

import com.google.gson.Gson;
import com.google.gson.JsonObject;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/** Global launcher settings (config.json). Currently just the user's CurseForge API key. */
public final class Config {

    private final Path file;
    private String curseforgeApiKey = "";

    public Config(Path dataDir) {
        this.file = dataDir.resolve("config.json");
        load();
    }

    public String curseforgeApiKey() {
        return curseforgeApiKey == null ? "" : curseforgeApiKey;
    }

    public void setCurseforgeApiKey(String key) {
        this.curseforgeApiKey = key == null ? "" : key.trim();
        save();
    }

    private void load() {
        try {
            if (Files.exists(file)) {
                JsonObject o = new Gson().fromJson(Files.readString(file, StandardCharsets.UTF_8), JsonObject.class);
                if (o != null && o.has("curseforgeApiKey")) curseforgeApiKey = o.get("curseforgeApiKey").getAsString();
            }
        } catch (Exception ignored) {
        }
    }

    private void save() {
        try {
            JsonObject o = new JsonObject();
            o.addProperty("curseforgeApiKey", curseforgeApiKey);
            Path tmp = file.resolveSibling("config.json.tmp");
            Files.writeString(tmp, new Gson().toJson(o), StandardCharsets.UTF_8);
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
        } catch (Exception ignored) {
        }
    }
}
