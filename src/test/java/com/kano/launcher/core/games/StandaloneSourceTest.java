package com.kano.launcher.core.games;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class StandaloneSourceTest {
    @Test
    void returnsManuallyAddedGames(@TempDir Path dir) {
        GamesStore store = new GamesStore(dir);
        store.addManual(Game.basic("notepad", "Notepad", GameKind.STANDALONE, "C:/Windows", "C:/Windows/notepad.exe"));
        List<Game> games = new StandaloneSource(store).scan();
        assertEquals(1, games.size());
        assertEquals("Notepad", games.get(0).name());
        assertEquals(GameKind.STANDALONE, new StandaloneSource(store).kind());
    }
}
