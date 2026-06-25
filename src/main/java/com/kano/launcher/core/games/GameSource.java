package com.kano.launcher.core.games;

import java.util.List;

/** A place games can be discovered. Implementations must never throw from scan() — return empty instead. */
public interface GameSource {
    GameKind kind();
    List<Game> scan();
}
