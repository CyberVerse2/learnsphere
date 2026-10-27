package edu.learnsphere.app.ui;

import edu.learnsphere.app.AppContext;
import edu.learnsphere.app.ui.components.Avatar;
import edu.learnsphere.app.ui.components.Icons;
import edu.learnsphere.app.ui.components.SphereArt;
import edu.learnsphere.core.events.CourseChanged;
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
        showWorkspace(context.workspace());
    }

    /** Stops following the open profile (window closing). */
    public void dispose() {
        context.workspaceProperty().removeListener(workspaceListener);
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
        scope.add(UiTasks.run(() -> context.profiles().list().size(),
                count -> showChip(workspace.profile(), count > 1),
                message -> showChip(workspace.profile(), false)));
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
        scope.add(Fx.subscribe(workspace.events(), CourseChanged.class, event -> load.run()));
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
        SphereArt cover = SphereArt.cover(course.coverHue());
        cover.setCornerRadius(5);
        cover.setMinSize(22, 22);
        cover.setPrefSize(22, 22);
        cover.setMaxSize(22, 22);
        ToggleButton button = new ToggleButton(course.title(), cover);
        button.getStyleClass().setAll("nav-item", "sidebar-course");
        button.setToggleGroup(group);
        button.setMaxWidth(Double.MAX_VALUE);
        button.setMnemonicParsing(false);
        Destination destination = new Destination.Course(course.id());
        button.setOnAction(event -> open(button, destination));
        courseItems.put(course.id(), button);
        return button;
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
