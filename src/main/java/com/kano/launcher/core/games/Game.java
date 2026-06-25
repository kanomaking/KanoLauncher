package com.kano.launcher.core.games;

import java.util.ArrayList;
import java.util.List;

/**
 * A game in the unified library. One immutable record; later plans populate the richer fields
 * (favorite, categories, accentHex, modSource). Persistence of user-edited fields lives in GamesStore.
 */
public record Game(
        String id, String name, GameKind kind, String installDir, String launchTarget,
        String coverArtPath, long lastPlayedEpoch, boolean favorite, boolean startupGame,
        List<String> categories, String accentHex, boolean moddable, String modSource,
        String launchOptions, String workingDirOverride) {

    public Game {
        // FORWARD-COMPAT: Gson's record deserializer passes null for any JSON field absent from an
        // older games.json (e.g. a field added in plan 1B/1C that doesn't exist in the saved file).
        // Every collection field added in future plans MUST be null-guarded here exactly like
        // `categories` is below — or loading an old file will NPE and (via GamesStore's catch)
        // silently drop ALL manually-added games with no error message.
        categories = categories == null ? List.of() : List.copyOf(categories);
    }

    /** Stable identity across rescans: e.g. "STEAM:440". */
    public String key() { return kind + ":" + id; }

    public static Game basic(String id, String name, GameKind kind, String installDir, String launchTarget) {
        return new Game(id, name, kind, installDir, launchTarget, null, 0L, false, false,
                new ArrayList<>(), null, false, null, null, null);
    }

    public Game withLastPlayed(long epoch) {
        return new Game(id, name, kind, installDir, launchTarget, coverArtPath, epoch, favorite, startupGame,
                categories, accentHex, moddable, modSource, launchOptions, workingDirOverride);
    }

    public Game withCoverArt(String path) {
        return new Game(id, name, kind, installDir, launchTarget, path, lastPlayedEpoch, favorite, startupGame,
                categories, accentHex, moddable, modSource, launchOptions, workingDirOverride);
    }
}
