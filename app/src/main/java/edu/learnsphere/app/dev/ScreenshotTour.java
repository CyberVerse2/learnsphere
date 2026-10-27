package edu.learnsphere.app.dev;

import edu.learnsphere.app.AppContext;
import edu.learnsphere.app.Main;
import edu.learnsphere.app.theme.Theme;
import edu.learnsphere.app.theme.Typography;
import edu.learnsphere.app.ui.Destination;
import edu.learnsphere.app.ui.MainWindow;
import edu.learnsphere.app.ui.UiExecutors;
import edu.learnsphere.core.service.ThemePreference;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import javafx.application.Platform;
import javafx.embed.swing.SwingFXUtils;
import javafx.scene.Scene;
import javafx.scene.image.WritableImage;
import javax.imageio.ImageIO;

/**
 * Renders screens to PNG in light and dark without showing a window, then exits. Usage:
 * {@code ScreenshotTour <output folder>} (run through {@code ./gradlew :app:screenshots}). Each shot is
 * written as {@code <name>-light.png} and {@code <name>-dark.png} at {@value #WIDTH}×{@value #HEIGHT}.
 *
 * <p>The demo profile is open, so screens show {@link DemoWorkspace} content. Add a {@link Shot} to
 * {@link #shots()} for each new screen; it receives the main window and puts it in the state to capture.
 * Background loads finish before each capture.
 */
public final class ScreenshotTour {

    public static final int WIDTH = 1440;
    public static final int HEIGHT = 900;

    private static final Duration SETTLE_TIMEOUT = Duration.ofSeconds(20);

    /** One screen to capture: {@code prepare} sets up the window before rendering. */
    public record Shot(String name, Consumer<MainWindow> prepare) {
    }

    private ScreenshotTour() {
    }

    static List<Shot> shots() {
        return List.of(new Shot("shell", window -> window.navigator().reset(Destination.HOME)));
    }

    public static void main(String[] args) {
        if (args.length != 1) {
            System.err.println("Usage: ScreenshotTour <output folder>");
            System.exit(2);
        }
        Main.configureLogging();
        int status = 0;
        try {
            Path out = Files.createDirectories(Path.of(args[0]));
            CompletableFuture<Void> started = new CompletableFuture<>();
            Platform.startup(() -> started.complete(null));
            started.get(30, TimeUnit.SECONDS);
            Platform.setImplicitExit(false);
            for (Path file : renderAll(out)) {
                System.out.println("Wrote " + file);
            }
        } catch (Throwable t) {
            status = 1;
            System.err.println("Screenshot tour failed: " + t);
            t.printStackTrace();
        } finally {
            UiExecutors.shutdown();
            try {
                Platform.exit();
            } catch (Throwable ignored) {
                // The toolkit never started.
            }
        }
        System.exit(status);
    }

    private static List<Path> renderAll(Path out) throws Exception {
        onFx(() -> {
            Typography.load();
            return null;
        });
        List<Path> files = new ArrayList<>();
        try (AppContext context = AppContext.create()) {
            DemoData.seedProfiles(context);
            context.openLastProfile().orElseThrow(() -> new IllegalStateException("The demo profile did not open"));
            settle();
            for (Shot shot : shots()) {
                for (ThemePreference preference : List.of(ThemePreference.LIGHT, ThemePreference.DARK)) {
                    // A fresh scene per theme: snapshots of an off-screen scene can reuse cached renderings.
                    Capture capture = onFx(() -> {
                        MainWindow window = new MainWindow(context);
                        Scene scene = new Scene(window, WIDTH, HEIGHT);
                        Theme theme = Theme.install(scene, preference);
                        shot.prepare().accept(window);
                        return new Capture(window, scene, theme);
                    });
                    settle();
                    Path file = out.resolve(shot.name() + "-" + preference.code() + ".png");
                    files.add(onFx(() -> {
                        try {
                            return write(capture.scene(), file);
                        } finally {
                            capture.window().dispose();
                            capture.theme().dispose();
                        }
                    }));
                }
            }
        }
        return files;
    }

    private record Capture(MainWindow window, Scene scene, Theme theme) {
    }

    /** Waits for background loads and the FX updates they queue (twice, for loads started by results). */
    private static void settle() throws Exception {
        for (int round = 0; round < 2; round++) {
            if (!UiExecutors.awaitIdle(SETTLE_TIMEOUT)) {
                throw new IllegalStateException("Background loads did not finish within " + SETTLE_TIMEOUT);
            }
            onFx(() -> null);
        }
    }

    private static <T> T onFx(Callable<T> action) throws Exception {
        CompletableFuture<T> result = new CompletableFuture<>();
        Platform.runLater(() -> {
            try {
                result.complete(action.call());
            } catch (Throwable t) {
                result.completeExceptionally(t);
            }
        });
        return result.get(2, TimeUnit.MINUTES);
    }

    private static Path write(Scene scene, Path file) throws IOException {
        scene.getRoot().applyCss();
        scene.getRoot().layout();
        WritableImage image = scene.snapshot(null);
        ImageIO.write(SwingFXUtils.fromFXImage(image, null), "png", file.toFile());
        return file;
    }
}
