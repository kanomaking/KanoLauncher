package com.kano.launcher.core.games;

import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class GameTest {
    @Test
    void basicFillsDefaultsAndKey() {
        Game g = Game.basic("440", "Team Fortress 2", GameKind.STEAM, "C:/games/tf2", "steam://rungameid/440");
        assertEquals("STEAM:440", g.key());
        assertEquals("Team Fortress 2", g.name());
        assertFalse(g.favorite());
        assertEquals(0L, g.lastPlayedEpoch());
        assertNotNull(g.categories());
        assertTrue(g.categories().isEmpty());
    }

    @Test
    void withersReturnUpdatedCopies() {
        Game g = Game.basic("x", "X", GameKind.STANDALONE, "C:/x", "C:/x/x.exe");
        assertEquals(123L, g.withLastPlayed(123L).lastPlayedEpoch());
        assertEquals("a.png", g.withCoverArt("a.png").coverArtPath());
        assertEquals(0L, g.lastPlayedEpoch(), "original unchanged (record immutability)");
    }
}
