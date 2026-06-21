package com.kano.launcher.core;

import java.util.List;

/**
 * A browsable content provider (Modrinth, CurseForge, …). All sources map their data to the common
 * Modrinth-shaped DTOs so the UI and installer don't care which source a result came from.
 */
public interface ContentSource {
    String id();

    ModrinthClient.SearchResult search(String query, String gameVersion, String loader,
                                       String projectType, String sort, int offset, int limit) throws Exception;

    List<ModrinthClient.ModVersion> versions(String idOrSlug, String gameVersion, String loader) throws Exception;

    ModrinthClient.ProjectDetail project(String idOrSlug) throws Exception;
}
