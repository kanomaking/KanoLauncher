package com.kano.launcher.core;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.reflect.TypeToken;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;

/** Global launcher settings (config.json). */
public final class Config {

    /** One coloured run of the launcher name (for the multi-colour brand). */
    public record NameSegment(String text, String color) {}

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
    private boolean autoNeoForge = true;
    // Open the launcher maximized by default. Workaround for the windowed-mode top-bar issue
    // (launching behind a foreground window covers the title bar); maximized fills the work area
    // and covers everything, so the title bar is always visible. Default ON until the windowed
    // foreground/z-order behaviour is fully solved.
    private boolean startMaximized = false; // standard OS window: windowed works reliably now
    private List<NameSegment> nameSegments = new ArrayList<>();

    public Config(Path dataDir) {
        this.file = dataDir.resolve("config.json");
        load();
    }

    /**
     * The CurseForge key to use: the per-user key from settings if set, otherwise a key bundled into
     * the build ({@code /cf-key.txt} on the classpath). Bundling lets you ship the app to friends with
     * CurseForge working out of the box — drop your key into {@code src/main/resources/cf-key.txt}
     * before building the distributable (that file is gitignored so it never lands in source control).
     */
    public String curseforgeApiKey() {
        if (curseforgeApiKey != null && !curseforgeApiKey.isBlank()) return curseforgeApiKey;
        return bundledCfKey();
    }

    /** The user's own key from settings only (blank if they haven't set one) — for the settings field. */
    public String userCurseforgeApiKey() {
        return curseforgeApiKey == null ? "" : curseforgeApiKey;
    }

    /** True when a CurseForge key is baked into this build. */
    public boolean hasBundledCfKey() {
        return !bundledCfKey().isBlank();
    }

    private static String cachedBundledKey;
    private static String bundledCfKey() {
        if (cachedBundledKey != null) return cachedBundledKey;
        String key = "";
        try (var in = Config.class.getResourceAsStream("/cf-key.txt")) {
            if (in != null) {
                key = new String(in.readAllBytes(), StandardCharsets.UTF_8)
                        .replace("﻿", "") // strip a UTF-8 BOM (Notepad adds one)
                        .trim();
            }
        } catch (Exception ignored) {
        }
        cachedBundledKey = key;
        return key;
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

    /** When true, picking Forge for a NeoForge-era version offers to switch to NeoForge. */
    public boolean autoNeoForge() { return autoNeoForge; }

    public void setAutoNeoForge(boolean v) { this.autoNeoForge = v; save(); }

    /** When true, the launcher opens maximized (work-area-filling) — sidesteps the windowed top-bar issue. */
    public boolean startMaximized() { return startMaximized; }

    public void setStartMaximized(boolean v) { this.startMaximized = v; save(); }

    /** Coloured runs of the brand name; defaults to the whole name in white if unset. */
    public List<NameSegment> nameSegments() {
        if (nameSegments == null || nameSegments.isEmpty())
            return List.of(new NameSegment(launcherName(), "#FFFFFF"));
        return List.copyOf(nameSegments);
    }

    public void setNameSegments(List<NameSegment> segs) {
        this.nameSegments = segs == null ? new ArrayList<>() : new ArrayList<>(segs);
        StringBuilder sb = new StringBuilder();
        for (NameSegment s : this.nameSegments) if (s.text() != null) sb.append(s.text());
        if (sb.length() > 0) this.launcherName = sb.toString(); // keep the plain name (OS title) in sync
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
                if (o != null && o.has("themeAccent")) themeAccent = o.get("themeAccent").getAsString();
                if (o != null && o.has("bgScale")) bgScale = o.get("bgScale").getAsDouble();
                if (o != null && o.has("bgOpacity")) bgOpacity = o.get("bgOpacity").getAsDouble();
                if (o != null && o.has("defaultRamMb")) defaultRamMb = o.get("defaultRamMb").getAsInt();
                if (o != null && o.has("defaultLoader")) defaultLoader = o.get("defaultLoader").getAsString();
                if (o != null && o.has("minimizeOnPlay")) minimizeOnPlay = o.get("minimizeOnPlay").getAsBoolean();
                if (o != null && o.has("confirmDelete")) confirmDelete = o.get("confirmDelete").getAsBoolean();
                if (o != null && o.has("animations")) animations = o.get("animations").getAsBoolean();
                if (o != null && o.has("autoNeoForge")) autoNeoForge = o.get("autoNeoForge").getAsBoolean();
                if (o != null && o.has("startMaximized")) startMaximized = o.get("startMaximized").getAsBoolean();
                if (o != null && o.has("nameSegments")) {
                    nameSegments = new Gson().fromJson(o.get("nameSegments"),
                            new TypeToken<ArrayList<NameSegment>>() {}.getType());
                    if (nameSegments == null) nameSegments = new ArrayList<>();
                }
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
            o.addProperty("autoNeoForge", autoNeoForge);
            o.addProperty("startMaximized", startMaximized);
            o.add("nameSegments", new Gson().toJsonTree(nameSegments));
            Path tmp = file.resolveSibling("config.json.tmp");
            Files.writeString(tmp, new Gson().toJson(o), StandardCharsets.UTF_8);
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
        } catch (Exception ignored) {
        }
    }
}
