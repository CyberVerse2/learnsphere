package edu.learnsphere.app.ui;

import edu.learnsphere.app.AppContext;
import edu.learnsphere.app.ui.components.Avatar;
import edu.learnsphere.app.ui.components.CourseCover;
import edu.learnsphere.app.ui.components.Formats;
import edu.learnsphere.app.ui.components.Icons;
import edu.learnsphere.app.ui.components.SphereArt;
import edu.learnsphere.core.events.CourseChanged;
import edu.learnsphere.core.events.ItemsChanged;
import edu.learnsphere.core.events.ProgressChanged;
import edu.learnsphere.core.service.CourseFilter;
import edu.learnsphere.core.service.CourseSummary;
import edu.learnsphere.core.service.ProfileSummary;
import edu.learnsphere.core.service.Workspace;
import edu.learnsphere.core.util.Cancellable;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import javafx.beans.value.ChangeListener;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.ContentDisplay;
import javafx.scene.control.Tooltip;
import javafx.scene.control.Label;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import org.kordamp.ikonli.Ikon;
import org.kordamp.ikonli.feather.Feather;

/**
 * App mark and name, primary navigation, the "Studying" list, then Settings and the profile chip at the
 * bottom. The selected item follows the navigator; the studying list and the chip follow the open profile.
 * The studying list, with each course's due badge, reloads when a course, its items or the learner's
 * progress change.
 */
public final class Sidebar extends VBox {

    /** Courses listed under "Studying". */
    public static final int STUDYING_LIMIT = 5;

    private final AppContext context;
    private final Navigator navigator;
    private final ToggleGroup group = new ToggleGroup();
    private final Map<Route, ToggleButton> items = new EnumMap<>(Route.class);
    private final Map<String, ToggleButton> courseItems = new HashMap<>();
    private final VBox studying = new VBox(2);
    private final HBox chip = new HBox();
    private final ChangeListener<Workspace> workspaceListener = (obs, old, workspace) -> showWorkspace(workspace);
    /** The chip follows renames, colour and password changes of the open profile. */
    private final ChangeListener<ProfileSummary> profileListener = (obs, old, profile) -> profileChanged(profile);
    private boolean severalProfiles;
    private Disposer workspaceScope = new Disposer();

    public Sidebar(AppContext context, Navigator navigator) {
        this.context = context;
        this.navigator = navigator;
        getStyleClass().add("sidebar");

        Label name = new Label("LearnSphere");
        name.getStyleClass().add("sidebar-brand-name");
        HBox brand = new HBox(SphereArt.mark(30), name);
        brand.getStyleClass().add("sidebar-brand");

        Label section = new Label("STUDYING");
        section.getStyleClass().addAll("text-overline", "sidebar-section");

        Region spacer = new Region();
        VBox.setVgrow(spacer, Priority.ALWAYS);
        Region divider = new Region();
        divider.getStyleClass().add("sidebar-divider");
        VBox.setMargin(divider, new Insets(10, 8, 8, 8));

        chip.getStyleClass().add("profile-chip");
        chip.setOnMouseClicked(event -> navigator.navigate(Destination.PROFILES));
        chip.setFocusTraversable(true);
        chip.setAccessibleRole(javafx.scene.AccessibleRole.BUTTON);
        chip.setOnKeyPressed(event -> {
            if (event.getCode() == javafx.scene.input.KeyCode.ENTER || event.getCode() == javafx.scene.input.KeyCode.SPACE) {
                event.consume();
                navigator.navigate(Destination.PROFILES);
            }
        });

        getChildren().addAll(brand,
                item(Route.HOME, Feather.HOME, Destination.HOME),
                item(Route.LIBRARY, Feather.BOOK_OPEN, Destination.LIBRARY),
                item(Route.PROGRESS, Feather.BAR_CHART_2, Destination.Progress.overview()),
                item(Route.STUDIO, Feather.EDIT_3, Destination.Studio.courses()),
                section, studying, spacer,
                item(Route.SETTINGS, Feather.SETTINGS, Destination.SETTINGS),
                divider,
                chip);

        group.selectedToggleProperty().addListener((obs, old, selected) -> {
            if (selected == null && old != null && old == buttonFor(navigator.current())) {
                old.setSelected(true);
            }
        });
        navigator.currentProperty().addListener((obs, old, destination) -> select(destination));
        context.workspaceProperty().addListener(workspaceListener);
        context.profileProperty().addListener(profileListener);
        showWorkspace(context.workspace());
    }

    /** Stops following the open profile (window closing). */
    public void dispose() {
        context.workspaceProperty().removeListener(workspaceListener);
        context.profileProperty().removeListener(profileListener);
        workspaceScope.dispose();
    }

    private ToggleButton item(Route route, Ikon icon, Destination destination) {
        ToggleButton button = new ToggleButton(route.title(), Icons.of(icon));
        button.getStyleClass().setAll("nav-item");
        button.setToggleGroup(group);
        button.setMaxWidth(Double.MAX_VALUE);
        button.setOnAction(event -> open(button, destination));
        items.put(route, button);
        return button;
    }

    /** Navigates to {@code destination}; clicking the item already on screen keeps it selected. */
    private void open(ToggleButton button, Destination destination) {
        if (destination.equals(navigator.current())) {
            button.setSelected(true);
        } else {
            navigator.navigate(destination);
        }
    }

    private ToggleButton buttonFor(Destination destination) {
        if (destination == null) {
            return null;
        }
        if (destination instanceof Destination.Course(String courseId) && courseItems.containsKey(courseId)) {
            return courseItems.get(courseId);
        }
        return items.get(destination.route());
    }

