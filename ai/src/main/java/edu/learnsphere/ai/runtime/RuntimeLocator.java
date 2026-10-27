package edu.learnsphere.ai.runtime;

import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.CodeSource;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Properties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Finds the runtime folder (llama.cpp builds and model files). Candidates, in order:
 *
 * <ol>
 *   <li>system property {@value #PROPERTY};</li>
 *   <li>environment variable {@value #ENVIRONMENT};</li>
 *   <li>{@code runtime/} next to the application jars (the packaged app's {@code app} folder);</li>
 *   <li>{@code runtime/} at the repository root, found by walking up from the working directory to the
 *       folder holding {@code settings.gradle.kts} (development).</li>
 * </ol>
 *
 * The first candidate that is an existing folder wins. A property or variable naming a missing folder is
 * logged and skipped.
 */
public final class RuntimeLocator {

    /** System property naming the runtime folder. */
    public static final String PROPERTY = "learnsphere.runtime";
    /** Environment variable naming the runtime folder. */
    public static final String ENVIRONMENT = "LEARNSPHERE_RUNTIME";
    /** Name of the runtime folder inside the app folder or repository. */
    public static final String FOLDER = "runtime";

    private static final Logger LOG = LoggerFactory.getLogger(RuntimeLocator.class);

    private RuntimeLocator() {
    }

    /** The runtime folder for this process, or empty when none is found. */
    public static Optional<Path> locate() {
        return locate(System.getProperties(), System.getenv(), applicationDirectory(),
                Path.of(System.getProperty("user.dir", ".")));
    }

    /**
     * The runtime folder for explicit inputs (testable).
     *
     * @param applicationDirectory the folder holding the application jars, if known
     * @param workingDirectory     where to start looking for the repository root
     */
    public static Optional<Path> locate(Properties properties, Map<String, String> environment,
                                        Optional<Path> applicationDirectory, Path workingDirectory) {
        Objects.requireNonNull(properties, "properties");
        Objects.requireNonNull(environment, "environment");
        Optional<Path> explicit = explicit("system property " + PROPERTY, properties.getProperty(PROPERTY));
        if (explicit.isPresent()) {
            return explicit;
        }
        explicit = explicit("environment variable " + ENVIRONMENT, environment.get(ENVIRONMENT));
        if (explicit.isPresent()) {
            return explicit;
        }
        Optional<Path> packaged = applicationDirectory.map(dir -> dir.resolve(FOLDER)).filter(Files::isDirectory);
        if (packaged.isPresent()) {
            return packaged.map(RuntimeLocator::normalize);
        }
        return repositoryRoot(workingDirectory).map(root -> root.resolve(FOLDER)).filter(Files::isDirectory)
                .map(RuntimeLocator::normalize);
    }

    private static Optional<Path> explicit(String source, String value) {
        if (value == null || value.isBlank()) {
            return Optional.empty();
        }
        Path path = Path.of(value.strip());
        if (Files.isDirectory(path)) {
            return Optional.of(normalize(path));
        }
        LOG.warn("The {} names {}, which is not a folder; looking elsewhere for the AI runtime", source, path);
        return Optional.empty();
    }

    /** The closest folder at or above {@code start} holding {@code settings.gradle.kts}. */
    static Optional<Path> repositoryRoot(Path start) {
        if (start == null) {
            return Optional.empty();
        }
        for (Path dir = start.toAbsolutePath().normalize(); dir != null; dir = dir.getParent()) {
            if (Files.isRegularFile(dir.resolve("settings.gradle.kts"))) {
                return Optional.of(dir);
            }
        }
        return Optional.empty();
    }

    /** The folder holding the jar this class was loaded from, or empty when it was not loaded from a jar. */
    static Optional<Path> applicationDirectory() {
        try {
            CodeSource source = RuntimeLocator.class.getProtectionDomain().getCodeSource();
            if (source == null || source.getLocation() == null) {
                return Optional.empty();
            }
            Path location = Path.of(source.getLocation().toURI());
            if (Files.isRegularFile(location) && location.getFileName().toString().endsWith(".jar")) {
                return Optional.ofNullable(location.getParent());
            }
            return Optional.empty();
        } catch (URISyntaxException | IllegalArgumentException | SecurityException e) {
            LOG.debug("Cannot tell where the application jars are", e);
            return Optional.empty();
        }
    }

    private static Path normalize(Path path) {
        return path.toAbsolutePath().normalize();
    }
}
