package com.kano.launcher.core.games;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;

class GamesStoreTest {
    @Test
    void manualGamesRoundTripAcrossInstances(@TempDir Path dir) {
        GamesStore a = new GamesStore(dir);
        assertTrue(a.manualGames().isEmpty());
        a.addManual(Game.basic("ds3", "Dark Souls III", GameKind.STANDALONE, "C:/ds3", "C:/ds3/ds3.exe"));

        GamesStore b = new GamesStore(dir); // re-read from disk
        assertEquals(1, b.manualGames().size());
        assertEquals("Dark Souls III", b.manualGames().get(0).name());
    }

    @Test
    void lastPlayedPersists(@TempDir Path dir) {
        GamesStore a = new GamesStore(dir);
        a.recordPlayed("STEAM:440", 1700000000L);
        assertEquals(1700000000L, new GamesStore(dir).lastPlayedFor("STEAM:440"));
        assertEquals(0L, new GamesStore(dir).lastPlayedFor("STEAM:999"));
    }

    @Test
    void removeManualPersists(@TempDir Path dir) {
        GamesStore a = new GamesStore(dir);
        a.addManual(Game.basic("x", "X", GameKind.STANDALONE, "C:/x", "C:/x/x.exe"));
        a.removeManual("STANDALONE:x");
        assertTrue(new GamesStore(dir).manualGames().isEmpty());
    }
}
