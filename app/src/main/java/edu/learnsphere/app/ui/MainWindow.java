package edu.learnsphere.app.ui;

import edu.learnsphere.app.AppContext;
import edu.learnsphere.app.dev.ComponentGallery;
import edu.learnsphere.app.theme.Motion;
import edu.learnsphere.app.ui.ask.AskPage;
import edu.learnsphere.app.ui.components.Overlays;
import edu.learnsphere.app.ui.components.Page;
import edu.learnsphere.app.ui.components.TopBar;
import edu.learnsphere.app.ui.components.TopBar.Crumb;
import edu.learnsphere.app.ui.course.CoursePage;
import edu.learnsphere.app.ui.home.HomePage;
import edu.learnsphere.app.ui.library.LibraryPage;
import edu.learnsphere.app.ui.onboarding.OnboardingPage;
import edu.learnsphere.app.ui.profiles.ProfilesPage;
import edu.learnsphere.app.ui.profiles.UnlockPage;
import edu.learnsphere.app.ui.progress.ProgressPage;
import edu.learnsphere.app.ui.settings.SettingsPage;
import edu.learnsphere.app.ui.studio.StudioPage;
import edu.learnsphere.app.ui.study.StudyPage;
import edu.learnsphere.core.events.CourseChanged;
import edu.learnsphere.core.service.ProfileSummary;
import edu.learnsphere.core.service.Workspace;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;
import java.util.concurrent.ConcurrentHashMap;
import javafx.animation.KeyFrame;
import javafx.animation.KeyValue;
import javafx.animation.Timeline;
import javafx.beans.value.ChangeListener;
import javafx.geometry.Pos;
import javafx.scene.control.ScrollPane;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.shape.Rectangle;
import javafx.util.Duration;

/**
 * The window content: the {@link Sidebar} on the left, the {@link TopBar} with breadcrumbs and back above
 * the routed content, and the overlay layers for toasts and dialogs on top. Every route has its screen
 * registered here. The study session is focus mode: the sidebar folds away and the top bar hides;
 * onboarding and profile choice take the whole window too. Breadcrumbs name the course on screen and follow
 * it when it is renamed.
 */
public final class MainWindow extends StackPane {

    /** Width of the sidebar. */
    public static final double SIDEBAR_WIDTH = 232;

    private final AppContext context;
    private final Navigator navigator;
    private final Sidebar sidebar;
    private final StackPane sidebarHolder;
    private final TopBar topBar = new TopBar();
    private final AssistantIndicator assistant;
    private final Overlays overlays;
    private final Map<String, String> courseTitles = new ConcurrentHashMap<>();
    private final ChangeListener<Workspace> workspaceListener = (obs, old, workspace) -> workspaceChanged(old, workspace);
    private Timeline sidebarAnimation;
    private double targetWidth = SIDEBAR_WIDTH;
    private Disposer crumbScope = new Disposer();
    /** Subscriptions to the open profile's events; replaced when the profile changes. */
    private Disposer workspaceScope = new Disposer();
    /** The computer's file dialogs and file manager; tests and the screenshot tour put in their own. */
    private SystemFiles systemFiles = SystemFiles.standard();
    private final Supplier<SystemFiles> files = () -> systemFiles;

    public MainWindow(AppContext context) {
        this.context = context;
        getStyleClass().add("app-root");
        StackPane content = new StackPane();
        content.getStyleClass().add("content");
        VBox.setVgrow(content, Priority.ALWAYS);

        navigator = new Navigator(content, MainWindow::untitledPage);
        register(context);

        sidebar = new Sidebar(context, navigator);
        sidebarHolder = new StackPane(sidebar);
        sidebarHolder.getStyleClass().add("sidebar-holder");
        sidebarHolder.setAlignment(Pos.TOP_RIGHT);
        sidebarHolder.setMinWidth(0);
        sidebarHolder.setPrefWidth(SIDEBAR_WIDTH);
        sidebarHolder.setMaxWidth(SIDEBAR_WIDTH);
        Rectangle clip = new Rectangle();
        clip.widthProperty().bind(sidebarHolder.widthProperty());
        clip.heightProperty().bind(sidebarHolder.heightProperty());
        sidebarHolder.setClip(clip);

        topBar.canGoBackProperty().bind(navigator.canGoBackProperty());
        topBar.setOnBack(navigator::back);
        assistant = new AssistantIndicator(context, navigator);
        topBar.setTrailing(assistant);

        VBox main = new VBox(topBar, content);
        main.getStyleClass().add("main-column");
        BorderPane shell = new BorderPane(main);
        shell.getStyleClass().add("app-shell");
        shell.setLeft(sidebarHolder);
        getChildren().add(shell);
        overlays = Overlays.install(this);

        navigator.currentViewProperty().addListener((obs, old, view) -> viewChanged(view));
        navigator.currentProperty().addListener((obs, old, destination) -> routeChanged(old, destination));
        context.workspaceProperty().addListener(workspaceListener);
        followCourseTitles(context.workspace());
        navigator.reset(startDestination(context));
    }

