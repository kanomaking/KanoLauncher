package com.kano.launcher.core.games;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/** Launches a game by kind: store protocols via "start", standalone exes directly, Minecraft via callback. */
public final class GameRunner {

    public static List<String> buildCommand(Game g) {
        return switch (g.kind()) {
            case STEAM, EPIC, GOG -> List.of("cmd", "/c", "start", "", g.launchTarget());
            case STANDALONE -> {
                List<String> cmd = new ArrayList<>();
                cmd.add(g.launchTarget());
                String opts = g.launchOptions();
                if (opts != null && !opts.isBlank()) {
                    for (String part : opts.trim().split("\\s+")) cmd.add(part);
                }
                yield List.copyOf(cmd);
            }
            case MINECRAFT -> throw new IllegalArgumentException(
                    "Minecraft launches natively — use the onMinecraft callback");
        };
    }

    /** Launch {@code g}. Minecraft runs {@code onMinecraft}; everything else starts a process. */
    public void launch(Game g, Runnable onMinecraft) throws IOException {
        if (g.kind() == GameKind.MINECRAFT) { onMinecraft.run(); return; }
        ProcessBuilder pb = new ProcessBuilder(buildCommand(g));
        File wd = workingDir(g);
        if (wd != null && wd.isDirectory()) pb.directory(wd);
        pb.start();
    }

    private static File workingDir(Game g) {
        if (g.workingDirOverride() != null && !g.workingDirOverride().isBlank())
            return new File(g.workingDirOverride());
        if (g.kind() == GameKind.STANDALONE && g.launchTarget() != null)
            return new File(g.launchTarget()).getParentFile();
        return null;
    }
}
