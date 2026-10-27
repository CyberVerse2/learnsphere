package edu.learnsphere.app.ui.studio;

import edu.learnsphere.app.ui.Destination;
import edu.learnsphere.app.ui.StudioTab;
import edu.learnsphere.app.ui.components.Chip;
import edu.learnsphere.app.ui.components.CourseCard;
import edu.learnsphere.app.ui.components.CourseCover;
import edu.learnsphere.app.ui.components.EmptyIllustration;
import edu.learnsphere.app.ui.components.EmptyState;
import edu.learnsphere.app.ui.components.Formats;
import edu.learnsphere.app.ui.components.Icons;
import edu.learnsphere.app.ui.components.PageHeader;
import edu.learnsphere.app.ui.components.SectionHeader;
import edu.learnsphere.app.ui.components.Skeleton;
import edu.learnsphere.app.ui.components.StatusPill;
import edu.learnsphere.app.ui.components.Tone;
import edu.learnsphere.core.error.UserMessage;
import edu.learnsphere.core.events.CourseChanged;
import edu.learnsphere.core.events.DocumentChanged;
import edu.learnsphere.core.events.ItemsChanged;
import edu.learnsphere.core.service.CourseFilter;
import edu.learnsphere.core.service.CourseSummary;
import java.util.ArrayList;
import java.util.List;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.AccessibleRole;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ContentDisplay;
import javafx.scene.control.Label;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyCodeCombination;
import javafx.scene.input.KeyCombination;
import javafx.scene.input.MouseButton;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import org.kordamp.ikonli.feather.Feather;

/**
 * The Studio without a course: every course this profile is writing, one row each with what needs
 * attention, and New course. Archived courses wait in their own list below, folded away. Opening a row goes
 * to the tab its first attention marker points at (PDFs to check, drafts to review, PDFs importing), or to
 * the course's details.
 */
final class CourseListSection extends StudioSection {

    private final VBox root = new VBox(32);
    private final VBox body = new VBox(32);
    private final Refresher<List<CourseSummary>> refresher;
    private boolean showArchived;

    CourseListSection(StudioHost host) {
        super(host);
        root.getStyleClass().add("studio-courses");
        Button create = new Button("New course", Icons.of(Feather.PLUS));
        create.getStyleClass().addAll("primary", "new-course-button");
        create.setOnAction(event -> newCourse());
        PageHeader header = new PageHeader("Studio")
                .subtitle("Turn your PDFs into courses: check what the study assistant drafts, then publish and share them.")
                .actions(create);
        VBox loading = new VBox();
        loading.getStyleClass().addAll("card", "flat", "studio-list");
        for (int i = 0; i < 3; i++) {
            loading.getChildren().add(skeletonRow(i == 0));
        }
        body.getChildren().setAll(loading);
        root.getChildren().setAll(header, body);
        refresher = new Refresher<>(disposer,
                () -> workspace().courses().list(CourseFilter.creating().withArchived(true)),
                this::render, this::showError);
    }

    @Override
    Region root() {
        return root;
    }

    @Override
    void onShown() {
        refresher.request();
        on(CourseChanged.class, event -> refresher.request());
        on(DocumentChanged.class, event -> refresher.request());
        on(ItemsChanged.class, event -> refresher.request());
        Shortcuts.install(root, new KeyCodeCombination(KeyCode.N, KeyCombination.SHORTCUT_DOWN), this::newCourse, disposer);
    }

    private void newCourse() {
        NewCourseDialog.show(root, workspace()).thenAccept(course -> {
            if (course != null) {
                host.navigator().navigate(new Destination.Studio(course.id(), StudioTab.MODULES));
            }
        });
    }

    private void showError(UserMessage message) {
        body.getChildren().setAll(new EmptyState(EmptyIllustration.Kind.SEARCH, "Your courses couldn't load", message.text())
                .primary("Try again", refresher::request));
    }