    private void select(Destination destination) {
        ToggleButton button = buttonFor(destination);
        if (button != null) {
            button.setSelected(true);
        } else if (group.getSelectedToggle() != null) {
            group.getSelectedToggle().setSelected(false);
        }
    }

    /** Rebuilds the studying list and the chip for {@code workspace} (null while no profile is open). */
    private void showWorkspace(Workspace workspace) {
        workspaceScope.dispose();
        workspaceScope = new Disposer();
        showCourses(List.of());
        if (workspace == null) {
            showChip(null, false);
            return;
        }
        Disposer scope = workspaceScope;
        showChip(openProfile(workspace), severalProfiles);
        scope.add(UiTasks.run(() -> context.profiles().list().size(), count -> {
            severalProfiles = count > 1;
            showChip(openProfile(workspace), severalProfiles);
        }, message -> showChip(openProfile(workspace), severalProfiles)));
        AtomicReference<Cancellable> pending = new AtomicReference<>();
        scope.onDispose(() -> pending.get().cancel());
        Runnable load = () -> {
            Cancellable previous = pending.getAndSet(UiTasks.run(
                    () -> workspace.courses().list(CourseFilter.studying()).stream().limit(STUDYING_LIMIT).toList(),
                    this::showCourses,
                    message -> showCourses(List.of())));
            if (previous != null) {
                previous.cancel();
            }
        };
        load.run();
        // Titles and enrolment change with the course; due badges change with every answer and approval.
        scope.add(Fx.subscribe(workspace.events(), CourseChanged.class, event -> load.run()));
        scope.add(Fx.subscribe(workspace.events(), ProgressChanged.class, event -> load.run()));
        scope.add(Fx.subscribe(workspace.events(), ItemsChanged.class, event -> load.run()));
    }

    private void showCourses(List<CourseSummary> courses) {
        courseItems.values().forEach(button -> button.setToggleGroup(null));
        courseItems.clear();
        if (courses.isEmpty()) {
            Label hint = new Label("Courses you study appear here.");
            hint.getStyleClass().add("sidebar-hint");
            hint.setWrapText(true);
            studying.getChildren().setAll(hint);
        } else {
            studying.getChildren().setAll(courses.stream().map(this::courseItem).toList());
        }
        select(navigator.current());
    }

    private ToggleButton courseItem(CourseSummary course) {
        CourseCover cover = new CourseCover(course.coverHue(), course.id(), CourseCover.Size.TINY);
        Label title = new Label(course.title());
        title.getStyleClass().add("sidebar-course-title");
        title.setMnemonicParsing(false);
        title.setMinWidth(0);
        title.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(title, Priority.ALWAYS);
        HBox row = new HBox(10, cover, title);
        row.setAlignment(Pos.CENTER_LEFT);
        if (course.dueCount() > 0) {
            Label badge = new Label(Integer.toString(course.dueCount()));
            badge.getStyleClass().add("nav-badge");
            badge.setMinWidth(Region.USE_PREF_SIZE);
            Tooltip.install(badge, new Tooltip(Formats.count(course.dueCount(), "review", "reviews") + " due"));
            row.getChildren().add(badge);
        }
        ToggleButton button = new ToggleButton(null, row);
        button.setContentDisplay(ContentDisplay.GRAPHIC_ONLY);
        button.getStyleClass().setAll("nav-item", "sidebar-course");
        button.setAccessibleText(course.title());
        button.setToggleGroup(group);
        button.setMaxWidth(Double.MAX_VALUE);
        row.prefWidthProperty().bind(button.widthProperty().subtract(20));
        row.maxWidthProperty().bind(row.prefWidthProperty());
        Destination destination = new Destination.Course(course.id());
        button.setOnAction(event -> open(button, destination));
        courseItems.put(course.id(), button);
        return button;
    }

    /** The due count on each course's badge in the "Studying" list, by course id (0 without a badge; tests). */
    public Map<String, Integer> studyingDueCounts() {
        Map<String, Integer> counts = new java.util.LinkedHashMap<>();
        courseItems.forEach((courseId, button) -> {
            Label badge = (Label) button.getGraphic().lookup(".nav-badge");
            counts.put(courseId, badge == null ? 0 : Integer.parseInt(badge.getText()));
        });
        return counts;
    }

    /** The titles in the "Studying" list, in order (tests). */
    public List<String> studyingTitles() {
        return studying.getChildren().stream()
                .filter(node -> node instanceof ToggleButton)
                .map(node -> ((ToggleButton) node).getAccessibleText())
                .toList();
    }

    private void profileChanged(ProfileSummary profile) {
        if (profile != null && context.workspace() != null) {
            showChip(profile, severalProfiles);
        }
    }

    /** The open profile as last published (it follows renames), else as the workspace opened it. */
    private ProfileSummary openProfile(Workspace workspace) {
        ProfileSummary current = context.profile();
        return current != null && current.id().equals(workspace.profile().id()) ? current : workspace.profile();
    }

    private void showChip(ProfileSummary profile, boolean several) {
        if (profile == null) {
            Label label = new Label("Choose a profile");
            label.getStyleClass().add("profile-chip-name");
            chip.getChildren().setAll(Icons.of(Feather.USER, 18), label);
            return;
        }
        Label name = new Label(profile.displayName());
        name.getStyleClass().add("profile-chip-name");
        Label detail = new Label(several ? "Switch profile" : "Your profile");
        detail.getStyleClass().add("profile-chip-detail");
        VBox text = new VBox(1, name, detail);
        HBox.setHgrow(text, Priority.ALWAYS);
        chip.getChildren().setAll(new Avatar(profile.initials(), profile.avatarHue(), 34), text,
                Icons.of(Feather.CHEVRON_RIGHT, 16));
    }
}
