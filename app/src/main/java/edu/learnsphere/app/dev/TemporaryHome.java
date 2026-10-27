package edu.learnsphere.app.dev;

import edu.learnsphere.core.AppPaths;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.stream.Stream;

/**
 * The app home of a development run that the build made for that run alone ({@code :app:realRun} and
 * {@code :app:screenshots} create one in the system's temporary folder and set {@value #PROPERTY}). Such a run
 * deletes its home when it ends, so nothing it wrote (profiles, the study assistant's logs) is left behind. A
 * home without the property, such as the one the app itself uses, is never deleted.
 */
public final class TemporaryHome {

    /** System property the build sets to {@code true} when the app home was made for this run only. */
    public static final String PROPERTY = "learnsphere.temporaryHome";

    private TemporaryHome() {
    }

    /** True when this run's app home was made for it alone. */
    public static boolean isTemporary() {
        return Boolean.getBoolean(PROPERTY);
    }

    /**
     * Deletes the app home of {@code paths} with everything in it when it was made for this run alone; does
     * nothing otherwise. Afterwards, check the folder through a path read before this call: {@link AppPaths#home()}
     * makes the folder again when asked for it.
     *
     * @return true when the home was deleted
     */
    public static boolean deleteIfTemporary(AppPaths paths) {
        if (!isTemporary()) {
            return false;
        }
        delete(paths.home());
        return true;
    }

    /** Deletes {@code folder} and everything under it; a folder that is already gone is fine. */
    static void delete(Path folder) {
        if (!Files.exists(folder)) {
            return;
        }
        try (Stream<Path> paths = Files.walk(folder)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Could not delete " + folder, e);
        }
    }
}
