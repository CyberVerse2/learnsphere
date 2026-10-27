package edu.learnsphere.app.dev;

import edu.learnsphere.app.AppContext;
import edu.learnsphere.app.Main;
import edu.learnsphere.app.theme.Theme;
import edu.learnsphere.app.theme.Typography;
import edu.learnsphere.app.ui.Destination;
import edu.learnsphere.app.ui.MainWindow;
import edu.learnsphere.app.ui.StudioTab;
import edu.learnsphere.app.ui.UiExecutors;
import edu.learnsphere.app.ui.ask.AskPage;
import edu.learnsphere.app.ui.library.LibraryPage;
import edu.learnsphere.app.ui.library.NewCourseDialog;
import edu.learnsphere.app.ui.onboarding.OnboardingPage;
import edu.learnsphere.app.ui.profiles.ProfilesPage;
import edu.learnsphere.app.ui.profiles.UnlockPage;
import edu.learnsphere.app.ui.settings.LicencesDialog;
import edu.learnsphere.app.ui.settings.SettingsPage;
import edu.learnsphere.app.ui.study.StudyPage;
import edu.learnsphere.core.AppPaths;
import edu.learnsphere.core.events.AssistantStatusChanged;
import edu.learnsphere.core.model.Citation;
import edu.learnsphere.core.model.Rating;
import edu.learnsphere.core.service.AssistantStatus;
import edu.learnsphere.core.service.ExportOptions;
import edu.learnsphere.core.service.ProfileDraft;
import edu.learnsphere.core.service.ProfileSummary;
import edu.learnsphere.core.service.Response;
import edu.learnsphere.core.service.SessionItem;
import edu.learnsphere.core.service.SessionRequest;
import edu.learnsphere.core.service.StudySession;
import edu.learnsphere.core.service.ThemePreference;
import edu.learnsphere.core.service.Workspace;
import edu.learnsphere.core.spi.ModelChoice;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
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
 * <p>The demo profile is open, so screens show {@link DemoWorkspace} content. The profile is closed and
 * opened afresh for every capture, so each shot starts from the same demo content whatever earlier shots did
 * (answering a question changes due counts, for one) and light and dark show identical screens. Three more
 * profiles sit beside it (one with the password {@value #DEMO_PASSWORD}, one with a long name) for the
 * profile choice and the password screen. Add a {@link Shot} to {@link #shots()} for each new screen; it
 * receives the main window and puts it in the state to capture. Background loads finish before each capture.
 *
 * <p>The system property {@value #ONLY_PROPERTY} (from {@code -Pshots=name,prefix}) renders only the shots
 * whose names start with one of the comma-separated values.
 */
public final class ScreenshotTour {

    public static final int WIDTH = 1440;
    public static final int HEIGHT = 900;
    /** System property limiting the tour to shots whose names start with one of its comma-separated values. */
    public static final String ONLY_PROPERTY = "learnsphere.shots";
    /** The password of the demo's protected profile. */
    public static final String DEMO_PASSWORD = "lantern";
    /** The demo's profile with a password. */
    public static final String PROTECTED_PROFILE = "Theo Park";
    /** The demo's profile with a long name. */
    public static final String LONG_PROFILE = "Alexandra Featherstonehaugh-Montgomery";

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
                Shot.of("ask-new", Destination.Ask.newThread(os)),
                new Shot("ask-wide", window -> window.navigator().reset(askThread(window, os))),
                new Shot("ask-streaming", window -> window.navigator().reset(Destination.Ask.newThread(os)),
                        window -> askOnScreen(window, "How does round robin decide which process runs next?")),
                new Shot("ask-scanned", window -> window.navigator().reset(askScannedPage(window, chemistry))),
                new Shot("ask-many", window -> window.navigator().reset(askWithManyThreads(window, os))),
                Shot.of("study", new Destination.Study(SessionRequest.studyNext())),
                new Shot("study-feedback", window -> window.navigator().reset(new Destination.Study(SessionRequest.studyNext())),
                        ScreenshotTour::answerCurrentQuestion),
                Shot.of("progress", Destination.Progress.overview()),
                Shot.of("progress-course", new Destination.Progress(os)),
                new Shot("progress-scrolled", window -> window.navigator().reset(Destination.Progress.overview()),
                        ScreenshotTour::scrollToEnd),
                new Shot("progress-tooltip", window -> window.navigator().reset(Destination.Progress.overview()),
                        window -> {
                            scrollToEnd(window);
                            inspectChart(window, ".forecast-chart", 4);
                        }),
                new Shot("progress-course-scrolled", window -> window.navigator().reset(new Destination.Progress(os)),
                        ScreenshotTour::scrollToEnd),
                new Shot("progress-week-off", window -> {
                    forgetWeek(window, 2);
                    window.navigator().reset(new Destination.Progress(os));
                }, window -> {
                    scrollToEnd(window);
                    inspectChart(window, ".retention-chart", 2);
                }),
                new Shot("progress-creator", window -> {
                    DemoClassProgress.seed(window.context().workspace(), chemistry);
                    window.navigator().reset(new Destination.Progress(chemistry));
                }),
                new Shot("progress-creator-scrolled", window -> {
                    DemoClassProgress.seed(window.context().workspace(), chemistry);
                    window.navigator().reset(new Destination.Progress(chemistry));
                }, ScreenshotTour::scrollToEnd),
                Shot.of("progress-creator-empty", new Destination.Progress(chemistry)),
                new Shot("progress-new-learner", window -> {
                    forgetStudy(window);
                    window.navigator().reset(Destination.Progress.overview());
                }),
                Shot.of("progress-error", new Destination.Progress("a-course-that-does-not-exist")),
                new Shot("progress-no-courses", window -> {
                    var courses = window.context().workspace().courses();
                    courses.list(edu.learnsphere.core.service.CourseFilter.studying()).forEach(c -> courses.leave(c.id()));
                    window.navigator().reset(Destination.Progress.overview());
                }),
                Shot.of("studio", Destination.Studio.courses()),
                Shot.of("studio-review", new Destination.Studio(chemistry, StudioTab.REVIEW)),
                Shot.of("studio-documents", new Destination.Studio(chemistry, StudioTab.DOCUMENTS)),
                Shot.of("studio-pages", new Destination.Studio(chemistry, StudioTab.PAGES)),
                Shot.of("studio-details", new Destination.Studio(chemistry, StudioTab.DETAILS)),
                Shot.of("studio-publish", new Destination.Studio(chemistry, StudioTab.PUBLISH)),
                Shot.of("settings", Destination.SETTINGS),
                new Shot("settings-assistant", window -> window.navigator().reset(Destination.SETTINGS),
                        window -> settings(window).scrollTo(SettingsPage.Section.ASSISTANT)),
                new Shot("settings-assistant-unavailable", window -> window.navigator().reset(Destination.SETTINGS),
                        window -> {
                            assistant(window, AssistantStatus.unavailable());
                            settings(window).scrollTo(SettingsPage.Section.ASSISTANT);
                        }),
                new Shot("settings-storage", window -> window.navigator().reset(Destination.SETTINGS),
                        window -> settings(window).scrollTo(SettingsPage.Section.STORAGE)),
                new Shot("settings-licences", window -> window.navigator().reset(Destination.SETTINGS),
                        window -> LicencesDialog.show(settings(window).root())),
                Shot.of("profiles", Destination.PROFILES),
                new Shot("profiles-edit", window -> window.navigator().reset(Destination.PROFILES),
                        window -> profiles(window).actions().edit(window.context().profile(),
                                profiles(window).root(), saved -> { })),
                new Shot("profiles-password", window -> window.navigator().reset(Destination.PROFILES),
                        window -> profiles(window).actions().addPassword(window.context().profile(),
                                profiles(window).root(), saved -> { })),
                new Shot("profiles-delete-open", window -> window.navigator().reset(Destination.PROFILES),
                        window -> profiles(window).actions().delete(window.context().profile(),
                                profiles(window).root(), () -> { }, () -> { })),
                new Shot("profiles-delete", window -> window.navigator().reset(Destination.PROFILES),
                        window -> profiles(window).actions().delete(profileNamed(window, LONG_PROFILE),
                                profiles(window).root(), () -> { }, () -> { })),
                new Shot("profiles-backup-protected", window -> window.navigator().reset(Destination.PROFILES),
                        window -> profiles(window).actions().backup(profileNamed(window, PROTECTED_PROFILE),
                                profiles(window).root())),
                new Shot("profiles-delete-protected", window -> window.navigator().reset(Destination.PROFILES),
                        window -> profiles(window).actions().delete(profileNamed(window, PROTECTED_PROFILE),
                                profiles(window).root(), () -> { }, () -> { })),
                new Shot("unlock", window -> window.navigator().reset(
                        new Destination.Unlock(profileNamed(window, PROTECTED_PROFILE).id()))),
                new Shot("unlock-wrong", window -> window.navigator().reset(
                        new Destination.Unlock(profileNamed(window, PROTECTED_PROFILE).id())), window -> {
                            UnlockPage unlock = (UnlockPage) window.navigator().currentView();
                            unlock.passwordField().setText("lanturn");
                            unlock.passwordField().fireEvent(new javafx.event.ActionEvent());
                        }),
                Shot.of("onboarding", Destination.ONBOARDING),
                new Shot("onboarding-profile", window -> window.navigator().reset(Destination.ONBOARDING),
                        window -> {
                            OnboardingPage page = onboarding(window, OnboardingPage.Step.PROFILE);
                            page.nameField().setText(DemoData.PROFILE_NAME);
                            page.passwordField().setText(DEMO_PASSWORD);
                            page.confirmField().setText(DEMO_PASSWORD);
                            page.colours().setHue(DemoData.PROFILE_HUE);
                        }),
                new Shot("onboarding-profile-error", window -> window.navigator().reset(Destination.ONBOARDING),
                        window -> {
                            OnboardingPage page = onboarding(window, OnboardingPage.Step.PROFILE);
                            page.nameField().setText("Amara");
                            page.passwordField().setText(DEMO_PASSWORD);
                            page.confirmField().setText("lanterns");
                            ((javafx.scene.control.Button) page.root().lookup(".button.primary")).fire();
                        }),
                new Shot("onboarding-assistant", window -> window.navigator().reset(Destination.ONBOARDING),
                        window -> onboarding(window, OnboardingPage.Step.ASSISTANT)),
                new Shot("onboarding-assistant-starting", window -> window.navigator().reset(Destination.ONBOARDING),
                        window -> {
                            onboarding(window, OnboardingPage.Step.ASSISTANT);
                            assistant(window, AssistantStatus.starting(ModelChoice.CHAT_4B));
                        }),
                new Shot("onboarding-assistant-unavailable", window -> window.navigator().reset(Destination.ONBOARDING),
                        window -> {
                            onboarding(window, OnboardingPage.Step.ASSISTANT);
                            assistant(window, AssistantStatus.unavailable());
                        }),
                Shot.of("add-profile", Destination.ADD_PROFILE),
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

    /** The shots {@value #ONLY_PROPERTY} asks for, or all of them. */
    static List<Shot> selectedShots() {
        String only = System.getProperty(ONLY_PROPERTY, "").strip();
        if (only.isEmpty()) {
            return shots();
        }
        List<String> prefixes = Arrays.stream(only.split(",")).map(String::strip).filter(p -> !p.isEmpty()).toList();
        return shots().stream().filter(shot -> prefixes.stream().anyMatch(shot.name()::startsWith)).toList();
    }

    private static SettingsPage settings(MainWindow window) {
        return (SettingsPage) window.navigator().currentView();
    }

    private static ProfilesPage profiles(MainWindow window) {
        return (ProfilesPage) window.navigator().currentView();
    }

    private static OnboardingPage onboarding(MainWindow window, OnboardingPage.Step step) {
        OnboardingPage page = (OnboardingPage) window.navigator().currentView();
        page.showStep(step);
        return page;
    }

    /** Shows {@code status} as the study assistant's, as its own change would. */
    private static void assistant(MainWindow window, AssistantStatus status) {
        window.context().workspace().events().publish(new AssistantStatusChanged(status));
    }

    private static ProfileSummary profileNamed(MainWindow window, String name) {
        return window.context().profiles().list().stream().filter(p -> p.displayName().equals(name)).findFirst()
                .orElseThrow(() -> new IllegalStateException("No demo profile named " + name));
    }

    /** The profiles beside the demo one: one with a password, one with a long name, one more for the grid. */
    private static void seedOtherProfiles(AppContext context) {
        if (context.profiles().list().size() > 1) {
            return;
        }
        // Opened earlier than the demo profile, so the demo profile stays the one opened last.
        context.profiles().create(new ProfileDraft(PROTECTED_PROFILE, 190, DEMO_PASSWORD.toCharArray()));
        context.profiles().create(new ProfileDraft(LONG_PROFILE, 268, null));
        context.profiles().create(new ProfileDraft("Lena Fischer", 320, null));
    }

    /** Scrolls the page on screen to its end. FX thread. */
    private static void scrollToEnd(MainWindow window) {
        if (window.navigator().currentView().root() instanceof javafx.scene.control.ScrollPane page) {
            page.applyCss();
            page.layout();
            page.setVvalue(page.getVmax());
        }
    }

    /** Shows the tooltip of item {@code index} of the first chart matching {@code selector}. FX thread. */
    private static void inspectChart(MainWindow window, String selector, int index) {
        Node root = window.navigator().currentView().root();
        if (window.getScene() != null) {
            window.getScene().getRoot().applyCss();
            window.getScene().getRoot().layout();
        }
        if (root.lookup(selector) instanceof edu.learnsphere.app.ui.components.charts.CanvasChart chart) {
            chart.inspect(index);
        }
    }

    /** Takes the demo profile back to before its first study session. FX thread. */
    private static void forgetStudy(MainWindow window) {
        if (window.context().workspace().progress() instanceof DemoProgress demo) {
            DemoStore store = demo.store();
            synchronized (store) {
                store.attempts.clear();
                store.reviews.clear();
                store.studyTime.clear();
            }
        }
    }

    /** Removes the demo's answers from the week {@code weeksAgo} weeks before this one: a week off. FX thread. */
    private static void forgetWeek(MainWindow window, int weeksAgo) {
        if (window.context().workspace().progress() instanceof DemoProgress demo) {
            DemoStore store = demo.store();
            synchronized (store) {
                java.time.LocalDate monday = store.today()
                        .with(java.time.temporal.TemporalAdjusters.previousOrSame(java.time.DayOfWeek.MONDAY))
                        .minusWeeks(weeksAgo);
                long from = store.startOfDay(monday);
                long to = store.startOfDay(monday.plusWeeks(1));
                store.attempts.removeIf(attempt -> attempt.createdAt() >= from && attempt.createdAt() < to);
            }
        }
    }

    /** The demo conversation about {@code courseId} with its first citation open in the source viewer. */
    private static Destination askWithSource(MainWindow window, String courseId) {
        var ask = window.context().workspace().ask();
        var thread = ask.threads(courseId).getFirst();
        Citation first = ask.messages(thread.id()).stream().flatMap(m -> m.citations().stream()).findFirst().orElse(null);
        return new Destination.Ask(courseId, thread.id(), first);
    }

    /** The demo conversation about {@code courseId}, with the source viewer closed. */
    private static Destination askThread(MainWindow window, String courseId) {
        return new Destination.Ask(courseId, window.context().workspace().ask().threads(courseId).getFirst().id(), null);
    }

    /** Asks {@code question} from the question box of the Ask screen on show (the answer is caught mid-way). */
    private static void askOnScreen(MainWindow window, String question) {
        if (window.navigator().currentView() instanceof AskPage ask) {
            ask.askFromBox(question);
        }
    }

    /** A new conversation about {@code courseId} with page 2 of its scanned handout open in the source viewer. */
    private static Destination askScannedPage(MainWindow window, String courseId) {
        var documents = window.context().workspace().documents();
        var scanned = documents.list(courseId).stream().filter(d -> d.title().contains("scanned")).findFirst().orElseThrow();
        String text = documents.page(scanned.id(), 2).text();
        String firstParagraph = text.split("\n+")[0];
        return new Destination.Ask(courseId, null, new Citation(1, null, scanned.id(), scanned.title(), 2, 2, firstParagraph));
    }

    /** The demo conversation about {@code courseId} among many others, to show a long list of conversations. */
    private static Destination askWithManyThreads(MainWindow window, String courseId) {
        var ask = window.context().workspace().ask();
        String current = ask.threads(courseId).getFirst().id();
        for (String title : List.of("Deadlock conditions", "Semaphores versus mutexes", "Virtual memory basics",
                "What a page fault costs", "Shortest job first", "Priority inversion", "Disk scheduling",
                "Inodes and directories", "Why preemption matters", "Copy on write")) {
            ask.createThread(courseId, title);
        }
        return new Destination.Ask(courseId, current, null);
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
        try {
            // The demo profiles were made for this tour alone.
            TemporaryHome.deleteIfTemporary(AppPaths.resolve());
        } catch (RuntimeException e) {
            System.err.println("Could not delete the tour's app home: " + e.getMessage());
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
            String demoId = context.profiles().list().stream()
                    .filter(p -> p.displayName().equals(DemoData.PROFILE_NAME)).findFirst().orElseThrow().id();
            seedOtherProfiles(context);
            context.preferences().update(p -> p.withLastProfileId(demoId));
            for (Shot shot : selectedShots()) {
                for (ThemePreference preference : List.of(ThemePreference.LIGHT, ThemePreference.DARK)) {
                    // The demo profile afresh: every capture starts from the same content.
                    context.closeWorkspace();
                    context.open(demoId, null);
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
