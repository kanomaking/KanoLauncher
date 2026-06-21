package com.kano.launcher.core;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Installs a Modrinth mod (and its required dependencies, recursively) into an instance's mods/
 * folder. Modrinth-only keeps the dependency graph clean (no CurseForge opt-out dead-ends).
 */
public final class ModInstaller {

    public interface Log { void line(String message); }

    private ModInstaller() {}

    /** Install a project's latest compatible version + all required deps. Returns count installed. */
    public static int install(ModrinthClient client, String projectId, String gameVersion,
                              String loader, Path modsDir, Log log) throws Exception {
        Files.createDirectories(modsDir);
        return installRec(client, projectId, gameVersion, loader, modsDir, new HashSet<>(), log);
    }

    private static int installRec(ModrinthClient client, String projectId, String gameVersion,
                                  String loader, Path modsDir, Set<String> visited, Log log) throws Exception {
        if (!visited.add(projectId)) return 0;
        List<ModrinthClient.ModVersion> versions = client.versions(projectId, gameVersion, loader);
        if (versions.isEmpty()) {
            log.line("No compatible version for " + projectId + " (" + gameVersion + "/" + loader + ")");
            return 0;
        }
        ModrinthClient.ModVersion v = versions.get(0); // newest compatible
        ModrinthClient.ModFile file = v.files().stream().filter(ModrinthClient.ModFile::primary)
                .findFirst().orElse(v.files().isEmpty() ? null : v.files().get(0));
        if (file == null) return 0;

        Downloader.download(file.url(), null, modsDir.resolve(file.filename()));
        log.line("Installed " + file.filename());
        int count = 1;

        for (ModrinthClient.Dep dep : v.dependencies()) {
            if ("required".equals(dep.type())) {
                count += installRec(client, dep.projectId(), gameVersion, loader, modsDir, visited, log);
            }
        }
        return count;
    }
}
