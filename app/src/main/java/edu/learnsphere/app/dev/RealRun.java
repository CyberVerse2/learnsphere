package edu.learnsphere.app.dev;

import edu.learnsphere.app.AppContext;
import edu.learnsphere.app.Main;
import edu.learnsphere.app.theme.Theme;
import edu.learnsphere.app.theme.Typography;
import edu.learnsphere.app.ui.Destination;
import edu.learnsphere.app.ui.MainWindow;
import edu.learnsphere.app.ui.UiExecutors;
import edu.learnsphere.app.ui.settings.SettingsPage;
import edu.learnsphere.core.AppPaths;
import edu.learnsphere.core.service.AssistantState;
import edu.learnsphere.core.service.AssistantStatus;
import edu.learnsphere.core.service.ProfileDraft;
import edu.learnsphere.core.service.ProfileSummary;
import edu.learnsphere.core.service.ThemePreference;
import edu.learnsphere.core.service.Workspace;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import javafx.application.Platform;
import javafx.embed.swing.SwingFXUtils;
import javafx.scene.Scene;
import javafx.scene.image.WritableImage;
import javax.imageio.ImageIO;

/**
 * Runs the app against the real study assistant without showing a window, as a check of the whole start and
 * quit path: makes a profile in a fresh app home, opens it with the main window, waits for the study
 * assistant to be ready, renders Home and the assistant settings to PNG, then quits as the app does and checks
 * that no study assistant process is left. Prints the timings and exits with 0 when every step passed. Usage:
 * {@code RealRun <output folder>} (run through {@code ./gradlew :app:realRun}, which sets the app home and
 * {@code learnsphere.runtime}).
 */
public final class RealRun {

    private static final Duration READY_TIMEOUT = Duration.ofMinutes(15);
    private static final Duration EXIT_TIMEOUT = Duration.ofSeconds(20);

    private RealRun() {
    }

    public static void main(String[] args) {
        if (args.length != 1) {
            System.err.println("Usage: RealRun <output folder>");
            System.exit(2);
        }
        Main.configureLogging();
        int status;
        try {
            CompletableFuture<Void> started = new CompletableFuture<>();
            Platform.startup(() -> started.complete(null));
            started.get(30, TimeUnit.SECONDS);
            Platform.setImplicitExit(false);
            status = run(Files.createDirectories(Path.of(args[0]))) ? 0 : 1;
        } catch (Throwable t) {
            status = 1;
            System.err.println("Real run failed: " + t);
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

    private static boolean run(Path out) throws Exception {
        AppPaths paths = AppPaths.resolve();
        report("home", paths.home());
        report("runtime", System.getProperty("learnsphere.runtime", "(found by LocalAi)"));
        onFx(() -> {
            Typography.load();
            return null;
        });

        long t0 = System.nanoTime();
        AppContext context = AppContext.real(paths, Clock.systemDefaultZone());
        report("context_ms", millis(t0));
        boolean passed = true;
        MainWindow window = null;
        List<ProcessHandle> servers = List.of();
        try {
            ProfileSummary profile = context.profiles().create(new ProfileDraft("Real Run", 226, null));
            long t1 = System.nanoTime();
            Workspace workspace = context.open(profile.id(), null);
            report("profile_open_ms", millis(t1));

            Scene scene = onFx(() -> new Scene(new MainWindow(context), ScreenshotTour.WIDTH, ScreenshotTour.HEIGHT));
            window = (MainWindow) scene.getRoot();
            Theme theme = onFx(() -> Theme.install(scene, ThemePreference.LIGHT));
            settle();
            MainWindow shown = window;
            Destination first = onFx(() -> shown.navigator().current());
            report("first_screen", first);
            passed &= Destination.HOME.equals(first);

            long t2 = System.nanoTime();
            AssistantStatus status = workspace.assistant().status();
            long deadline = System.nanoTime() + READY_TIMEOUT.toNanos();
            while (status.state() == AssistantState.STARTING && System.nanoTime() < deadline) {
                Thread.sleep(250);
                status = workspace.assistant().status();
            }
            report("assistant_state", status.state());
            report("assistant_message", status.message().text());
            report("assistant_model", status.activeModel());
            report("assistant_ready_ms", millis(t2));
            passed &= status.isReady();
            servers = llamaServers();
            report("llama_servers_running", servers.size() + " " + servers.stream().map(ProcessHandle::pid).toList());
            passed &= !servers.isEmpty();

            settle();
            write(scene, out.resolve("real-home.png"));
            onFx(() -> {
                shown.navigator().navigate(Destination.SETTINGS);
                return null;
            });
            settle();
            onFx(() -> {
                if (shown.navigator().currentView() instanceof SettingsPage settings) {
                    settings.scrollTo(SettingsPage.Section.ASSISTANT);
                }
                return null;
            });
            settle();
            write(scene, out.resolve("real-settings-assistant.png"));
            onFx(() -> {
                theme.dispose();
                return null;
            });
        } finally {
            // Quit as the app does: the screens go, then the profile closes and the assistant stops.
            long t3 = System.nanoTime();
            MainWindow closing = window;
            if (closing != null) {
                onFx(() -> {
                    closing.dispose();
                    return null;
                });
            }
            context.close();
            report("quit_ms", millis(t3));
        }
        long t4 = System.nanoTime();
        long deadline = System.nanoTime() + EXIT_TIMEOUT.toNanos();
        while (servers.stream().anyMatch(ProcessHandle::isAlive) && System.nanoTime() < deadline) {
            Thread.sleep(50);
        }
        List<ProcessHandle> left = servers.stream().filter(ProcessHandle::isAlive).toList();
        report("processes_gone_ms", millis(t4));
        report("llama_servers_left", left.size() + " " + left.stream().map(ProcessHandle::pid).toList());
        report("descendants_left", ProcessHandle.current().descendants().map(RealRun::describe).toList());
        passed &= left.isEmpty() && llamaServers().isEmpty();
        report("result", passed ? "PASS" : "FAIL");
        return passed;
    }

    /** The study assistant processes this run started. */
    private static List<ProcessHandle> llamaServers() {
        return ProcessHandle.current().descendants()
                .filter(p -> p.info().command().map(c -> c.contains("llama-server")).orElse(false))
                .toList();
    }

    private static String describe(ProcessHandle process) {
        return process.pid() + " " + process.info().command().orElse("?");
    }

    private static void settle() throws Exception {
        for (int round = 0; round < 2; round++) {
            UiExecutors.awaitIdle(Duration.ofMinutes(2));
            onFx(() -> null);
        }
        Thread.sleep(900);
        onFx(() -> null);
    }

    private static void write(Scene scene, Path file) throws Exception {
        onFx(() -> {
            for (int pass = 0; pass < 2; pass++) {
                scene.getRoot().applyCss();
                scene.getRoot().layout();
            }
            WritableImage image = scene.snapshot(null);
            try {
                ImageIO.write(SwingFXUtils.fromFXImage(image, null), "png", file.toFile());
            } catch (IOException e) {
                throw new java.io.UncheckedIOException(e);
            }
            return null;
        });
        report("wrote", file);
    }

    private static long millis(long since) {
        return (System.nanoTime() - since) / 1_000_000;
    }

    private static void report(String key, Object value) {
        System.out.println("REAL_RUN " + key + "=" + value);
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
}
