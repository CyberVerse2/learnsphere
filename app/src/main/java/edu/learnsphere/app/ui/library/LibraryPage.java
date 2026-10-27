package edu.learnsphere.app.ui.library;

import edu.learnsphere.app.AppContext;
import edu.learnsphere.app.ui.Destination;
import edu.learnsphere.app.ui.Navigator;
import edu.learnsphere.app.ui.PageView;
import edu.learnsphere.app.ui.StudioTab;
import edu.learnsphere.app.ui.components.CourseCard;
import edu.learnsphere.app.ui.components.EmptyIllustration;
import edu.learnsphere.app.ui.components.EmptyState;
import edu.learnsphere.app.ui.components.Icons;
import edu.learnsphere.app.ui.components.PageHeader;
import edu.learnsphere.app.ui.components.ResponsiveGrid;
import edu.learnsphere.app.ui.components.SegmentedControl;
import edu.learnsphere.app.ui.components.Skeleton;
import edu.learnsphere.app.ui.components.Toasts;
import edu.learnsphere.core.events.CourseChanged;
import edu.learnsphere.core.service.CourseFilter;
import edu.learnsphere.core.service.CourseSummary;
import java.util.List;
import javafx.scene.control.Button;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import org.kordamp.ikonli.feather.Feather;

/**
 * The library: every course in the profile as cards in a responsive grid, split into the courses being
 * studied and the ones being written here. Import course and New course start the two ways in.
 */
public final class LibraryPage extends PageView {

    /** The two halves of the library. */
    public enum Shelf {
        STUDYING("Studying"),
        CREATING("Creating");

        private final String label;

        Shelf(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    private final SegmentedControl<Shelf> shelf = new SegmentedControl<>(List.of(Shelf.values()), Shelf::label);
    private final VBox body = new VBox();
    private List<CourseSummary> studying = List.of();
    private List<CourseSummary> creating = List.of();

    public LibraryPage(AppContext context, Navigator navigator) {
        super(context, navigator);
        Button importCourse = new Button("Import course", Icons.of(Feather.DOWNLOAD));
        importCourse.setOnAction(event -> Toasts.info(page(), "Choose a course file to import it."));
        Button newCourse = new Button("New course", Icons.of(Feather.PLUS));
        newCourse.getStyleClass().add("primary");
        newCourse.setOnAction(event -> createCourse());
        PageHeader header = new PageHeader("Library")
                .subtitle("Courses you study and courses you're writing, all on this computer.")
                .actions(importCourse, newCourse);
        HBox controls = new HBox(shelf);
        controls.getStyleClass().add("library-controls");
        ResponsiveGrid loading = ResponsiveGrid.forCards();
        for (int i = 0; i < 3; i++) {
            loading.getChildren().add(Skeleton.card());
        }
        body.getChildren().setAll(loading);
        show(header, controls, body);
        shelf.valueProperty().addListener((obs, old, value) -> render());
    }

    /** The segmented control (tests). */
    public SegmentedControl<Shelf> shelf() {
        return shelf;
    }

    @Override
    public void onShown() {
        refresh();
        on(CourseChanged.class, event -> refresh());
    }

    private void refresh() {
        load(() -> List.of(workspace().courses().list(CourseFilter.studying()),
                workspace().courses().list(CourseFilter.creating())), lists -> {
                    List<CourseSummary> first = lists.get(0);
                    List<CourseSummary> second = lists.get(1);
                    studying = first;
                    creating = second;
                    render();
                });
    }

    private void render() {
        boolean studyingShelf = shelf.getValue() == Shelf.STUDYING;
        List<CourseSummary> courses = studyingShelf ? studying : creating;
        if (courses.isEmpty()) {
            body.getChildren().setAll(studyingShelf
                    ? new EmptyState(EmptyIllustration.Kind.LIBRARY, "No courses to study yet",
                            "Import a course someone shared with you, or start one from your own PDFs.")
                            .primary("Import a course", () -> Toasts.info(page(), "Choose a course file to import it."))
                            .secondary("New course", this::createCourse)
                    : new EmptyState(EmptyIllustration.Kind.DOCUMENTS, "Turn your PDFs into a course",
                            "Add lecture notes or a textbook chapter and LearnSphere drafts questions for you to check.")
                            .primary("New course", this::createCourse));
            return;
        }
        ResponsiveGrid grid = ResponsiveGrid.forCards();
        for (CourseSummary course : courses) {
            CourseCard card = new CourseCard(course, studyingShelf ? CourseCard.Mode.STUDYING : CourseCard.Mode.CREATING);
            card.setOnOpen(() -> navigator.navigate(studyingShelf ? new Destination.Course(course.id())
                    : new Destination.Studio(course.id(), StudioTab.DETAILS)));
            grid.getChildren().add(card);
            card.animateIn();
        }
        body.getChildren().setAll(grid);
    }

    private void createCourse() {
        run(() -> workspace().courses().create(new edu.learnsphere.core.service.CourseDraft("Untitled course", null,
                        null, null, null)),
                course -> navigator.navigate(new Destination.Studio(course.id(), StudioTab.DETAILS)),
                message -> Toasts.error(page(), message));
    }
}
