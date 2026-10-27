package edu.learnsphere.app.dev;

import edu.learnsphere.app.AppContext;
import edu.learnsphere.app.Main;
import edu.learnsphere.app.theme.Theme;
import edu.learnsphere.app.theme.Typography;
import edu.learnsphere.app.ui.Destination;
import edu.learnsphere.app.ui.MainWindow;
import edu.learnsphere.app.ui.StudioTab;
import edu.learnsphere.app.ui.UiExecutors;
import edu.learnsphere.app.ui.library.LibraryPage;
import edu.learnsphere.app.ui.library.NewCourseDialog;
import edu.learnsphere.app.ui.study.StudyPage;
import edu.learnsphere.core.model.Citation;
import edu.learnsphere.core.model.Rating;
import edu.learnsphere.core.service.ExportOptions;
import edu.learnsphere.core.service.Response;
import edu.learnsphere.core.service.SessionItem;
import edu.learnsphere.core.service.SessionRequest;
import edu.learnsphere.core.service.StudySession;
import edu.learnsphere.core.service.Workspace;
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
import javafx.event.Event;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.TextInputControl;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.SnapshotParameters;
import javafx.scene.layout.Region;
import javafx.scene.paint.Color;
import javafx.scene.image.WritableImage;
import javax.imageio.ImageIO;

/**
 * Renders screens to PNG in light and dark without showing a window, then exits. Usage:
 * {@code ScreenshotTour <output folder>} (run through {@code ./gradlew :app:screenshots}). Each shot is
 * written as {@code <name>-light.png} and {@code <name>-dark.png} at {@value #WIDTH}×{@value #HEIGHT}.
 *
 * <p>The demo profile is open, so screens show {@link DemoWorkspace} content. The profile is opened afresh
 * for every capture, so each shot starts from the same demo content whatever earlier shots did (answering a
 * question changes due counts, for one) and light and dark show identical screens. Add a {@link Shot} to
 * {@link #shots()} for each new screen; it receives the main window and puts it in the state to capture.
 * Background loads finish before each capture.
 */
public final class ScreenshotTour {

    public static final int WIDTH = 1440;
    public static final int HEIGHT = 900;

    private static final Duration SETTLE_TIMEOUT = Duration.ofMinutes(2);
    /** Longer than the longest standard animation (progress, 600 ms). */
    private static final Duration ANIMATION_SETTLE = Duration.ofMillis(900);

    /**
     * One screen to capture: {@code prepare} puts the window in the state to capture; {@code then}, when not
     * null, runs once that state has loaded (to answer a question, say) and the window settles again.
     */
    public record Shot(String name, Consumer<MainWindow> prepare, Consumer<MainWindow> then) {

        public Shot(String name, Consumer<MainWindow> prepare) {
            this(name, prepare, null);
        }

        /** A shot of {@code destination}. */
        static Shot of(String name, Destination destination) {
            return new Shot(name, window -> window.navigator().reset(destination));
        }
    }

    private ScreenshotTour() {
    }