    private void render(List<CourseSummary> courses) {
        List<CourseSummary> active = courses.stream().filter(c -> !c.isArchived()).toList();
        List<CourseSummary> archived = courses.stream().filter(CourseSummary::isArchived).toList();
        List<Node> sections = new ArrayList<>();
        if (active.isEmpty()) {
            EmptyState empty = new EmptyState(EmptyIllustration.Kind.DOCUMENTS, "Make your first course",
                    "Add the PDFs you teach from. The study assistant drafts questions and flashcards for you to check.")
                    .primary("New course", this::newCourse);
            empty.getStyleClass().add("studio-courses-empty");
            sections.add(empty);
        } else {
            sections.add(list(active, false));
        }
        if (!archived.isEmpty()) {
            Button toggle = new Button(showArchived ? "Hide" : "Show",
                    Icons.of(showArchived ? Feather.CHEVRON_UP : Feather.CHEVRON_DOWN, 14));
            toggle.getStyleClass().addAll("quiet", "small", "neutral");
            toggle.setContentDisplay(ContentDisplay.RIGHT);
            toggle.setAccessibleText(showArchived ? "Hide archived courses" : "Show archived courses");
            toggle.setOnAction(event -> {
                showArchived = !showArchived;
                render(courses);
            });
            SectionHeader header = new SectionHeader("Archived")
                    .subtitle(Formats.count(archived.size(), "course", "courses")
                            + " out of sight. Open one to bring it back.")
                    .trailing(toggle);
            VBox archive = new VBox(12, header);
            if (showArchived) {
                archive.getChildren().add(list(archived, true));
            }
            sections.add(archive);
        }
        body.getChildren().setAll(sections);
    }

    private Node list(List<CourseSummary> courses, boolean archived) {
        VBox list = new VBox();
        list.getStyleClass().addAll("card", "flat", "studio-list");
        if (archived) {
            list.getStyleClass().add("archived");
        }
        for (CourseSummary course : courses) {
            HBox row = row(course);
            if (list.getChildren().isEmpty()) {
                row.getStyleClass().add("first");
            }
            list.getChildren().add(row);
        }
        return list;
    }

    private HBox row(CourseSummary course) {
        CourseCover cover = new CourseCover(course.coverHue(), course.id(), CourseCover.Size.MEDIUM);

        Label overline = new Label(CourseCard.metaLine(course, CourseCard.Mode.CREATING).toUpperCase(java.util.Locale.ROOT));
        overline.getStyleClass().addAll("text-overline", "studio-row-overline");
        Label title = new Label(course.title());
        title.getStyleClass().add("studio-row-title");
        title.setMnemonicParsing(false);
        title.setWrapText(true);
        Label contents = new Label(contentsLine(course));
        contents.getStyleClass().add("text-caption");
        VBox text = new VBox(4, overline, title);
        if (!course.subtitle().isBlank()) {
            Label subtitle = new Label(course.subtitle());
            subtitle.getStyleClass().addAll("text-muted", "studio-row-subtitle");
            subtitle.setMnemonicParsing(false);
            text.getChildren().add(subtitle);
        }
        text.getChildren().add(contents);
        List<Node> markers = markers(course);
        if (!markers.isEmpty()) {
            FlowPane attention = new FlowPane(6, 6);
            attention.getStyleClass().add("studio-row-markers");
            attention.getChildren().addAll(markers);
            VBox.setMargin(attention, new Insets(6, 0, 0, 0));
            text.getChildren().add(attention);
        }
        text.setMinWidth(0);
        HBox.setHgrow(text, Priority.ALWAYS);

        Label edited = new Label("Edited " + Formats.ago(course.updatedAt(), host.context().clock()));
        edited.getStyleClass().add("text-caption");
        edited.setMinWidth(Region.USE_PREF_SIZE);

        var chevron = Icons.of(Feather.CHEVRON_RIGHT, 18);
        chevron.getStyleClass().add("studio-row-chevron");
        HBox row = new HBox(20, cover, text, edited, chevron);
        row.setAlignment(Pos.CENTER_LEFT);
        row.getStyleClass().addAll("studio-course-row");
        row.setFocusTraversable(true);
        row.setAccessibleRole(AccessibleRole.BUTTON);
        row.setAccessibleText(course.title());
        StudioTab landing = landingTab(course);
        row.setOnMouseClicked(event -> {
            if (event.getButton() == MouseButton.PRIMARY) {
                open(course, landing);
            }
        });
        row.setOnKeyPressed(event -> {
            switch (event.getCode()) {
                case ENTER, SPACE -> {
                    event.consume();
                    open(course, landing);
                }
                case DOWN, UP -> {
                    event.consume();
                    moveFocus(row, event.getCode() == KeyCode.DOWN ? 1 : -1);
                }
                default -> {
                }
            }
        });
        return row;
    }

