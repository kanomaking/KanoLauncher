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
    private String activeUuid = "";
    private String launcherName = "KanoLauncher";
    private String themeKey = "crimson";

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

    /** UUID of the account the user picked as active in the avatar bar (empty = none chosen yet). */
    public String activeUuid() {
        return activeUuid == null ? "" : activeUuid;
    }

    public void setActiveUuid(String uuid) {
        this.activeUuid = uuid == null ? "" : uuid.trim();
        save();
    }

    public String launcherName() {
        return launcherName == null || launcherName.isBlank() ? "KanoLauncher" : launcherName;
    }

    public void setLauncherName(String name) {
        this.launcherName = name == null || name.isBlank() ? "KanoLauncher" : name.trim();
        save();
    }

    public String themeKey() {
        return themeKey == null || themeKey.isBlank() ? "crimson" : themeKey;
    }

    public void setThemeKey(String key) {
        this.themeKey = key == null || key.isBlank() ? "crimson" : key.trim();
        save();
    }

    private void load() {
        try {
            if (Files.exists(file)) {
                JsonObject o = new Gson().fromJson(Files.readString(file, StandardCharsets.UTF_8), JsonObject.class);
                if (o != null && o.has("curseforgeApiKey")) curseforgeApiKey = o.get("curseforgeApiKey").getAsString();
                if (o != null && o.has("activeUuid")) activeUuid = o.get("activeUuid").getAsString();
                if (o != null && o.has("launcherName")) launcherName = o.get("launcherName").getAsString();
                if (o != null && o.has("themeKey")) themeKey = o.get("themeKey").getAsString();
            }
        } catch (Exception ignored) {
        }
    }

    private void save() {
        try {
            JsonObject o = new JsonObject();
            o.addProperty("curseforgeApiKey", curseforgeApiKey);
            o.addProperty("activeUuid", activeUuid);
            o.addProperty("launcherName", launcherName);
            o.addProperty("themeKey", themeKey);
            Path tmp = file.resolveSibling("config.json.tmp");
            Files.writeString(tmp, new Gson().toJson(o), StandardCharsets.UTF_8);
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
        } catch (Exception ignored) {
        }
    }
}
