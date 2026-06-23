package com.kano.launcher.core;

/**
 * Thrown when CurseForge rejects the developer API key with HTTP 403 — the key is missing, invalid,
 * or (most often) was deactivated on CurseForge's side. Callers catch this to prompt the user to
 * update their key in Settings, rather than showing a raw error.
 */
public class CfAuthException extends RuntimeException {
    public CfAuthException(String message) {
        super(message);
    }
}