    /** Every route, a few states worth seeing, and the component gallery. */
    static List<Shot> shots() {
        String os = DemoContent.OPERATING_SYSTEMS;
        String chemistry = DemoContent.ORGANIC_CHEMISTRY;
        List<Shot> shots = new ArrayList<>(List.of(
                Shot.of("home", Destination.HOME),
                Shot.of("library", Destination.LIBRARY),
                new Shot("library-creating", window -> {
                    window.navigator().reset(Destination.LIBRARY);
                    if (window.navigator().currentView() instanceof LibraryPage library) {
                        library.shelf().setValue(LibraryPage.Shelf.CREATING);
                    }
                }),
                new Shot("course", window -> {
                    // Opened from the Library, so the top bar shows its back arrow.
                    window.navigator().reset(Destination.LIBRARY);
                    window.navigator().navigate(new Destination.Course(os));
                }),
                new Shot("ask", window -> window.navigator().reset(askWithSource(window, os))),
                Shot.of("study", new Destination.Study(SessionRequest.studyNext())),
                new Shot("study-feedback", window -> window.navigator().reset(new Destination.Study(SessionRequest.studyNext())),
                        ScreenshotTour::answerCurrentQuestion),
                Shot.of("progress", Destination.Progress.overview()),
                Shot.of("progress-course", new Destination.Progress(os)),
                Shot.of("studio", Destination.Studio.courses()),
                Shot.of("studio-review", new Destination.Studio(chemistry, StudioTab.REVIEW)),
                Shot.of("studio-documents", new Destination.Studio(chemistry, StudioTab.DOCUMENTS)),
                Shot.of("studio-pages", new Destination.Studio(chemistry, StudioTab.PAGES)),
                Shot.of("studio-details", new Destination.Studio(chemistry, StudioTab.DETAILS)),
                Shot.of("studio-publish", new Destination.Studio(chemistry, StudioTab.PUBLISH)),
                Shot.of("settings", Destination.SETTINGS),
                Shot.of("profiles", Destination.PROFILES),
                Shot.of("onboarding", Destination.ONBOARDING),
                new Shot("onboarding-profile", window -> window.navigator().reset(Destination.ONBOARDING),
                        window -> ((javafx.scene.control.Button) window.navigator().currentView().root()
                                .lookup(".button.primary")).fire()),
                Shot.of("gallery", Destination.GALLERY)));
        shots.addAll(homeLibraryCourseShots());
        return shots;
    }

    /** States of Home, the Library (with its sheets) and the course page. */
    private static List<Shot> homeLibraryCourseShots() {
        String os = DemoContent.OPERATING_SYSTEMS;
        String methods = DemoContent.RESEARCH_METHODS;
        String chemistry = DemoContent.ORGANIC_CHEMISTRY;
        return List.of(
                new Shot("home-scrolled", window -> window.navigator().reset(Destination.HOME),
                        ScreenshotTour::scrollToBottom),
                new Shot("home-caught-up", window -> {
                    Workspace workspace = window.context().workspace();
                    workspace.courses().leave(os);
                    studyEverything(workspace);
                    window.navigator().reset(Destination.HOME);
                }),
                new Shot("home-not-studying", window -> {
                    Workspace workspace = window.context().workspace();
                    workspace.courses().leave(os);
                    workspace.courses().leave(methods);
                    window.navigator().reset(Destination.HOME);
                }),
                new Shot("home-empty", window -> {
                    Workspace workspace = window.context().workspace();
                    for (String id : List.of(os, methods, chemistry)) {
                        workspace.courses().delete(id);
                    }
                    window.navigator().reset(Destination.HOME);
                }),
                new Shot("library-search-empty", window -> window.navigator().reset(Destination.LIBRARY),
                        window -> {
                            if (window.navigator().currentView() instanceof LibraryPage library) {
                                library.searchField().setText("Linear algebra");
                            }
                        }),
                new Shot("library-empty", window -> {
                    Workspace workspace = window.context().workspace();
                    for (String id : List.of(os, methods, chemistry)) {
                        workspace.courses().delete(id);
                    }
                    window.navigator().reset(Destination.LIBRARY);
                }),
                new Shot("library-studying-empty", window -> {
                    // Only the course made here is left, and the learner isn't studying it.
                    Workspace workspace = window.context().workspace();
                    workspace.courses().delete(os);
                    workspace.courses().delete(methods);
                    if (workspace.courses().summary(chemistry).enrolled()) {
                        workspace.courses().leave(chemistry);
                    }
                    window.navigator().reset(Destination.LIBRARY);
                }),
                new Shot("library-new-course", window -> window.navigator().reset(Destination.LIBRARY),
                        window -> {
                            if (window.navigator().currentView() instanceof LibraryPage library) {
                                NewCourseDialog dialog = library.openNewCourse();
                                dialog.titleField().setText("Cell Biology");
                                dialog.subtitleField().setText("From molecules to tissues");
                                dialog.picker().select(150);
                            }
                        }),
                new Shot("library-import", window -> window.navigator().reset(Destination.LIBRARY),
                        window -> {
                            // A protected course that is not in the library yet: the methods course, saved
                            // with a code and then removed.
                            Workspace workspace = window.context().workspace();
                            Path file = tourFile();
                            workspace.sharing().exportCourse(methods, file,
                                    ExportOptions.protectedWith("orbit".toCharArray()));
                            workspace.courses().delete(methods);
                            if (window.navigator().currentView() instanceof LibraryPage library) {
                                library.importFrom(file);
                            }
                        }),
                new Shot("library-import-own", window -> window.navigator().reset(Destination.LIBRARY),
                        window -> {
                            Workspace workspace = window.context().workspace();
                            Path file = tourFile();
                            workspace.sharing().exportCourse(chemistry, file, ExportOptions.standard());
                            if (window.navigator().currentView() instanceof LibraryPage library) {
                                library.importFrom(file);
                            }
                        }),
                new Shot("course-scrolled", window -> {
                    window.navigator().reset(Destination.LIBRARY);
                    window.navigator().navigate(new Destination.Course(os));
                }, ScreenshotTour::scrollToBottom),
                new Shot("course-creator", window -> {
                    window.navigator().reset(Destination.LIBRARY);
                    window.navigator().navigate(new Destination.Course(chemistry));
                }),
                new Shot("course-not-studying", window -> {
                    window.context().workspace().courses().leave(os);
                    window.navigator().reset(Destination.LIBRARY);
                    window.navigator().navigate(new Destination.Course(os));
                }),
                new Shot("course-archived", window -> {
                    window.context().workspace().courses().archive(chemistry);
                    window.navigator().reset(Destination.LIBRARY);
                    window.navigator().navigate(new Destination.Course(chemistry));
                }),
                new Shot("course-caught-up", window -> {
                    studyEverything(window.context().workspace());
                    window.navigator().reset(Destination.LIBRARY);
                    window.navigator().navigate(new Destination.Course(methods));
                }));
    }

