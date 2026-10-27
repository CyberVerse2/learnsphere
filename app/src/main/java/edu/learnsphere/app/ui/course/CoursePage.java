package edu.learnsphere.app.ui.course;

import edu.learnsphere.app.AppContext;
import edu.learnsphere.app.ui.Destination;
import edu.learnsphere.app.ui.Navigator;
import edu.learnsphere.app.ui.PageView;
import edu.learnsphere.app.ui.components.Card;
import edu.learnsphere.app.ui.components.Chip;
import edu.learnsphere.app.ui.components.CourseCard;
import edu.learnsphere.app.ui.components.CourseCover;
import edu.learnsphere.app.ui.components.Formats;
import edu.learnsphere.app.ui.components.Icons;
import edu.learnsphere.app.ui.components.ProgressBarSlim;
import edu.learnsphere.app.ui.components.ProgressRing;
import edu.learnsphere.app.ui.components.SectionHeader;
import edu.learnsphere.app.ui.components.Skeleton;
import edu.learnsphere.app.ui.components.StatTile;
import edu.learnsphere.app.ui.components.Tone;
import edu.learnsphere.core.events.ProgressChanged;
import edu.learnsphere.core.service.ConceptMastery;
import edu.learnsphere.core.service.CourseDetail;
import edu.learnsphere.core.service.CourseSummary;
import edu.learnsphere.core.service.ModuleSummary;
import edu.learnsphere.core.service.SessionRequest;
import java.util.Comparator;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import org.kordamp.ikonli.feather.Feather;

/**
 * One course: its cover, title, author and version; Study and Ask as the two main actions; the modules as
 * a vertical path with completion; and concept mastery as chips.
 */
public final class CoursePage extends PageView {

    private final Destination.Course destination;

    public CoursePage(AppContext context, Navigator navigator, Destination.Course destination) {
        super(context, navigator);
        this.destination = destination;
        show(Skeleton.block(-1, 200), Skeleton.block(420, 34), Skeleton.text(3));
    }

    @Override
    public void onShown() {
        String id = destination.courseId();
        load(() -> {
            workspace().courses().markOpened(id);
            return workspace().courses().get(id);
        }, this::render);
        on(ProgressChanged.class, event -> {
            if (id.equals(event.courseId())) {
                load(() -> workspace().courses().get(id), this::render);
            }
        });
    }

