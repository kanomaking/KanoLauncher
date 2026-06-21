package com.kano.launcher.core;

/**
 * A launcher instance: an isolated game directory with its own version, loader, and settings.
 *
 * @param name            user-facing name (renameable)
 * @param version         Minecraft version, e.g. "1.21.4"
 * @param loader          mod loader
 * @param dirName         folder name under instances/ (sanitized, stable — does not change on rename)
 * @param createdEpoch    creation time (millis)
 * @param lastPlayedEpoch last launch time (millis), 0 if never
 * @param ramMb           heap size in MB (default 4096)
 * @param iconKey         profile-icon key (a preset block), e.g. "grass"
 * @param width           window width in px (0 = game default)
 * @param height          window height in px (0 = game default)
 * @param fullscreen      launch fullscreen
 * @param jvmArgs         extra JVM args (space-separated), may be empty
 * @param group           optional group/tag for organizing instances (null/blank = ungrouped)
 */
public record Instance(
        String name,
        String version,
        Loader loader,
        String dirName,
        long createdEpoch,
        long lastPlayedEpoch,
        int ramMb,
        String iconKey,
        int width,
        int height,
        boolean fullscreen,
        String jvmArgs,
        String group
) {
    public String iconOrDefault() {
        return iconKey == null || iconKey.isBlank() ? "grass" : iconKey;
    }

    public String jvmArgsOrEmpty() {
        return jvmArgs == null ? "" : jvmArgs;
    }

    /** Group label, or "" when ungrouped. */
    public String groupOrNone() {
        return group == null || group.isBlank() ? "" : group.trim();
    }

    public Instance withLastPlayed(long epoch) {
        return new Instance(name, version, loader, dirName, createdEpoch, epoch, ramMb, iconKey, width, height, fullscreen, jvmArgs, group);
    }

    public Instance withRam(int mb) {
        return new Instance(name, version, loader, dirName, createdEpoch, lastPlayedEpoch, mb, iconKey, width, height, fullscreen, jvmArgs, group);
    }

    public Instance withIconKey(String key) {
        return new Instance(name, version, loader, dirName, createdEpoch, lastPlayedEpoch, ramMb, key, width, height, fullscreen, jvmArgs, group);
    }

    public Instance withName(String newName) {
        return new Instance(newName, version, loader, dirName, createdEpoch, lastPlayedEpoch, ramMb, iconKey, width, height, fullscreen, jvmArgs, group);
    }

    public Instance withGroup(String newGroup) {
        return new Instance(name, version, loader, dirName, createdEpoch, lastPlayedEpoch, ramMb, iconKey, width, height, fullscreen, jvmArgs, newGroup);
    }

    /** Apply the editable settings block in one go. */
    public Instance withSettings(String name, int ramMb, int width, int height, boolean fullscreen, String jvmArgs, String group) {
        return new Instance(name, version, loader, dirName, createdEpoch, lastPlayedEpoch, ramMb, iconKey, width, height, fullscreen, jvmArgs, group);
    }
}
