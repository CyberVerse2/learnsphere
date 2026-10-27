package edu.learnsphere.app;

import javafx.application.Application;

/**
 * Launcher. JavaFX runs from the class path only when the main class does not extend
 * {@link Application}, so this class starts {@link LearnSphereApp}.
 */
public final class Main {

    private Main() {
    }

    public static void main(String[] args) {
        Application.launch(LearnSphereApp.class, args);
    }
}
