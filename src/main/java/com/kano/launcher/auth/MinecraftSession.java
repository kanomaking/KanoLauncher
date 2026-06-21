package com.kano.launcher.auth;

/**
 * A completed Minecraft sign-in.
 *
 * @param username     player name
 * @param uuid         player UUID (no dashes, as returned by the profile API)
 * @param accessToken  Minecraft bearer token (~24h life) — used to launch the game
 * @param refreshToken MSA refresh token — encrypt and store this; use it to get a new session silently
 */
public record MinecraftSession(String username, String uuid, String accessToken, String refreshToken) {
}
