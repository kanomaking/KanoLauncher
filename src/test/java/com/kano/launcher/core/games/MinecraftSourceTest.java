package com.kano.launcher.core.games;

import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class MinecraftSourceTest {
    @Test
    void alwaysReturnsExactlyOneModdableMinecraft() {
        List<Game> games = new MinecraftSource().scan();
        assertEquals(1, games.size());
        Game mc = games.get(0);
        assertEquals(GameKind.MINECRAFT, mc.kind());
        assertEquals("minecraft", mc.id());
        assertEquals("Minecraft", mc.name());
        assertTrue(mc.moddable());
        assertEquals("MINECRAFT:minecraft", mc.key());
    }
}
