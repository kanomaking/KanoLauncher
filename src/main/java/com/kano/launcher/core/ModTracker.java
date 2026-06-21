package com.kano.launcher.core;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;

import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;

/**
 * Records what content was installed into an instance (per project) so the launcher can later
 * update it. Stored as {@code <instanceDir>/.kano-mods.json}. Keyed by projectId; one entry per
 * top-level installed project (dependencies ride along on re-install).
 */
public final class ModTracker {

    /**
     * @param projectId source project id/slug
     * @param source    "modrinth" or "curseforge"
     * @param type      mod / resourcepack / shader / …
     * @param folder    instance subfolder it lives in (mods, resourcepacks, shaderpacks, …)
     * @param filename  the installed file name (for replace-on-update)
     * @param name      display name
     */
    public record Entry(String projectId, String source, String type, String folder,
                        String filename, String name) {}

    private static final Type LIST_TYPE = new TypeToken<ArrayList<Entry>>() {}.getType();

    private final Path file;
    private final Gson gson = new GsonBuilder().setPrettyPrinting().create();
    private final List<Entry> entries;

    public ModTracker(Path instanceDir) {
        this.file = instanceDir.resolve(".kano-mods.json");
        this.entries = load();
    }

    public List<Entry> list() {
        return List.copyOf(entries);
    }

    public void record(Entry e) {
        entries.removeIf(x -> x.projectId().equals(e.projectId()));
        entries.add(e);
        save();
    }

    public void remove(String projectId) {
        if (entries.removeIf(x -> x.projectId().equals(projectId))) save();
    }

    private List<Entry> load() {
        try {
            if (!Files.exists(file)) return new ArrayList<>();
            List<Entry> loaded = gson.fromJson(Files.readString(file, StandardCharsets.UTF_8), LIST_TYPE);
            return loaded != null ? loaded : new ArrayList<>();
        } catch (Exception e) {
            return new ArrayList<>();
        }
    }

    private void save() {
        try {
            Path tmp = file.resolveSibling(".kano-mods.json.tmp");
            Files.writeString(tmp, gson.toJson(entries, LIST_TYPE), StandardCharsets.UTF_8);
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
        } catch (Exception ignored) {
        }
    }
}
