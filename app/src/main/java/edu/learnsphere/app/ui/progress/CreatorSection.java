package edu.learnsphere.app.ui.progress;

import edu.learnsphere.app.ui.components.Card;
import edu.learnsphere.app.ui.components.Chip;
import edu.learnsphere.app.ui.components.EmptyIllustration;
import edu.learnsphere.app.ui.components.EmptyState;
import edu.learnsphere.app.ui.components.Formats;
import edu.learnsphere.app.ui.components.Icons;
import edu.learnsphere.app.ui.components.SectionHeader;
import edu.learnsphere.app.ui.components.Tone;
import edu.learnsphere.app.ui.components.charts.BarChart;
import edu.learnsphere.app.ui.components.charts.CanvasChart.Tip;
import edu.learnsphere.app.ui.progress.ProgressData.CreatorStats;
import edu.learnsphere.core.service.ClassInsightSummary;
import edu.learnsphere.core.service.ConceptInsight;
import edu.learnsphere.core.service.ConceptMastery;
import edu.learnsphere.core.service.ItemStats;
import edu.learnsphere.core.service.LearnerInsight;
import edu.learnsphere.core.model.ItemType;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.AccessibleRole;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ContentDisplay;
import javafx.scene.control.Label;
import javafx.scene.input.KeyCode;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import org.kordamp.ikonli.feather.Feather;

/**
 * For a course made in this profile: how each question is answered. The questions missed most come first as
 * rows, then accuracy per question as bars (hardest first), then, when the creator imported their class's
 * progress, the concepts the class finds hard and each learner's results. A row or a bar opens the course in
 * the Studio on that question ({@link edu.learnsphere.app.ui.Destination.Studio#question}). Accuracy comes from
 * the class when it has answered a question, otherwise from the creator's own answers.
 */
final class CreatorSection {

    /** Questions listed under "Most missed". */
    static final int MOST_MISSED = 5;
    /** Questions shown in the accuracy chart before "Show all". */
    static final int CHART_LIMIT = 12;

    /** What the section can do. */
    interface Actions {
        /** Opens the course in the Studio on one of its questions. */
        void openQuestion(String itemId);

        /** Opens the course in the Studio. */
        void openStudio();

        /** Opens the Studio's review queue, where drafts are approved. */
        void openReviewQueue();

        /** Opens the Studio's Publish and share tab, where class progress is imported. */
        void publishAndShare();

        /** Starts studying the course. */
        void studyCourse();
    }

    /**
     * A question with the accuracy shown for it.
     *
     * @param fromClass true when the accuracy is the class's, false when it is the creator's own
     */
    record Answered(ItemStats stats, double accuracy, boolean fromClass) {
    }

    private CreatorSection() {
    }

    /** The questions someone has answered, hardest first. */
    static List<Answered> answered(List<ItemStats> items) {
        List<Answered> list = new ArrayList<>();
        for (ItemStats item : items) {
            if (item.classLearners() > 0 && item.classAccuracy() != null) {
                list.add(new Answered(item, item.classAccuracy(), true));
            } else if (item.attempts() > 0 && item.accuracy() != null) {
                list.add(new Answered(item, item.accuracy(), false));
            }
        }
        list.sort(Comparator.comparingDouble(Answered::accuracy)
                .thenComparing(Comparator.comparingInt((Answered a) -> a.stats().attempts() + a.stats().classLearners()).reversed())
                .thenComparing(a -> a.stats().prompt()));
        return list;
    }

    /**
     * @param primaryFree true when nothing above already holds the screen's primary action
     */
    static VBox of(CreatorStats stats, Actions actions, boolean primaryFree) {
        Objects.requireNonNull(actions, "actions");
        List<Answered> answered = answered(stats.items());
        SectionHeader header = SectionHeader.large("Your questions");
        VBox section = new VBox(16, header);
        section.getStyleClass().add("creator-section");

        if (stats.items().isEmpty() || answered.isEmpty()) {
            // The card below says what will appear and how to get there; a subtitle would only repeat it.
            EmptyState empty = stats.items().isEmpty()
                    ? new EmptyState(EmptyIllustration.Kind.QUESTIONS, "No questions to study yet",
                            "Approve questions in the review queue, then see here how they're answered.")
                    : new EmptyState(EmptyIllustration.Kind.QUESTIONS, "No answers yet",
                            "Try your questions yourself, or import your class's progress, to see which ones are missed most.");
            if (stats.items().isEmpty()) {
                button(empty, primaryFree, "Open the review queue", actions::openReviewQueue);
            } else {
                button(empty, primaryFree, "Study it yourself", actions::studyCourse);
                empty.secondary("Publish and share", actions::publishAndShare);
            }
            Card card = new Card(empty);
            card.setInteractive(false);
            card.getStyleClass().addAll("panel", "creator-empty");
            section.getChildren().add(card);
            return section;
        }

        header.subtitle("How each question is answered, hardest first.").action("Open in the Studio", actions::openStudio);
        section.getChildren().add(mostMissed(answered, stats, actions));
        section.getChildren().add(accuracyChart(answered, stats.items().size(), actions));
        section.getChildren().add(stats.hasClass() ? classCard(stats.classInsights()) : classHint(actions));
        return section;
    }

