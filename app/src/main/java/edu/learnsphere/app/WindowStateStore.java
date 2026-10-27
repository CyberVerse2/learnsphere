package edu.learnsphere.app;

import edu.learnsphere.core.AppPaths;
import edu.learnsphere.core.util.Json;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Objects;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Reads and writes the {@link WindowState} in {@value #FILE_NAME} in the app home ({@link #in(AppPaths)}). A
 * missing or unreadable file gives no state (the window opens at its default size), and a failed write is
 * only logged and leaves no temporary file behind: the window's place is never worth an error. File format:
 * {@code {"version": 1, "x": 80, "y": 60, "width": 1360, "height": 860, "maximized": false}}.
 */
public final class WindowStateStore {

    /** Current file format version. */
    public static final int FORMAT_VERSION = 1;
    /** Name of the file in the app home. */
    public static final String FILE_NAME = "window.json";

    private static final Logger LOG = LoggerFactory.getLogger(WindowStateStore.class);

    /** The JSON document. */
    record StateFile(int version, double x, double y, double width, double height, boolean maximized) {
    }

    private final Path file;

    public WindowStateStore(Path file) {
        this.file = Objects.requireNonNull(file, "file");
    }

    /** The store of the app home of {@code paths}. */
    public static WindowStateStore in(AppPaths paths) {
        return new WindowStateStore(paths.home().resolve(FILE_NAME));
    }

    /** Where the state is saved. */
    public Path file() {
        return file;
    }

    /** The saved state, or empty when there is none or it can't be read. */
    public Optional<WindowState> load() {
        if (!Files.isRegularFile(file)) {
            return Optional.empty();
        }
        try {
            StateFile saved = Json.read(Files.readString(file, StandardCharsets.UTF_8), StateFile.class);
            if (saved.version() != FORMAT_VERSION) {
                return Optional.empty();
            }
            return Optional.of(new WindowState(saved.x(), saved.y(), saved.width(), saved.height(), saved.maximized()));
        } catch (IOException | RuntimeException e) {
            LOG.warn("Ignoring unreadable window state {}", file, e);
            return Optional.empty();
        }
    }

    /** Saves {@code state}, replacing the file atomically. */
    public void save(WindowState state) {
        Objects.requireNonNull(state, "state");
        StateFile content = new StateFile(FORMAT_VERSION, state.x(), state.y(), state.width(), state.height(),
                state.maximized());
        Path temp = null;
        try {
            Files.createDirectories(file.toAbsolutePath().getParent());
            temp = Files.createTempFile(file.toAbsolutePath().getParent(), "window", ".tmp");
            Files.writeString(temp, Json.writePretty(content), StandardCharsets.UTF_8);
            try {
                Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException e) {
                Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
            }
            temp = null;
        } catch (IOException | RuntimeException e) {
            LOG.warn("Could not save the window state to {}", file, e);
        } finally {
            if (temp != null) {
                try {
                    Files.deleteIfExists(temp);
                } catch (IOException e) {
                    LOG.debug("Could not remove {}", temp, e);
                }
            }
        }
    }
}