    private void render(CourseDetail detail) {
        CourseSummary course = detail.summary();
        CourseCover cover = new CourseCover(course.coverHue(), course.id(), CourseCover.Size.BANNER);

        Label meta = new Label(CourseCard.metaLine(course, CourseCard.Mode.STUDYING));
        meta.getStyleClass().addAll("text-caption", "course-meta");
        Label title = new Label(course.title());
        title.getStyleClass().addAll("text-display", "course-title");
        title.setWrapText(true);
        VBox heading = new VBox(6, meta, title);
        if (!detail.course().description().isBlank()) {
            Label description = new Label(detail.course().description());
            description.getStyleClass().add("course-description");
            description.setWrapText(true);
            description.setMaxWidth(640);
            heading.getChildren().add(description);
        }
        HBox.setHgrow(heading, Priority.ALWAYS);

        Button study = new Button(course.dueCount() > 0 ? "Study " + course.dueCount() + " due" : "Study",
                Icons.of(Feather.PLAY));
        study.getStyleClass().addAll("primary", "large");
        study.setOnAction(event -> navigator.navigate(new Destination.Study(SessionRequest.studyNext(course.id()))));
        Button ask = new Button("Ask", Icons.of(Feather.MESSAGE_CIRCLE));
        ask.getStyleClass().add("large");
        ask.setOnAction(event -> navigator.navigate(Destination.Ask.newThread(course.id())));
        HBox actions = new HBox(12, study, ask);
        actions.setAlignment(Pos.BOTTOM_RIGHT);
        actions.setMinWidth(Region.USE_PREF_SIZE);
        HBox top = new HBox(32, heading, actions);
        top.setAlignment(Pos.BOTTOM_LEFT);
        top.getStyleClass().add("course-heading");

        VBox modules = new VBox(0);
        modules.getStyleClass().add("module-path");
        for (int i = 0; i < detail.modules().size(); i++) {
            modules.getChildren().add(moduleStep(detail.modules().get(i), i == detail.modules().size() - 1));
        }
        Card modulesCard = new Card(new SectionHeader("Modules")
                .subtitle(Formats.count(detail.modules().size(), "module", "modules")), modules);
        modulesCard.setInteractive(false);
        modulesCard.setSpacing(12);
        HBox.setHgrow(modulesCard, Priority.ALWAYS);

        double mastery = detail.concepts().stream().filter(c -> c.mastery() != null)
                .mapToDouble(ConceptMastery::mastery).average().orElse(0);
        StatTile due = new StatTile("Due today", course.dueCount())
                .detail(course.newItemCount() > 0 ? Formats.count(course.newItemCount(), "new question", "new questions")
                        + " waiting" : "No new questions", Tone.NEUTRAL);
        StatTile progress = new StatTile("Studied", Formats.percent(course.progress()))
                .detail(Formats.count(course.itemCount(), "question", "questions") + " in the course", Tone.NEUTRAL);
        ProgressRing ring = ProgressRing.small();
        ring.setProgress(course.progress());
        progress.graphic(ring);
        HBox stats = new HBox(16, due, progress);
        stats.getChildren().forEach(n -> {
            ((Region) n).setPrefWidth(160);
            ((Region) n).setMaxWidth(Double.MAX_VALUE);
            HBox.setHgrow(n, Priority.ALWAYS);
        });

        FlowPane chips = new FlowPane(8, 8);
        detail.concepts().stream().sorted(Comparator.comparing(ConceptMastery::name)).forEach(c ->
                chips.getChildren().add(conceptChip(c)));
        Card concepts = new Card(new SectionHeader("Concepts").action("Progress",
                () -> navigator.navigate(new Destination.Progress(course.id()))), chips);
        concepts.setInteractive(false);
        concepts.setSpacing(14);

        VBox side = new VBox(16, stats, concepts);
        side.setPrefWidth(400);
        side.setMinWidth(340);
        HBox columns = new HBox(24, modulesCard, side);
        columns.setAlignment(Pos.TOP_LEFT);
        show(cover, top, columns);
    }

    private Node moduleStep(ModuleSummary module, boolean last) {
        ProgressRing ring = new ProgressRing(30, 3);
        ring.setShowPercent(false);
        ring.setProgress(module.completion());
        Region line = new Region();
        line.getStyleClass().add("module-line");
        line.setVisible(!last);
        VBox.setVgrow(line, Priority.ALWAYS);
        VBox rail = new VBox(6, ring, line);
        rail.setAlignment(Pos.TOP_CENTER);
        rail.setMinWidth(30);

        Label title = new Label(module.module().title());
        title.getStyleClass().add("module-title");
        String counts = Formats.count(module.itemCount(), "question", "questions")
                + (module.dueCount() > 0 ? " · " + module.dueCount() + " due" : "");
        Label detail = new Label(counts);
        detail.getStyleClass().add("text-caption");
        ProgressBarSlim bar = new ProgressBarSlim();
        bar.setProgress(module.completion());
        bar.setMaxWidth(260);
        VBox text = new VBox(4, title, detail, bar);
        if (!module.module().summary().isBlank()) {
            Label summary = new Label(module.module().summary());
            summary.getStyleClass().add("module-summary");
            summary.setWrapText(true);
            text.getChildren().add(2, summary);
        }
        text.getStyleClass().add("module-text");
        HBox.setHgrow(text, Priority.ALWAYS);
        Button quiz = new Button("Quiz");
        quiz.getStyleClass().addAll("quiet", "small");
        quiz.setMinWidth(Region.USE_PREF_SIZE);
        quiz.setOnAction(event -> navigator.navigate(new Destination.Study(
                SessionRequest.moduleQuiz(module.module().courseId(), module.module().id()))));
        HBox row = new HBox(16, rail, text, quiz);
        row.getStyleClass().add("module-step");
        return row;
    }

    private static Node conceptChip(ConceptMastery concept) {
        if (concept.mastery() == null) {
            return new Chip(concept.name() + " · not started");
        }
        double m = concept.mastery();
        Tone tone = m < ConceptMastery.WEAK_THRESHOLD ? Tone.WARNING : m >= 0.85 ? Tone.SUCCESS : Tone.ACCENT;
        return new Chip(concept.name() + " · " + Formats.percent(m), tone);
    }
}