    private void moveFocus(HBox row, int step) {
        List<Node> rows = root.lookupAll(".studio-course-row").stream().toList();
        int index = rows.indexOf(row);
        int next = index + step;
        if (index >= 0 && next >= 0 && next < rows.size()) {
            rows.get(next).requestFocus();
        }
    }

    private void open(CourseSummary course, StudioTab tab) {
        host.navigator().navigate(new Destination.Studio(course.id(), tab));
    }

    /** What the course holds, e.g. "4 modules · 8 PDFs · 6 approved questions". */
    static String contentsLine(CourseSummary course) {
        if (course.moduleCount() == 0 && course.documentCount() == 0 && course.itemCount() == 0) {
            return "No modules or PDFs yet";
        }
        return String.join(" · ", Formats.count(course.moduleCount(), "module", "modules"),
                Formats.count(course.documentCount(), "PDF", "PDFs"),
                Formats.count(course.itemCount(), "approved question", "approved questions"));
    }

    /** What needs the creator's attention, most urgent first. */
    private static List<Node> markers(CourseSummary course) {
        List<Node> markers = new ArrayList<>();
        if (course.documentsNeedingAttention() > 0) {
            markers.add(new StatusPill(StatusPill.Status.NEEDS_REVIEW,
                    Formats.count(course.documentsNeedingAttention(), "PDF to check", "PDFs to check")));
        }
        if (course.draftItemCount() > 0) {
            markers.add(new Chip(Formats.count(course.draftItemCount(), "draft to review", "drafts to review"), Tone.WARNING));
        }
        if (course.documentsProcessing() > 0) {
            markers.add(new StatusPill(StatusPill.Status.WORKING,
                    Formats.count(course.documentsProcessing(), "PDF importing", "PDFs importing")));
        }
        if (course.openFlagCount() > 0) {
            markers.add(new Chip(Formats.count(course.openFlagCount(), "flag", "flags"), Tone.DANGER, Feather.FLAG));
        }
        return markers;
    }

    /** The tab the first attention marker points at, or the details. */
    static StudioTab landingTab(CourseSummary course) {
        if (course.documentsNeedingAttention() > 0) {
            return StudioTab.DOCUMENTS;
        }
        if (course.draftItemCount() > 0) {
            return StudioTab.REVIEW;
        }
        if (course.documentsProcessing() > 0) {
            return StudioTab.DOCUMENTS;
        }
        if (course.openFlagCount() > 0) {
            return StudioTab.PUBLISH;
        }
        return course.moduleCount() == 0 ? StudioTab.MODULES : StudioTab.DETAILS;
    }

    private static HBox skeletonRow(boolean first) {
        Skeleton cover = Skeleton.block(64, 64);
        VBox text = new VBox(10, Skeleton.line(120), Skeleton.block(260, 18), Skeleton.line(200));
        HBox.setHgrow(text, Priority.ALWAYS);
        HBox row = new HBox(20, cover, text, Skeleton.line(90));
        row.setAlignment(Pos.CENTER_LEFT);
        row.getStyleClass().addAll("studio-course-row", "skeleton-row");
        if (first) {
            row.getStyleClass().add("first");
        }
        return row;
    }
}
