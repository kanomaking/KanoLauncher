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
import java.util.Locale;

/**
 * Creates, lists, and persists {@link Instance}s. Each instance has its own folder under
 * {@code <dataDir>/instances/<dirName>}; the registry is plain JSON (no secrets) in
 * {@code <dataDir>/instances.json}.
 */
public final class InstanceManager {

    private static final int DEFAULT_RAM_MB = 4096; // 4 GB default (see KanoLauncher.md)
    private static final Type LIST_TYPE = new TypeToken<ArrayList<Instance>>() {}.getType();

    private final Path instancesDir;
    private final Path registryFile;
    private final Gson gson = new GsonBuilder().setPrettyPrinting().create();
    private final List<Instance> instances;

    public InstanceManager(Path dataDir) throws Exception {
        this.instancesDir = dataDir.resolve("instances");
        this.registryFile = dataDir.resolve("instances.json");
        Files.createDirectories(instancesDir);
        this.instances = load();
    }

    public List<Instance> list() {
        return List.copyOf(instances);
    }

    public Path instanceDir(Instance i) {
        return instancesDir.resolve(i.dirName());
    }

    /** Create a new instance with the default heap size. */
    public Instance create(String name, String version, Loader loader) throws Exception {
        String clean = name == null || name.isBlank() ? "Instance" : name.trim();
        String dirName = uniqueDir(sanitize(clean));
        Files.createDirectories(instancesDir.resolve(dirName));
        Instance i = new Instance(clean, version, loader, dirName,
                System.currentTimeMillis(), 0L, DEFAULT_RAM_MB, "grass");
        instances.add(i);
        save();
        return i;
    }

    public void delete(Instance i) throws Exception {
        if (instances.removeIf(x -> x.dirName().equals(i.dirName()))) save();
        // instance folder is left on disk for safety; a future "delete files" option can remove it
    }

    /** Replace an instance entry (matched by dirName). */
    public void update(Instance i) throws Exception {
        for (int k = 0; k < instances.size(); k++) {
            if (instances.get(k).dirName().equals(i.dirName())) {
                instances.set(k, i);
                save();
                return;
            }
        }
    }

    // ---- persistence ----

    private List<Instance> load() throws Exception {
        if (!Files.exists(registryFile)) return new ArrayList<>();
        String json = Files.readString(registryFile, StandardCharsets.UTF_8);
        List<Instance> loaded = gson.fromJson(json, LIST_TYPE);
        return loaded != null ? loaded : new ArrayList<>();
    }

    private void save() throws Exception {
        Path tmp = registryFile.resolveSibling("instances.json.tmp");
        Files.writeString(tmp, gson.toJson(instances, LIST_TYPE), StandardCharsets.UTF_8);
        Files.move(tmp, registryFile, StandardCopyOption.REPLACE_EXISTING);
    }

    private String sanitize(String s) {
        String out = s.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9-_ ]", "").trim().replaceAll("\\s+", "-");
        return out.isBlank() ? "instance" : out;
    }

    private String uniqueDir(String base) {
        String candidate = base;
        int n = 2;
        while (Files.exists(instancesDir.resolve(candidate))) {
            candidate = base + "-" + n++;
        }
        return candidate;
    }
}
