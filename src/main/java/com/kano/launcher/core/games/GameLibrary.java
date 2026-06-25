package com.kano.launcher.core.games;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Aggregates every GameSource and merges in the user's persisted data (last-played). */
public final class GameLibrary {
    private final List<GameSource> sources;
    private final GamesStore store;

    public GameLibrary(List<GameSource> sources, GamesStore store) {
        this.sources = sources;
        this.store = store;
    }

    public List<Game> load() {
        Map<String, Game> byKey = new LinkedHashMap<>();
        for (GameSource src : sources) {
            for (Game g : src.scan()) {
                byKey.putIfAbsent(g.key(), g); // first source wins
            }
        }
        List<Game> games = new ArrayList<>();
        for (Game g : byKey.values()) {
            long lp = store.lastPlayedFor(g.key());
            games.add(lp > 0 ? g.withLastPlayed(lp) : g);
        }
        games.sort(Comparator.comparing(g -> g.name() == null ? "" : g.name().toLowerCase()));
        return games;
    }

    public void recordPlayed(Game g) {
        store.recordPlayed(g.key(), System.currentTimeMillis());
    }
}