    private static void button(EmptyState empty, boolean primary, String label, Runnable action) {
        if (primary) {
            empty.primary(label, action);
        } else {
            empty.secondary(label, action);
        }
    }

    // ---- most missed --------------------------------------------------------------------------

    private static Card mostMissed(List<Answered> answered, CreatorStats stats, Actions actions) {
        List<Answered> missed = answered.stream().filter(a -> a.accuracy() < 1).limit(MOST_MISSED).toList();
        SectionHeader header = new SectionHeader("Most missed").subtitle(stats.hasClass()
                ? "From your class's answers, and your own where the class hasn't answered."
                : "From your own answers.");
        Card card = new Card(header);
        card.setInteractive(false);
        card.getStyleClass().addAll("panel", "most-missed");
        card.setSpacing(12);
        if (missed.isEmpty()) {
            Label all = new Label("Every question answered so far was answered correctly.");
            all.getStyleClass().add("text-muted");
            card.getChildren().add(all);
            return card;
        }
        VBox rows = new VBox(4);
        rows.getStyleClass().add("question-rows");
        for (Answered a : missed) {
            rows.getChildren().add(questionRow(a, actions));
        }
        // Rows reach the card's padding, so their text lines up with the heading and the hover fills the width.
        VBox.setMargin(rows, new Insets(0, -16, 0, -16));
        card.getChildren().add(rows);
        return card;
    }

    private static Node questionRow(Answered a, Actions actions) {
        ItemStats s = a.stats();
        Chip type = new Chip(typeName(s.type()));
        Label prompt = new Label(s.prompt());
        prompt.getStyleClass().add("question-prompt");
        prompt.setWrapText(true);
        prompt.setMaxHeight(44);
        Label meta = new Label(answeredLine(s));
        meta.getStyleClass().add("text-caption");
        HBox chips = new HBox(8, type);
        chips.setAlignment(Pos.CENTER_LEFT);
        if (s.openFlags() > 0) {
            chips.getChildren().add(new Chip(Formats.count(s.openFlags(), "report", "reports"), Tone.WARNING, Feather.FLAG));
        }
        VBox text = new VBox(6, chips, prompt, meta);
        HBox.setHgrow(text, Priority.ALWAYS);
        text.setMinWidth(0);

        Label figure = new Label(Formats.percent(a.accuracy()));
        figure.getStyleClass().addAll("serif-medium", "question-accuracy");
        if (a.accuracy() < ConceptMastery.WEAK_THRESHOLD) {
            figure.getStyleClass().add("weak");
        }
        Label figureCaption = new Label(a.fromClass() ? "class right" : "right");
        figureCaption.getStyleClass().add("text-caption");
        VBox numbers = new VBox(0, figure, figureCaption);
        numbers.setAlignment(Pos.CENTER_RIGHT);
        numbers.setMinWidth(Region.USE_PREF_SIZE);

        Node arrow = Icons.of(Feather.CHEVRON_RIGHT, 16);
        arrow.getStyleClass().add("question-arrow");
        HBox row = new HBox(20, text, numbers, arrow);
        row.setAlignment(Pos.CENTER_LEFT);
        row.getStyleClass().add("question-row");
        row.setFocusTraversable(true);
        row.setAccessibleRole(AccessibleRole.BUTTON);
        row.setAccessibleText(s.prompt() + ". " + Formats.percent(a.accuracy()) + " right. Opens the course in the Studio.");
        row.setOnMouseClicked(event -> actions.openQuestion(s.itemId()));
        row.setOnKeyPressed(event -> {
            if (event.getCode() == KeyCode.ENTER || event.getCode() == KeyCode.SPACE) {
                actions.openQuestion(s.itemId());
                event.consume();
            }
        });
        return row;
    }