    /** Answers every question the courses being studied offer today, so nothing is left to do. */
    private static void studyEverything(Workspace workspace) {
        for (int round = 0; round < 40 && !workspace.practice().studyNext(null).caughtUp(); round++) {
            StudySession session = workspace.practice().start(SessionRequest.studyNext());
            if (session.items().isEmpty()) {
                break;
            }
            for (SessionItem item : session.items()) {
                workspace.practice().submit(session.id(), Response.flashcard(item.itemId(), Rating.GOOD, 9_000));
            }
            workspace.practice().finish(session.id());
        }
    }

    /** Scrolls the page on screen to its end. */
    private static void scrollToBottom(MainWindow window) {
        if (window.navigator().currentView().root() instanceof javafx.scene.control.ScrollPane scroll) {
            scroll.applyCss();
            scroll.layout();
            scroll.setVvalue(scroll.getVmax());
        }
    }

    /** A course file for the import shots, removed when the tour ends. */
    private static Path tourFile() {
        try {
            Path file = Files.createTempFile("learnsphere-tour-", ".learnsphere");
            file.toFile().deleteOnExit();
            return file;
        } catch (IOException e) {
            throw new IllegalStateException("Cannot create a course file for the tour", e);
        }
    }

    /** The demo conversation about {@code courseId} with its first citation open in the source viewer. */
    private static Destination askWithSource(MainWindow window, String courseId) {
        var ask = window.context().workspace().ask();
        var thread = ask.threads(courseId).getFirst();
        Citation first = ask.messages(thread.id()).stream().flatMap(m -> m.citations().stream()).findFirst().orElse(null);
        return new Destination.Ask(courseId, thread.id(), first);
    }

