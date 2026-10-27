package edu.learnsphere.core.profile;

import edu.learnsphere.core.error.LearnSphereException;
import edu.learnsphere.core.error.UserMessage;
import edu.learnsphere.core.service.ThemePreference;
import edu.learnsphere.core.util.AtomicFiles;
import edu.learnsphere.core.util.Json;
import edu.learnsphere.core.util.Subscription;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import java.util.function.UnaryOperator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Reads and writes {@link AppPreferences} in {@code app-settings.json} ({@code AppPaths.preferencesFile()}).
 * A missing or unreadable file gives the defaults (preferences are easy to set again, so a damaged file
 * never stops the app from starting). Every change writes the whole file atomically and notifies
 * listeners on the calling thread.
 *
 * <p>File format: {@code {"version": 1, "theme": "system", "reduceMotion": false, "lastProfileId": null,
 * "onboardingCompleted": false}}. Thread-safe.
 */
public final class AppPreferencesStore {

    /** Current file format version. */
    public static final int FORMAT_VERSION = 1;

    private static final Logger LOG = LoggerFactory.getLogger(AppPreferencesStore.class);
    private static final UserMessage CANNOT_SAVE =
            UserMessage.of("Your preferences couldn't be saved. Check that there's free space and try again.");

    /** The JSON document. */
    record PreferencesFile(int version, String theme, boolean reduceMotion, String lastProfileId,
                           boolean onboardingCompleted) {
    }

    private final Path file;
    private final List<Consumer<AppPreferences>> listeners = new CopyOnWriteArrayList<>();
    private AppPreferences current;

    public AppPreferencesStore(Path file) {
        this.file = Objects.requireNonNull(file, "file");
        this.current = load(file);
    }

    /** The current preferences. */
    public synchronized AppPreferences get() {
        return current;
    }

    /**
     * Applies {@code change}, writes the file and notifies listeners; returns the new preferences.
     *
     * @throws LearnSphereException when the file cannot be written (the preferences stay as they were)
     */
    public AppPreferences update(UnaryOperator<AppPreferences> change) {
        AppPreferences next;
        synchronized (this) {
            next = Objects.requireNonNull(change.apply(current), "preferences");
            if (next.equals(current)) {
                return current;
            }
            write(next);
            current = next;
        }
        for (Consumer<AppPreferences> listener : listeners) {
            listener.accept(next);
        }
        return next;
    }

    /** Calls {@code listener} after every change, on the thread that made it. */
    public Subscription addListener(Consumer<AppPreferences> listener) {
        listeners.add(Objects.requireNonNull(listener, "listener"));
        return () -> listeners.remove(listener);
    }

    private static AppPreferences load(Path file) {
        if (!Files.exists(file)) {
            return AppPreferences.DEFAULTS;
        }
        try {
            PreferencesFile stored = Json.read(Files.readString(file, StandardCharsets.UTF_8), PreferencesFile.class);
            ThemePreference theme = stored.theme() == null ? ThemePreference.SYSTEM : ThemePreference.fromCode(stored.theme());
            return new AppPreferences(theme, stored.reduceMotion(), stored.lastProfileId(), stored.onboardingCompleted());
        } catch (IOException | UncheckedIOException | IllegalArgumentException e) {
            LOG.warn("Ignoring unreadable preferences in {}", file, e);
            return AppPreferences.DEFAULTS;
        }
    }

    private void write(AppPreferences preferences) {
        try {
            AtomicFiles.writeString(file, Json.writePretty(new PreferencesFile(FORMAT_VERSION, preferences.theme().code(),
                    preferences.reduceMotion(), preferences.lastProfileId(), preferences.onboardingCompleted())));
        } catch (IOException e) {
            throw new LearnSphereException(CANNOT_SAVE, "Cannot write " + file, e);
        }
    }
}
