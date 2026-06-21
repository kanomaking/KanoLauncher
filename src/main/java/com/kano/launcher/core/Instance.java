package com.kano.launcher.core;

/**
 * A launcher instance: an isolated game directory with its own version, loader, and settings.
 *
 * @param name           user-facing name
 * @param version        Minecraft version, e.g. "1.21.4"
 * @param loader         mod loader
 * @param dirName        folder name under instances/ (sanitized, unique)
 * @param createdEpoch   creation time (millis)
 * @param lastPlayedEpoch last launch time (millis), 0 if never
 * @param ramMb          heap size in MB (default 4096)
 */
public record Instance(
        String name,
        String version,
        Loader loader,
        String dirName,
        long createdEpoch,
        long lastPlayedEpoch,
        int ramMb
) {
}
