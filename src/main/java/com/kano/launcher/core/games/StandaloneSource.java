package com.kano.launcher.core.games;

import java.util.List;

/** Games the user added by hand (any .exe). Backed by GamesStore's manual list. */
public final class StandaloneSource implements GameSource {
    private final GamesStore store;

    public StandaloneSource(GamesStore store) { this.store = store; }

    @Override public GameKind kind() { return GameKind.STANDALONE; }

    @Override public List<Game> scan() { return store.manualGames(); }
}