    /**
     * Where the window opens: Home with a profile open; the welcome when there are no profiles yet; the
     * password screen when the profile used last time has a password; otherwise the profile choice.
     */
    public static Destination startDestination(AppContext context) {
        if (context.workspace() != null) {
            return Destination.HOME;
        }
        if (context.profiles().list().isEmpty()) {
            return Destination.ONBOARDING;
        }
        Optional<ProfileSummary> last = context.lastProfile();
        if (last.isPresent() && last.get().passwordProtected()) {
            return new Destination.Unlock(last.get().id());
        }
        return Destination.PROFILES;
    }

    private void register(AppContext context) {
        navigator.register(Destination.Home.class, d -> new HomePage(context, navigator));
        navigator.register(Destination.Library.class, d -> new LibraryPage(context, navigator));
        navigator.register(Destination.Course.class, d -> new CoursePage(context, navigator, d));
        navigator.register(Destination.Ask.class, d -> new AskPage(context, navigator, d));
        navigator.register(Destination.Study.class, d -> new StudyPage(context, navigator, d));
        navigator.register(Destination.Progress.class, d -> new ProgressPage(context, navigator, d));
        navigator.register(Destination.Studio.class, d -> new StudioPage(context, navigator, d));
        navigator.register(Destination.Settings.class, d -> new SettingsPage(context, navigator, files));
        navigator.register(Destination.Onboarding.class, d -> new OnboardingPage(context, navigator));
        navigator.register(Destination.AddProfile.class, d -> new OnboardingPage(context, navigator, true));
        navigator.register(Destination.Profiles.class, d -> new ProfilesPage(context, navigator, files));
        navigator.register(Destination.Unlock.class, d -> new UnlockPage(context, navigator, d));
        if (context.isDemo()) {
            navigator.register(Destination.Gallery.class, d -> new ComponentGallery());
        }
    }

    /** The application context the window shows. */
    public AppContext context() {
        return context;
    }

    public Navigator navigator() {
        return navigator;
    }

    public TopBar topBar() {
        return topBar;
    }

    public Sidebar sidebar() {
        return sidebar;
    }

    public AssistantIndicator assistantIndicator() {
        return assistant;
    }

    public Overlays overlays() {
        return overlays;
    }

    /** The file dialogs and file manager the profile and settings screens use. */
    public SystemFiles systemFiles() {
        return systemFiles;
    }

    /** Replaces the file dialogs and file manager (tests and the screenshot tour). */
    public void setSystemFiles(SystemFiles value) {
        systemFiles = Objects.requireNonNull(value, "value");
    }

    /** True while the sidebar is (or is becoming) visible. */
    public boolean isSidebarShown() {
        return targetWidth > 0;
    }

    /** The top bar lines up with the new view's content column. */
    private void viewChanged(View view) {
        topBar.setColumnMaxWidth(view instanceof PageView ? Page.MAX_WIDTH : Double.POSITIVE_INFINITY);
        // A scrolling page narrows its column by the scroll bar's width; the crumbs follow the column.
        topBar.alignWith(view != null && view.root() instanceof ScrollPane page ? page : null);
    }

    private void routeChanged(Destination old, Destination destination) {
        if (destination == null) {
            return;
        }
        Route route = destination.route();
        boolean full = route.isFullScreen();
        topBar.setVisible(!full);
        topBar.setManaged(!full);
        boolean animate = route.isFocusMode() || (old != null && old.route().isFocusMode());
        showSidebar(!full, animate);
        updateCrumbs(destination);
    }

    private void showSidebar(boolean show, boolean animate) {
        double target = show ? SIDEBAR_WIDTH : 0;
        if (target == targetWidth && sidebarAnimation == null) {
            sidebarHolder.setVisible(show);
            return;
        }
        targetWidth = target;
        if (sidebarAnimation != null) {
            sidebarAnimation.stop();
            sidebarAnimation = null;
        }
        if (!animate || Motion.reduced() || getScene() == null) {
            sidebarHolder.setPrefWidth(target);
            sidebarHolder.setVisible(show);
            return;
        }
        sidebarHolder.setVisible(true);
        sidebarAnimation = new Timeline(new KeyFrame(Duration.millis(240),
                new KeyValue(sidebarHolder.prefWidthProperty(), target, Motion.EASE_IN_OUT)));
        sidebarAnimation.setOnFinished(event -> {
            sidebarHolder.setVisible(show);
            sidebarAnimation = null;
        });
        sidebarAnimation.play();
    }

