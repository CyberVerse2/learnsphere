package edu.learnsphere.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import javafx.geometry.Rectangle2D;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WindowStateTest {

    private static final Rectangle2D LAPTOP = new Rectangle2D(0, 25, 1512, 920);
    private static final Rectangle2D MONITOR = new Rectangle2D(1512, 0, 2560, 1415);
    private static final Rectangle2D DEFAULT = new Rectangle2D(0, 0, 1360, 860);

    @TempDir
    Path home;

    @Test
    void centresTheDefaultWindowOnTheScreen() {
        WindowState state = WindowState.centred(LAPTOP, 1360, 860);

        assertThat(state).isEqualTo(new WindowState(76, 55, 1360, 860, false));
    }

    @Test
    void keepsAPlaceThatIsStillOnAScreen() {
        WindowState saved = new WindowState(1700, 120, 1600, 1000, true);

        assertThat(saved.fitTo(List.of(LAPTOP, MONITOR), 1100, 720, DEFAULT)).isEqualTo(saved);
    }

    @Test
    void movesBackToThePrimaryScreenWhenItsScreenIsGone() {
        WindowState saved = new WindowState(1700, 120, 1600, 1000, true);

        WindowState fitted = saved.fitTo(List.of(LAPTOP), 1100, 720, DEFAULT);

        assertThat(fitted).isEqualTo(new WindowState(76, 55, 1360, 860, true));
    }

    @Test
    void shrinksToTheScreenButNotBelowTheMinimum() {
        WindowState tooBig = new WindowState(40, 40, 3000, 2000, false);
        WindowState tiny = new WindowState(100, 100, 400, 300, false);

        assertThat(tooBig.fitTo(List.of(LAPTOP), 1100, 720, DEFAULT))
                .isEqualTo(new WindowState(0, 25, 1512, 920, false));
        assertThat(tiny.fitTo(List.of(LAPTOP), 1100, 720, DEFAULT))
                .isEqualTo(new WindowState(100, 100, 1100, 720, false));
    }

    @Test
    void replacesNonsenseWithTheDefault() {
        WindowState broken = new WindowState(Double.NaN, 0, -5, 0, false);

        assertThat(broken.fitTo(List.of(LAPTOP), 1100, 720, DEFAULT)).isEqualTo(WindowState.centred(LAPTOP, 1360, 860));
    }

    @Test
    void storeRoundTripsTheState() {
        WindowStateStore store = new WindowStateStore(home.resolve("window.json"));
        assertThat(store.load()).isEmpty();

        WindowState state = new WindowState(120, 80, 1440, 900, true);
        store.save(state);

        assertThat(new WindowStateStore(home.resolve("window.json")).load()).contains(state);
    }

    @Test
    void storeLivesInTheAppHome() {
        WindowStateStore store = WindowStateStore.in(edu.learnsphere.core.AppPaths.at(home));

        assertThat(store.file()).isEqualTo(home.resolve(WindowStateStore.FILE_NAME));
    }

    @Test
    void aFailedSaveLeavesNoTemporaryFile() throws IOException {
        // A folder in the file's place cannot be replaced by a file.
        Path file = Files.createDirectories(home.resolve("window.json"));
        Files.writeString(file.resolve("keep.txt"), "x", StandardCharsets.UTF_8);

        new WindowStateStore(file).save(new WindowState(120, 80, 1440, 900, false));

        try (var files = Files.list(home)) {
            assertThat(files.map(p -> p.getFileName().toString())).containsExactly("window.json");
        }
    }

    @Test
    void storeIgnoresUnreadableAndNewerFiles() throws IOException {
        Path file = home.resolve("window.json");
        WindowStateStore store = new WindowStateStore(file);

        Files.writeString(file, "{ not json", StandardCharsets.UTF_8);
        assertThat(store.load()).isEmpty();

        Files.writeString(file, """
                {"version": 99, "x": 1, "y": 2, "width": 1200, "height": 800, "maximized": false}
                """, StandardCharsets.UTF_8);
        assertThat(store.load()).isEmpty();
    }
}
