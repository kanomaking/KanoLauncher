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
    private String themeAccent = "#D32F2F";
    private double bgScale = 540;
    private double bgOpacity = 0.30;
    private int defaultRamMb = 4096;
    private String defaultLoader = "FABRIC";
    private boolean minimizeOnPlay = false;
    private boolean confirmDelete = true;
    private boolean animations = true;

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

    public String themeAccent() {
        return themeAccent == null || themeAccent.isBlank() ? "#D32F2F" : themeAccent;
    }

    public void setThemeAccent(String hex) {
        this.themeAccent = hex == null || hex.isBlank() ? "#D32F2F" : hex.trim();
        save();
    }

    public double bgScale() { return bgScale <= 0 ? 540 : bgScale; }

    public void setBgScale(double v) { this.bgScale = v; save(); }

    public double bgOpacity() { return bgOpacity < 0 ? 0.30 : bgOpacity; }

    public void setBgOpacity(double v) { this.bgOpacity = v; save(); }

    public int defaultRamMb() { return defaultRamMb <= 0 ? 4096 : defaultRamMb; }

    public void setDefaultRamMb(int v) { this.defaultRamMb = v; save(); }

    public String defaultLoader() { return defaultLoader == null || defaultLoader.isBlank() ? "FABRIC" : defaultLoader; }

    public void setDefaultLoader(String v) { this.defaultLoader = v == null || v.isBlank() ? "FABRIC" : v; save(); }

    public boolean minimizeOnPlay() { return minimizeOnPlay; }

    public void setMinimizeOnPlay(boolean v) { this.minimizeOnPlay = v; save(); }

    public boolean confirmDelete() { return confirmDelete; }

    public void setConfirmDelete(boolean v) { this.confirmDelete = v; save(); }

    public boolean animations() { return animations; }

    public void setAnimations(boolean v) { this.animations = v; save(); }

    private void load() {
        try {
            if (Files.exists(file)) {
                JsonObject o = new Gson().fromJson(Files.readString(file, StandardCharsets.UTF_8), JsonObject.class);
                if (o != null && o.has("curseforgeApiKey")) curseforgeApiKey = o.get("curseforgeApiKey").getAsString();
                if (o != null && o.has("activeUuid")) activeUuid = o.get("activeUuid").getAsString();
                if (o != null && o.has("launcherName")) launcherName = o.get("launcherName").getAsString();
                if (o != null && o.has("themeKey")) themeKey = o.get("themeKey").getAsString();
                if (o != null && o.has("themeAccent")) themeAccent = o.get("themeAccent").getAsString();
                if (o != null && o.has("bgScale")) bgScale = o.get("bgScale").getAsDouble();
                if (o != null && o.has("bgOpacity")) bgOpacity = o.get("bgOpacity").getAsDouble();
                if (o != null && o.has("defaultRamMb")) defaultRamMb = o.get("defaultRamMb").getAsInt();
                if (o != null && o.has("defaultLoader")) defaultLoader = o.get("defaultLoader").getAsString();
                if (o != null && o.has("minimizeOnPlay")) minimizeOnPlay = o.get("minimizeOnPlay").getAsBoolean();
                if (o != null && o.has("confirmDelete")) confirmDelete = o.get("confirmDelete").getAsBoolean();
                if (o != null && o.has("animations")) animations = o.get("animations").getAsBoolean();
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
            o.addProperty("themeAccent", themeAccent);
            o.addProperty("bgScale", bgScale);
            o.addProperty("bgOpacity", bgOpacity);
            o.addProperty("defaultRamMb", defaultRamMb);
            o.addProperty("defaultLoader", defaultLoader);
            o.addProperty("minimizeOnPlay", minimizeOnPlay);
            o.addProperty("confirmDelete", confirmDelete);
            o.addProperty("animations", animations);
            Path tmp = file.resolveSibling("config.json.tmp");
            Files.writeString(tmp, new Gson().toJson(o), StandardCharsets.UTF_8);
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
        } catch (Exception ignored) {
        }
    }
}
