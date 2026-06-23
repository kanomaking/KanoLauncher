package com.kano.launcher;

import javafx.application.Application;

/**
 * Entry point. Deliberately does NOT extend javafx.application.Application — launching the JavaFX
 * app through a non-Application main class avoids the "JavaFX runtime components are missing" error
 * when running from the classpath.
 */
public final class Launcher {
    public static void main(String[] args) {
        // Force JavaFX's software render pipeline. On some Windows GPUs/drivers the Direct3D
        // "present" path composites the window content with a vertical offset (content shifted up
        // ~one title-bar height — clipped at the top, not scrollable), even though the scene graph
        // and the window backing store are correct (which is why PrintWindow/snapshot looked fine
        // but the on-screen result was clipped). The software pipeline avoids that D3D path. A
        // launcher UI doesn't need GPU acceleration, so the cost is negligible. Must be set before
        // the JavaFX toolkit initializes (i.e. before Application.launch).
        if (System.getProperty("prism.order") == null) System.setProperty("prism.order", "sw");
        Application.launch(MainApp.class, args);
    }
}