    private void workspaceChanged(Workspace old, Workspace workspace) {
        followCourseTitles(workspace);
        Destination current = navigator.current();
        if (current != null && current.route() == Route.ONBOARDING) {
            // Onboarding opens the new profile itself and finishes on its last step.
            return;
        }
        if (workspace != null && old != workspace) {
            navigator.reset(Destination.HOME);
        } else if (workspace == null && current != null && !current.route().isFullScreen()) {
            navigator.reset(Destination.PROFILES);
        }
    }

    /**
     * Forgets the course names of the previous profile and, while {@code workspace} is open, reloads a course's
     * name in the crumbs when the course changes (renamed in the Studio, say).
     */
    private void followCourseTitles(Workspace workspace) {
        workspaceScope.dispose();
        workspaceScope = new Disposer();
        courseTitles.clear();
        if (workspace == null) {
            return;
        }
        workspaceScope.add(Fx.subscribe(workspace.events(), CourseChanged.class, event -> {
            String previous = courseTitles.remove(event.courseId());
            Destination current = navigator.current();
            if (current != null && event.courseId().equals(courseOf(current))) {
                updateCrumbs(current, previous);
            }
        }));
    }

    /** Breadcrumbs for {@code destination}; course names load in the background. */
    private void updateCrumbs(Destination destination) {
        updateCrumbs(destination, null);
    }

    /**
     * Breadcrumbs for {@code destination}. A course name not known yet loads in the background; until it
     * arrives the crumbs show {@code interim} (the name before a change), or none.
     */
    private void updateCrumbs(Destination destination, String interim) {
        crumbScope.dispose();
        crumbScope = new Disposer();
        String courseId = courseOf(destination);
        Workspace workspace = context.workspace();
        if (courseId == null || courseTitles.containsKey(courseId) || workspace == null) {
            topBar.setCrumbs(crumbs(destination, courseId == null ? null : courseTitles.get(courseId)));
            return;
        }
        topBar.setCrumbs(crumbs(destination, interim));
        crumbScope.add(UiTasks.run(() -> workspace.courses().summary(courseId).title(), title -> {
            courseTitles.put(courseId, title);
            if (destination.equals(navigator.current())) {
                topBar.setCrumbs(crumbs(destination, title));
            }
        }, message -> { }));
    }

    private static String courseOf(Destination destination) {
        return switch (destination) {
            case Destination.Course d -> d.courseId();
            case Destination.Ask d -> d.courseId();
            case Destination.Studio d -> d.courseId();
            case Destination.Progress d -> d.courseId();
            case Destination.Study d -> d.request().courseId();
            default -> null;
        };
    }

    /** The crumbs for {@code destination}; {@code title} is its course's name, or null while it loads. */
    List<Crumb> crumbs(Destination destination, String title) {
        List<Crumb> list = new ArrayList<>();
        switch (destination) {
            case Destination.Course d -> {
                list.add(link("Library", Destination.LIBRARY));
                addTitle(list, title, null);
            }
            case Destination.Ask d -> {
                list.add(link("Library", Destination.LIBRARY));
                addTitle(list, title, new Destination.Course(d.courseId()));
                list.add(Crumb.current("Ask"));
            }
            case Destination.Studio d when d.courseId() != null -> {
                list.add(link("Studio", Destination.Studio.courses()));
                addTitle(list, title, null);
                list.add(Crumb.current(d.tab().title()));
            }
            case Destination.Progress d when d.courseId() != null -> {
                list.add(link("Progress", Destination.Progress.overview()));
                addTitle(list, title, null);
            }
            default -> list.add(Crumb.current(destination.route().title()));
        }
        return list;
    }

    private void addTitle(List<Crumb> list, String title, Destination target) {
        if (title != null) {
            list.add(target == null ? Crumb.current(title) : link(title, target));
        }
    }

    private Crumb link(String label, Destination target) {
        return new Crumb(label, () -> navigator.navigate(target));
    }

    /** A screen without a view: its title on an empty page. */
    private static View untitledPage(Destination destination) {
        javafx.scene.control.Label title = new javafx.scene.control.Label(destination.route().title());
        title.getStyleClass().add("text-display");
        edu.learnsphere.app.ui.components.Page page = new edu.learnsphere.app.ui.components.Page(title);
        return () -> page;
    }

    /** Disposes the screen on display and stops following the profile (window closing). */
    public void dispose() {
        context.workspaceProperty().removeListener(workspaceListener);
        workspaceScope.dispose();
        crumbScope.dispose();
        navigator.dispose();
        sidebar.dispose();
        assistant.dispose();
        if (sidebarAnimation != null) {
            sidebarAnimation.stop();
        }
    }
}
