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
 * @param iconKey        profile-icon key (a preset block), e.g. "grass"
 */
public record Instance(
        String name,
        String version,
        Loader loader,
        String dirName,
        long createdEpoch,
        long lastPlayedEpoch,
        int ramMb,
        String iconKey
) {
    /** Default icon when an instance predates the iconKey field (loaded as null). */
    public String iconOrDefault() {
        return iconKey == null || iconKey.isBlank() ? "grass" : iconKey;
    }

    public Instance withLastPlayed(long epoch) {
        return new Instance(name, version, loader, dirName, createdEpoch, epoch, ramMb, iconKey);
    }

    public Instance withRam(int mb) {
        return new Instance(name, version, loader, dirName, createdEpoch, lastPlayedEpoch, mb, iconKey);
    }

    public Instance withIconKey(String key) {
        return new Instance(name, version, loader, dirName, createdEpoch, lastPlayedEpoch, ramMb, key);
    }
}
