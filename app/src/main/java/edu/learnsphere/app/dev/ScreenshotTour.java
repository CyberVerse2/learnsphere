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
import edu.learnsphere.app.ui.studio.StudioFiles;
import edu.learnsphere.app.ui.studio.StudioPage;
import edu.learnsphere.app.ui.study.StudyPage;
import edu.learnsphere.core.model.Citation;
import edu.learnsphere.core.service.CourseDraft;
import edu.learnsphere.core.service.SessionRequest;
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
import javafx.scene.control.ButtonBase;
import javafx.scene.control.TextInputControl;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.SnapshotParameters;
import javafx.scene.layout.Region;
import javafx.scene.paint.Color;
import javafx.stage.Window;
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
        return List.of(
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
                new Shot("studio-modules", window -> studio(window, new Destination.Studio(chemistry, StudioTab.MODULES))),
                new Shot("studio-new-course", window -> studio(window, Destination.Studio.courses()),
                        window -> fire(window, ".new-course-button")),
                new Shot("studio-many", window -> {
                    addCourses(window);
                    studio(window, Destination.Studio.courses());
                }),
                new Shot("studio-details-invalid", window -> studio(window, new Destination.Studio(chemistry, StudioTab.DETAILS)),
                        window -> {
                            window.applyCss();
                            ((TextInputControl) window.lookup(".details-title")).setText("");
                            fire(window, ".details-save");
                        }),
                new Shot("studio-documents-preview", window -> studio(window, new Destination.Studio(chemistry, StudioTab.DOCUMENTS)),
                        window -> fire(window, ".document-preview")),
                new Shot("studio-pages-checked", window -> studio(window,
                        new Destination.Studio(chemistry, StudioTab.PAGES)), window -> {
                    fire(window, ".approve-all");
                    fire(window, ".modal-confirm");
                }),
                new Shot("studio-details-sharing", window -> studio(window, new Destination.Studio(chemistry, StudioTab.DETAILS)),
                        window -> focus(window, ".details-delete")),
                new Shot("studio-pages-unsaved", window -> studio(window, new Destination.Studio(chemistry, StudioTab.PAGES)),
                        window -> {
                            window.applyCss();
                            ((TextInputControl) window.lookup(".scanned-text")).appendText("\n\nA line the creator added.");
                            window.lookupAll(".icon-button").stream()
                                    .filter(n -> "Next page".equals(n.getAccessibleText()) && n instanceof ButtonBase)
                                    .findFirst().map(ButtonBase.class::cast).orElseThrow().fire();
                        }),
                new Shot("studio-publish-exported", window -> {
                    window.context().workspace().courses().publish(chemistry, "First edition for the spring term.");
                    studio(window, new Destination.Studio(chemistry, StudioTab.PUBLISH));
                }, window -> fire(window, ".export-course")),
                Shot.of("settings", Destination.SETTINGS),
                Shot.of("profiles", Destination.PROFILES),
                Shot.of("onboarding", Destination.ONBOARDING),
                new Shot("onboarding-profile", window -> window.navigator().reset(Destination.ONBOARDING),
                        window -> ((javafx.scene.control.Button) window.navigator().currentView().root()
                                .lookup(".button.primary")).fire()),
                Shot.of("gallery", Destination.GALLERY));
    }

    /** Shows a Studio destination whose file dialogs answer at once (exports go to a temporary folder). */
    private static void studio(MainWindow window, Destination.Studio destination) {
        window.navigator().register(Destination.Studio.class,
                d -> new StudioPage(window.context(), window.navigator(), d, TourFiles.INSTANCE));
        window.navigator().reset(destination);
    }

    /** A few more courses in the Studio, one of them archived. */
    private static void addCourses(MainWindow window) {
        var courses = window.context().workspace().courses();
        String author = window.context().workspace().profile().displayName();
        List<String[]> drafts = List.of(
                new String[] {"Cell Biology", "From membranes to mitosis"},
                new String[] {"Introduction to Statistics", "Describing data, probability and inference"},
                new String[] {"Academic Writing", ""},
                new String[] {"Thermodynamics for Engineers", "Energy, entropy and the laws that govern them"},
                new String[] {"History of Computing", "Babbage to the browser"});
        String last = null;
        for (String[] draft : drafts) {
            last = courses.create(new CourseDraft(draft[0], draft[1], null, author, null)).id();
        }
        courses.archive(last);
    }

    /** Moves keyboard focus to the first node matching {@code selector}, scrolling it into view. */
    private static void focus(MainWindow window, String selector) {
        window.applyCss();
        window.layout();
        Node node = window.lookup(selector);
        if (node == null) {
            throw new IllegalStateException("Nothing matches " + selector);
        }
        node.requestFocus();
    }

    /** Fires the first button matching {@code selector} in the window. */
    private static void fire(MainWindow window, String selector) {
        // Off screen, controls get their skins (and scroll panes their content) only when CSS is applied.
        window.applyCss();
        window.layout();
        Node node = window.lookup(selector);
        if (node instanceof ButtonBase button) {
            button.fire();
        } else {
            throw new IllegalStateException("No button " + selector + " on screen");
        }
    }

    /** File dialogs for the tour: nothing to choose, and course files saved in a temporary folder. */
    private enum TourFiles implements StudioFiles {
        INSTANCE;

        @Override
        public List<Path> choosePdfs(Window owner) {
            return List.of();
        }

        @Override
        public Path chooseCourseFile(Window owner, String suggestedName) {
            try {
                Path folder = Files.createTempDirectory("learnsphere-tour");
                folder.toFile().deleteOnExit();
                Path file = folder.resolve(suggestedName);
                file.toFile().deleteOnExit();
                return file;
            } catch (IOException e) {
                throw new java.io.UncheckedIOException(e);
            }
        }

        @Override
        public List<Path> chooseProgressFiles(Window owner) {
            return List.of();
        }

        @Override
        public Path chooseFeedbackFile(Window owner) {
            return null;
        }

        @Override
        public void reveal(Path file) {
            // Nothing to show during the tour.
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
