package edu.learnsphere.app.dev;

import edu.learnsphere.app.AppContext;
import edu.learnsphere.app.theme.Theme;
import edu.learnsphere.app.theme.Typography;
import edu.learnsphere.app.ui.MainWindow;
import edu.learnsphere.app.ui.Route;
import edu.learnsphere.core.service.ThemePreference;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
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
 * <p>Add a {@link Shot} to {@link #shots()} for each new screen; it receives the main window and puts it
 * in the state to capture (route, selection, demo content).
 */
public final class ScreenshotTour {

    public static final int WIDTH = 1440;
    public static final int HEIGHT = 900;

    /** One screen to capture: {@code prepare} sets up the window before rendering. */
    public record Shot(String name, Consumer<MainWindow> prepare) {
    }

    private ScreenshotTour() {
    }

    static List<Shot> shots() {
        return List.of(new Shot("shell", window -> window.navigator().reset(Route.HOME)));
    }

    public static void main(String[] args) {
        if (args.length != 1) {
            System.err.println("Usage: ScreenshotTour <output folder>");
            System.exit(2);
        }
        int status = 0;
        try {
            Path out = Files.createDirectories(Path.of(args[0]));
            CompletableFuture<Void> started = new CompletableFuture<>();
            Platform.startup(() -> started.complete(null));
            started.get(30, TimeUnit.SECONDS);
            Platform.setImplicitExit(false);

            CompletableFuture<List<Path>> rendered = new CompletableFuture<>();
            Platform.runLater(() -> {
                try {
                    rendered.complete(renderAll(out));
                } catch (Throwable t) {
                    rendered.completeExceptionally(t);
                }
            });
            for (Path file : rendered.get(2, TimeUnit.MINUTES)) {
                System.out.println("Wrote " + file);
            }
        } catch (Throwable t) {
            status = 1;
            System.err.println("Screenshot tour failed: " + t);
            t.printStackTrace();
        } finally {
            try {
                Platform.exit();
            } catch (Throwable ignored) {
                // The toolkit never started.
            }
        }
        System.exit(status);
    }

    private static List<Path> renderAll(Path out) throws IOException {
        Typography.load();
        List<Path> files = new ArrayList<>();
        try (AppContext context = AppContext.create()) {
            DemoData.seedProfiles(context);
            for (Shot shot : shots()) {
                for (ThemePreference preference : List.of(ThemePreference.LIGHT, ThemePreference.DARK)) {
                    // A fresh scene per theme: snapshots of an off-screen scene can reuse cached renderings.
                    MainWindow window = new MainWindow(context);
                    Scene scene = new Scene(window, WIDTH, HEIGHT);
                    Theme theme = Theme.install(scene, preference);
                    shot.prepare().accept(window);
                    files.add(write(scene, out.resolve(shot.name() + "-" + preference.code() + ".png")));
                    theme.dispose();
                }
            }
        }
        return files;
    }

    private static Path write(Scene scene, Path file) throws IOException {
        scene.getRoot().applyCss();
        scene.getRoot().layout();
        WritableImage image = scene.snapshot(null);
        ImageIO.write(SwingFXUtils.fromFXImage(image, null), "png", file.toFile());
        return file;
    }
}
