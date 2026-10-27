package edu.learnsphere.app;

import edu.learnsphere.core.AppPaths;
import javafx.application.Application;
import org.slf4j.LoggerFactory;

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
        logUncaughtExceptions();
        Application.launch(LearnSphereApp.class, args);
        // launch returns after LearnSphereApp.stop() closed the profile and stopped the study assistant;
        // exiting here also ends any library thread that would otherwise keep the process alive.
        System.exit(0);
    }

    /** Sends exceptions nobody caught, including those thrown by event handlers on the FX thread, to the app log. */
    public static void logUncaughtExceptions() {
        Thread.setDefaultUncaughtExceptionHandler((thread, error) ->
                LoggerFactory.getLogger(Main.class).error("Uncaught exception on thread {}", thread.getName(), error));
    }

    /** Sets {@value #LOGS_PROPERTY} to {@code <app home>/logs} unless it is already set. Call before the first log. */
    public static void configureLogging() {
        if (System.getProperty(LOGS_PROPERTY) == null) {
            System.setProperty(LOGS_PROPERTY, AppPaths.resolve().logsDir().toString());
        }
    }
}
