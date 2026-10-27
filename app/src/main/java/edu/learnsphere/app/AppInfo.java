package edu.learnsphere.app;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

/** Facts about this build of the app, read from {@code app.properties} (filled in by the build). */
public final class AppInfo {

    /** The version shown when the build did not record one (running from an IDE, say). */
    static final String UNKNOWN_VERSION = "0.0.0";

    private static final String VERSION = load();

    private AppInfo() {
    }

    /** The app's version, e.g. {@code 0.1.0}. */
    public static String version() {
        return VERSION;
    }

    private static String load() {
        Properties properties = new Properties();
        try (InputStream in = AppInfo.class.getResourceAsStream("app.properties")) {
            if (in != null) {
                properties.load(in);
            }
        } catch (IOException e) {
            return UNKNOWN_VERSION;
        }
        String version = properties.getProperty("version", "").strip();
        return version.isEmpty() || version.startsWith("$") ? UNKNOWN_VERSION : version;
    }
}
