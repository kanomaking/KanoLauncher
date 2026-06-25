package com.kano.launcher.core.games;

import java.util.List;

/** Minecraft is always present as a first-class game; it's launched natively (no install path needed). */
public final class MinecraftSource implements GameSource {
    @Override public GameKind kind() { return GameKind.MINECRAFT; }

    @Override public List<Game> scan() {
        Game mc = new Game("minecraft", "Minecraft", GameKind.MINECRAFT,
                null, null, null, 0L, false, false, List.of(), "#3FA34D", true, null, null, null);
        return List.of(mc);
    }
}
