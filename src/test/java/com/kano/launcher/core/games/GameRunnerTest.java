package com.kano.launcher.core.games;

import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class GameRunnerTest {
    @Test
    void steamUsesProtocolStart() {
        Game g = Game.basic("440", "TF2", GameKind.STEAM, "x", "steam://rungameid/440");
        assertEquals(List.of("cmd", "/c", "start", "", "steam://rungameid/440"), GameRunner.buildCommand(g));
    }

    @Test
    void standaloneUsesExePlusSplitOptions() {
        Game base = Game.basic("x", "X", GameKind.STANDALONE, "C:/x", "C:/x/x.exe");
        Game withOpts = new Game(base.id(), base.name(), base.kind(), base.installDir(), base.launchTarget(),
                null, 0L, false, false, List.of(), null, false, null, "-windowed -dx11", null);
        assertEquals(List.of("C:/x/x.exe", "-windowed", "-dx11"), GameRunner.buildCommand(withOpts));
        assertEquals(List.of("C:/x/x.exe"), GameRunner.buildCommand(base));
    }

    @Test
    void minecraftCommandIsRejected() {
        Game mc = Game.basic("minecraft", "Minecraft", GameKind.MINECRAFT, null, null);
        assertThrows(IllegalArgumentException.class, () -> GameRunner.buildCommand(mc));
    }

    @Test
    void launchRoutesMinecraftToCallback() {
        Game mc = Game.basic("minecraft", "Minecraft", GameKind.MINECRAFT, null, null);
        boolean[] called = {false};
        try { new GameRunner().launch(mc, () -> called[0] = true); } catch (Exception e) { fail(e); }
        assertTrue(called[0]);
    }
}
