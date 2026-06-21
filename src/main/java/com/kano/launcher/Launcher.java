package com.kano.launcher;

import javafx.application.Application;

/**
 * Entry point. Deliberately does NOT extend javafx.application.Application — launching the JavaFX
 * app through a non-Application main class avoids the "JavaFX runtime components are missing" error
 * when running from the classpath.
 */
public final class Launcher {
    public static void main(String[] args) {
        Application.launch(MainApp.class, args);
    }
}
