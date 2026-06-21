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
 * Per-instance list of favourite servers, stored as {@code <instanceDir>/.kano-servers.json}.
 * Launcher-side only (separate from Minecraft's own {@code servers.dat}); used for the Servers tab
 * and one-click quick-join.
 */
public final class ServerStore {

    public record Server(String name, String address) {}

    private static final Type LIST_TYPE = new TypeToken<ArrayList<Server>>() {}.getType();

    private final Path file;
    private final Gson gson = new GsonBuilder().setPrettyPrinting().create();
    private final List<Server> servers;

    public ServerStore(Path instanceDir) {
        this.file = instanceDir.resolve(".kano-servers.json");
        this.servers = load();
    }

    public List<Server> list() {
        return List.copyOf(servers);
    }

    public void add(Server s) {
        servers.removeIf(x -> x.address().equalsIgnoreCase(s.address()));
        servers.add(s);
        save();
    }

    public void remove(String address) {
        if (servers.removeIf(x -> x.address().equalsIgnoreCase(address))) save();
    }

    private List<Server> load() {
        try {
            if (!Files.exists(file)) return new ArrayList<>();
            List<Server> loaded = gson.fromJson(Files.readString(file, StandardCharsets.UTF_8), LIST_TYPE);
            return loaded != null ? loaded : new ArrayList<>();
        } catch (Exception e) {
            return new ArrayList<>();
        }
    }

    private void save() {
        try {
            Path tmp = file.resolveSibling(".kano-servers.json.tmp");
            Files.writeString(tmp, gson.toJson(servers, LIST_TYPE), StandardCharsets.UTF_8);
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
        } catch (Exception ignored) {
        }
    }
}