    /**
     * Answers the question on screen in a study session through the keyboard, as a learner would: the second
     * option (or a typed answer, or turning a flashcard and rating it Good), then Enter. Does nothing when no
     * study session is on screen. FX thread.
     */
    public static void answerCurrentQuestion(MainWindow window) {
        if (!(window.navigator().currentView() instanceof StudyPage study)) {
            return;
        }
        // The question sits in a scroll pane, whose content joins the scene only once its skin exists; an
        // off-screen scene gets skins on its first CSS pass.
        if (window.getScene() != null) {
            window.getScene().getRoot().applyCss();
            window.getScene().getRoot().layout();
        }
        Node root = study.root();
        if (root.lookup(".study-input") instanceof TextInputControl input) {
            input.setText("The time a process waits in the ready queue");
            press(root, KeyCode.ENTER, "\r");
        } else if (root.lookup(".flashcard") != null) {
            press(root, KeyCode.SPACE, " ");
            press(root, KeyCode.DIGIT3, "3");
        } else {
            press(root, KeyCode.DIGIT2, "2");
            press(root, KeyCode.ENTER, "\r");
        }
    }

    private static void press(Node target, KeyCode code, String text) {
        Event.fireEvent(target, new KeyEvent(KeyEvent.KEY_PRESSED, text, text, code, false, false, false, false));
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
            for (Shot shot : shots()) {
                for (ThemePreference preference : List.of(ThemePreference.LIGHT, ThemePreference.DARK)) {
                    // The demo profile afresh (opening it closes the previous one): every capture starts
                    // from the same content.
                    context.openLastProfile().orElseThrow(() -> new IllegalStateException("The demo profile did not open"));
                    settle();
                    // A fresh scene per theme: snapshots of an off-screen scene can reuse cached renderings.
                    Capture capture = onFx(() -> {
                        MainWindow window = new MainWindow(context);
                        Scene scene = new Scene(window, WIDTH, HEIGHT);
                        Theme theme = Theme.install(scene, preference);
                        shot.prepare().accept(window);
                        return new Capture(window, scene, theme);
                    });
                    settle();
                    if (shot.then() != null) {
                        onFx(() -> {
                            shot.then().accept(capture.window());
                            return null;
                        });
                        settle();
                    }
                    Path file = out.resolve(shot.name() + "-" + preference.code() + ".png");
                    System.out.println("Rendering " + file.getFileName());
                    Path full = out.resolve(shot.name() + "-full-" + preference.code() + ".png");
                    files.addAll(onFx(() -> {
                        try {
                            List<Path> written = new ArrayList<>();
                            written.add(write(capture.scene(), file));
                            if (capture.window().navigator().currentView() instanceof ComponentGallery gallery) {
                                written.add(writeNode(gallery.content(), full));
                            }
                            return written;
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

    /**
     * Waits for background loads and the FX updates they queue (twice, for loads started by results), then
     * for entrance and progress animations to finish.
     */
    private static void settle() throws Exception {
        for (int round = 0; round < 2; round++) {
            if (!UiExecutors.awaitIdle(SETTLE_TIMEOUT)) {
                throw new IllegalStateException("Background loads did not finish within " + SETTLE_TIMEOUT);
            }
            onFx(() -> null);
        }
        Thread.sleep(ANIMATION_SETTLE.toMillis());
        onFx(() -> null);
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

    /** Writes {@code node} at its full laid-out size (the whole gallery, beyond the window's height). */
    private static Path writeNode(Region node, Path file) throws IOException {
        node.applyCss();
        node.layout();
        SnapshotParameters parameters = new SnapshotParameters();
        parameters.setFill(Color.TRANSPARENT);
        parameters.setViewport(new javafx.geometry.Rectangle2D(0, 0, node.getWidth(), node.getHeight()));
        WritableImage image = node.snapshot(parameters, null);
        ImageIO.write(SwingFXUtils.fromFXImage(image, null), "png", file.toFile());
        return file;
    }

    private static Path write(Scene scene, Path file) throws IOException {
        // Twice: the top bar lines up with the page's viewport, which is only known after the page's layout.
        for (int pass = 0; pass < 2; pass++) {
            scene.getRoot().applyCss();
            scene.getRoot().layout();
        }
        WritableImage image = scene.snapshot(null);
        ImageIO.write(SwingFXUtils.fromFXImage(image, null), "png", file.toFile());
        return file;
    }
}
