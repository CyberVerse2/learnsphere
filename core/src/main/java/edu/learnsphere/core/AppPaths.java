package edu.learnsphere.core;

import edu.learnsphere.core.util.Ids;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;

/**
 * The on-disk layout. The only class that knows where things live:
 *
 * <pre>
 * &lt;app home&gt;/
 *   profiles.json
 *   logs/
 *   profiles/&lt;profileId&gt;/
 *     learnsphere.db
 *     index/
 *     files/&lt;sha256&gt;.pdf
 *     renders/
 * </pre>
 *
 * The app home is, in order: system property {@value #HOME_PROPERTY}; on macOS
 * {@code ~/Library/Application Support/LearnSphere}; on Windows {@code %LOCALAPPDATA%\LearnSphere}; otherwise
 * {@code $XDG_DATA_HOME/learnsphere} or {@code ~/.local/share/learnsphere}. Directory accessors create
 * their directory when it does not exist yet.
 */
public final class AppPaths {

    /** System property that overrides the app home (tests, demo, screenshot tour). */
    public static final String HOME_PROPERTY = "learnsphere.home";

    private final Path home;

    private AppPaths(Path home) {
        this.home = home.toAbsolutePath().normalize();
    }

    /** The layout under the app home resolved from system properties, environment and platform. */
    public static AppPaths resolve() {
        return at(resolveHome(System.getProperties(), System.getenv()));
    }

    /** The layout under an explicit home folder. */
    public static AppPaths at(Path home) {
        return new AppPaths(Objects.requireNonNull(home, "home"));
    }

    /** Resolves the app home from the given system properties and environment (testable). */
    public static Path resolveHome(Properties properties, Map<String, String> env) {
        String override = properties.getProperty(HOME_PROPERTY);
        if (override != null && !override.isBlank()) {
            return Path.of(override);
        }
        String os = properties.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        Path userHome = Path.of(properties.getProperty("user.home", "."));
        if (os.contains("mac")) {
            return userHome.resolve("Library").resolve("Application Support").resolve("LearnSphere");
        }
        if (os.contains("win")) {
            String localAppData = env.get("LOCALAPPDATA");
            Path base = localAppData != null && !localAppData.isBlank()
                    ? Path.of(localAppData)
                    : userHome.resolve("AppData").resolve("Local");
            return base.resolve("LearnSphere");
        }
        String xdg = env.get("XDG_DATA_HOME");
        Path base = xdg != null && !xdg.isBlank() ? Path.of(xdg) : userHome.resolve(".local").resolve("share");
        return base.resolve("learnsphere");
    }

    /** The app home (created on demand). */
    public Path home() {
        return directory(home);
    }

    /** The profile registry file. */
    public Path profilesFile() {
        return home().resolve("profiles.json");
    }

    /** Application and AI process logs. */
    public Path logsDir() {
        return directory(home.resolve("logs"));
    }

    /** The folder holding every profile folder. */
    public Path profilesDir() {
        return directory(home.resolve("profiles"));
    }

    /** The paths of one profile. */
    public ProfilePaths profile(String profileId) {
        return new ProfilePaths(home.resolve("profiles").resolve(Ids.requireValid(profileId)));
    }

    /**
     * The folders of one profile. Directory accessors create their directory on demand.
     */
    public static final class ProfilePaths {

        private final Path root;

        ProfilePaths(Path root) {
            this.root = root;
        }

        /** The profile folder (back up or restore this whole folder). */
        public Path root() {
            return directory(root);
        }

        /** True when the profile folder exists. */
        public boolean exists() {
            return Files.isDirectory(root);
        }

        /** The SQLite database file. */
        public Path database() {
            return root().resolve("learnsphere.db");
        }

        /** The search index folder (rebuildable from the database). */
        public Path indexDir() {
            return directory(root.resolve("index"));
        }

        /** Imported PDFs, named by content hash. */
        public Path filesDir() {
            return directory(root.resolve("files"));
        }

        /** The imported copy of a PDF with this SHA-256 (lowercase hex). */
        public Path pdfFile(String sha256) {
            if (!sha256.matches("[0-9a-f]{64}")) {
                throw new IllegalArgumentException("Not a SHA-256 hex digest: " + sha256);
            }
            return filesDir().resolve(sha256 + ".pdf");
        }

        /** Cached page images. */
        public Path rendersDir() {
            return directory(root.resolve("renders"));
        }

        /** The cached render of a page at a scale, e.g. {@code renders/<documentId>/12@2.0.png}. */
        public Path renderFile(String documentId, int pageNumber, float scale) {
            Path folder = directory(rendersDir().resolve(Ids.requireValid(documentId)));
            return folder.resolve(pageNumber + "@" + String.format(Locale.ROOT, "%.2f", scale) + ".png");
        }
    }

    private static Path directory(Path path) {
        try {
            return Files.createDirectories(path);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot create " + path, e);
        }
    }

    @Override
    public String toString() {
        return "AppPaths[" + home + "]";
    }
}
