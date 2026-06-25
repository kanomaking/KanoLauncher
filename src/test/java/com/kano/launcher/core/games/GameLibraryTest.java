package com.kano.launcher.core.games;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class GameLibraryTest {
    private static GameSource source(GameKind kind, Game... games) {
        return new GameSource() {
            public GameKind kind() { return kind; }
            public List<Game> scan() { return List.of(games); }
        };
    }

    @Test
    void aggregatesDedupesAndAppliesLastPlayed(@TempDir Path dir) {
        GamesStore store = new GamesStore(dir);
        store.recordPlayed("STEAM:440", 1700000000L);
        GameSource a = source(GameKind.STEAM, Game.basic("440", "TF2", GameKind.STEAM, "x", "steam://rungameid/440"));
        GameSource dup = source(GameKind.STEAM, Game.basic("440", "TF2 dup", GameKind.STEAM, "y", "z"));
        GameSource mc = source(GameKind.MINECRAFT, Game.basic("minecraft", "Minecraft", GameKind.MINECRAFT, null, null));

        List<Game> games = new GameLibrary(List.of(a, dup, mc), store).load();
        assertEquals(2, games.size(), "duplicate STEAM:440 collapsed to one");
        Game tf2 = games.stream().filter(g -> g.key().equals("STEAM:440")).findFirst().orElseThrow();
        assertEquals("TF2", tf2.name(), "first source wins on dedupe");
        assertEquals(1700000000L, tf2.lastPlayedEpoch(), "last-played stamped from store");
    }

    @Test
    void sortedByNameCaseInsensitive(@TempDir Path dir) {
        GameSource s = source(GameKind.STANDALONE,
                Game.basic("b", "banjo", GameKind.STANDALONE, "x", "x"),
                Game.basic("a", "Azure", GameKind.STANDALONE, "y", "y"));
        List<Game> games = new GameLibrary(List.of(s), new GamesStore(dir)).load();
        assertEquals("Azure", games.get(0).name());
        assertEquals("banjo", games.get(1).name());
    }
}