    /** "3 of 8 right · Class 52% of 6 learners". */
    static String answeredLine(ItemStats s) {
        List<String> parts = new ArrayList<>();
        if (s.attempts() > 0) {
            parts.add("You: " + s.correct() + " of " + s.attempts() + " right");
        }
        if (s.classLearners() > 0 && s.classAccuracy() != null) {
            parts.add("Class: " + Formats.percent(s.classAccuracy()) + " right, "
                    + Formats.count(s.classLearners(), "learner", "learners"));
        }
        return parts.isEmpty() ? "Not answered yet" : String.join(" · ", parts);
    }

    static String typeName(ItemType type) {
        return switch (type) {
            case MCQ -> "Multiple choice";
            case TRUE_FALSE -> "True or false";
            case SHORT_ANSWER -> "Short answer";
            case FLASHCARD -> "Flashcard";
            case CLOZE -> "Fill in the blank";
        };
    }

    // ---- accuracy per question ----------------------------------------------------------------

    private static Card accuracyChart(List<Answered> answered, int total, Actions actions) {
        SectionHeader header = new SectionHeader("Accuracy by question")
                .subtitle("Hardest first. Questions under 60% are worth a second look.");
        BarChart chart = new BarChart();
        chart.setTitle("Accuracy by question, hardest first");
        chart.getStyleClass().add("question-chart");
        chart.setLabelWidth(320);
        chart.setMarker(ConceptMastery.WEAK_THRESHOLD, null);
        Card card = new Card(header, chart);
        card.setInteractive(false);
        card.getStyleClass().addAll("panel", "accuracy-card");
        card.setSpacing(20);

        boolean[] expanded = {false};
        Consumer<Boolean> fill = all -> {
            List<Answered> shown = all ? answered : answered.stream().limit(CHART_LIMIT).toList();
            chart.setBars(shown.stream().map(a -> new BarChart.Bar(Formats.shorten(a.stats().prompt(), 80), a.accuracy(),
                    Formats.percent(a.accuracy()), a.accuracy() < ConceptMastery.WEAK_THRESHOLD,
                    new Tip(Formats.shorten(a.stats().prompt(), 160), answeredLine(a.stats())))).toList());
            chart.setOnItemActivated(index -> actions.openQuestion(shown.get(index).stats().itemId()));
        };
        fill.accept(false);

        int unanswered = total - answered.size();
        boolean more = answered.size() > CHART_LIMIT;
        if (unanswered == 0 && !more) {
            return card;
        }
        Label note = new Label(unanswered > 0 ? Formats.count(unanswered, "question", "questions") + " not answered yet" : "");
        note.getStyleClass().add("text-caption");
        Region gap = new Region();
        HBox.setHgrow(gap, Priority.ALWAYS);
        HBox footer = new HBox(12, note, gap);
        footer.setAlignment(Pos.CENTER_LEFT);
        if (more) {
            Button toggle = new Button("Show all " + answered.size() + " questions", Icons.of(Feather.CHEVRON_DOWN, 14));
            toggle.getStyleClass().addAll("quiet", "small", "show-all");
            toggle.setContentDisplay(ContentDisplay.RIGHT);
            toggle.setOnAction(event -> {
                expanded[0] = !expanded[0];
                fill.accept(expanded[0]);
                toggle.setText(expanded[0] ? "Show the hardest " + CHART_LIMIT : "Show all " + answered.size() + " questions");
                toggle.setGraphic(Icons.of(expanded[0] ? Feather.CHEVRON_UP : Feather.CHEVRON_DOWN, 14));
            });
            footer.getChildren().add(toggle);
        }
        card.getChildren().add(footer);
        return card;
    }

    // ---- the class ----------------------------------------------------------------------------

