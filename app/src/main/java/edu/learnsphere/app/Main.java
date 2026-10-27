package edu.learnsphere.app;

import edu.learnsphere.core.AppPaths;
import javafx.application.Application;

/**
 * Launcher. JavaFX runs from the class path only when the main class does not extend
 * {@link Application}, so this class starts {@link LearnSphereApp}. It also points logging at the app's
 * logs folder before anything logs.
 */
public final class Main {

    /** System property {@code logback.xml} reads for the logs folder. */
    public static final String LOGS_PROPERTY = "learnsphere.logs";

    private Main() {
    }

    public static void main(String[] args) {
        configureLogging();
        Application.launch(LearnSphereApp.class, args);
    }

    /** Sets {@value #LOGS_PROPERTY} to {@code <app home>/logs} unless it is already set. Call before the first log. */
    public static void configureLogging() {
        if (System.getProperty(LOGS_PROPERTY) == null) {
            System.setProperty(LOGS_PROPERTY, AppPaths.resolve().logsDir().toString());
        }
    }
}
