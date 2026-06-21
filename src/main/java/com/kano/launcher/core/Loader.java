package com.kano.launcher.core;

/** Mod loader for an instance. Only VANILLA and FABRIC are wired in Phase 1. */
public enum Loader {
    VANILLA("Vanilla"),
    FABRIC("Fabric"),
    FORGE("Forge"),
    NEOFORGE("NeoForge"),
    QUILT("Quilt");

    private final String display;

    Loader(String display) {
        this.display = display;
    }

    public String display() {
        return display;
    }
}
