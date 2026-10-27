package edu.learnsphere.core.testing;

import edu.learnsphere.core.AppPaths;
import java.nio.file.Path;
import org.junit.platform.launcher.LauncherSession;
import org.junit.platform.launcher.LauncherSessionListener;

/**
 * Gives each Gradle test JVM an app home of its own: {@code <learnsphere.home>/worker-<n>}. The build sets one
 * home per test task and runs a task's test classes in several JVMs at once, so without this they would share it.
 * Does nothing outside Gradle's test workers. Registered in {@code META-INF/services}.
 */
public final class TestWorkerHome implements LauncherSessionListener {

    /** The system property Gradle sets to a test JVM's worker number. */
    static final String WORKER_PROPERTY = "org.gradle.test.worker";

    @Override
    public void launcherSessionOpened(LauncherSession session) {
        String home = System.getProperty(AppPaths.HOME_PROPERTY);
        String worker = System.getProperty(WORKER_PROPERTY);
        if (home != null && !home.isBlank() && worker != null && !worker.isBlank()) {
            System.setProperty(AppPaths.HOME_PROPERTY, Path.of(home, "worker-" + worker).toString());
        }
    }
}