    private static Card classCard(ClassInsightSummary insights) {
        SectionHeader header = new SectionHeader("Your class").subtitle("From the progress "
                + Formats.count(insights.learnerCount(), "learner", "learners") + " sent you. Concepts weakest first.");
        List<ConceptInsight> concepts = insights.concepts().stream().filter(c -> c.accuracy() != null).toList();
        Node left;
        if (concepts.isEmpty()) {
            Label none = new Label("Your learners haven't answered questions on any concept yet.");
            none.getStyleClass().add("text-muted");
            none.setWrapText(true);
            left = none;
        } else {
            BarChart chart = new BarChart();
            chart.setTitle("Class accuracy by concept, weakest first");
            chart.getStyleClass().add("class-chart");
            chart.setMarker(ConceptMastery.WEAK_THRESHOLD, null);
            chart.setBars(concepts.stream().map(c -> new BarChart.Bar(c.name(), c.accuracy(), Formats.percent(c.accuracy()),
                    c.accuracy() < ConceptMastery.WEAK_THRESHOLD || c.learnersStruggling() > 0,
                    new Tip(c.name(), Formats.percent(c.accuracy()) + " right · " + struggling(c)))).toList());
            left = chart;
        }
        HBox.setHgrow(left, Priority.ALWAYS);

        VBox learners = new VBox(2);
        learners.getStyleClass().add("learner-list");
        Label learnersTitle = new Label("Learners");
        learnersTitle.getStyleClass().addAll("text-overline", "learner-list-title");
        learners.getChildren().add(learnersTitle);
        for (LearnerInsight learner : insights.learners()) {
            Label name = new Label(learner.label());
            name.getStyleClass().addAll("medium", "learner-name");
            Label answers = new Label(Formats.count(learner.attempts(), "answer", "answers"));
            answers.getStyleClass().add("text-caption");
            Label accuracy = new Label(learner.accuracy() == null ? "–" : Formats.percent(learner.accuracy()));
            accuracy.getStyleClass().addAll("medium", "learner-accuracy");
            if (learner.accuracy() != null && learner.accuracy() < ConceptMastery.WEAK_THRESHOLD) {
                accuracy.getStyleClass().add("text-warning");
            }
            Region gap = new Region();
            HBox.setHgrow(gap, Priority.ALWAYS);
            HBox row = new HBox(10, name, gap, answers, accuracy);
            row.setAlignment(Pos.CENTER_LEFT);
            row.getStyleClass().add("learner-row");
            learners.getChildren().add(row);
        }
        learners.setMinWidth(260);
        learners.setPrefWidth(300);
        learners.setMaxWidth(300);

        HBox body = new HBox(32, left, learners);
        body.setAlignment(Pos.TOP_LEFT);
        Card card = new Card(header, body);
        card.setInteractive(false);
        card.getStyleClass().addAll("panel", "class-card");
        card.setSpacing(20);
        return card;
    }

    private static String struggling(ConceptInsight c) {
        if (c.learnersStruggling() == 0) {
            return "no one under 60%";
        }
        return c.learnersStruggling() + " of " + Formats.count(c.learnersAttempted(), "learner", "learners") + " under 60%";
    }

    /** A line under the class's results offering to study the course, for a creator who hasn't yet. */
    static Node selfHint(Actions actions) {
        Label text = new Label("Study the course yourself to see your own progress here: your streak, accuracy, "
                + "each concept's mastery and the reviews coming up.");
        text.getStyleClass().add("class-hint-text");
        text.setWrapText(true);
        HBox.setHgrow(text, Priority.ALWAYS);
        text.setMaxWidth(Double.MAX_VALUE);
        Button study = new Button("Study it yourself", Icons.of(Feather.ARROW_RIGHT, 14));
        study.setContentDisplay(ContentDisplay.RIGHT);
        study.getStyleClass().add("small");
        study.setMinWidth(Region.USE_PREF_SIZE);
        study.setOnAction(event -> actions.studyCourse());
        Node icon = Icons.of(Feather.BAR_CHART_2, 18);
        icon.getStyleClass().add("class-hint-icon");
        HBox hint = new HBox(14, icon, text, study);
        hint.setAlignment(Pos.CENTER_LEFT);
        hint.getStyleClass().addAll("class-hint", "self-hint");
        return hint;
    }

    private static Node classHint(Actions actions) {
        Label text = new Label("See how your class does: ask your learners to send you their progress, "
                + "then import it in Publish and share.");
        text.getStyleClass().add("class-hint-text");
        text.setWrapText(true);
        HBox.setHgrow(text, Priority.ALWAYS);
        text.setMaxWidth(Double.MAX_VALUE);
        Button open = new Button("Publish and share", Icons.of(Feather.ARROW_RIGHT, 14));
        open.setContentDisplay(ContentDisplay.RIGHT);
        open.getStyleClass().add("small");
        open.setMinWidth(Region.USE_PREF_SIZE);
        open.setOnAction(event -> actions.publishAndShare());
        Node icon = Icons.of(Feather.USERS, 18);
        icon.getStyleClass().add("class-hint-icon");
        HBox hint = new HBox(14, icon, text, open);
        hint.setAlignment(Pos.CENTER_LEFT);
        hint.getStyleClass().add("class-hint");
        return hint;
    }
}
