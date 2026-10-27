package edu.learnsphere.app.ui.home;

import edu.learnsphere.app.ui.components.Card;
import edu.learnsphere.app.ui.components.CitationChip;
import edu.learnsphere.app.ui.components.CourseCover;
import edu.learnsphere.app.ui.components.Formats;
import edu.learnsphere.core.model.Citation;
import edu.learnsphere.core.service.RecentQuestion;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import javafx.geometry.Pos;
import javafx.scene.AccessibleRole;
import javafx.scene.Node;
import javafx.scene.control.ButtonBase;
import javafx.scene.control.Label;
import javafx.scene.input.KeyCode;
import javafx.scene.input.MouseButton;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

/**
 * The questions asked most recently, as one card of rows: the question in reading type, the start of its
 * answer, the course and when, and a chip for each source. A row opens its conversation in Ask; a chip opens
 * the conversation at that page.
 */
public final class RecentQuestions extends Card {

    /** Characters of an answer shown under its question. */
    static final int PREVIEW_LENGTH = 180;
    /** Widest an answer preview runs, for a comfortable line length. */
    static final double ANSWER_WIDTH = 760;

    private final Consumer<RecentQuestion> onOpen;
    private final BiConsumer<RecentQuestion, Citation> onCitation;
    private final List<Row> rows = new ArrayList<>();

    /**
     * @param onOpen     opens a question's conversation
     * @param onCitation opens a question's conversation at one of its sources
     */
    public RecentQuestions(Consumer<RecentQuestion> onOpen, BiConsumer<RecentQuestion, Citation> onCitation) {
        this.onOpen = Objects.requireNonNull(onOpen, "onOpen");
        this.onCitation = Objects.requireNonNull(onCitation, "onCitation");
        setInteractive(false);
        getStyleClass().add("home-questions");
        setSpacing(0);
    }

    public void show(List<RecentQuestion> questions, Clock clock) {
        rows.clear();
        List<Node> nodes = new ArrayList<>();
        for (RecentQuestion question : questions) {
            if (!nodes.isEmpty()) {
                Region rule = new Region();
                rule.getStyleClass().add("home-question-rule");
                nodes.add(rule);
            }
            Row row = new Row(question, clock);
            rows.add(row);
            nodes.add(row);
        }
        getChildren().setAll(nodes);
    }

    /** The rows on screen (tests). */
    public List<Row> rows() {
        return List.copyOf(rows);
    }

    /** One question: click, Enter or Space opens its conversation; Enter on a source chip opens that source. */
    public final class Row extends HBox {

        private final RecentQuestion question;
        private final List<CitationChip> chips = new ArrayList<>();

        Row(RecentQuestion question, Clock clock) {
            this.question = question;
            getStyleClass().add("home-question");
            setFocusTraversable(true);
            setAccessibleRole(AccessibleRole.BUTTON);
            setAccessibleText(question.question() + ", " + question.courseTitle());

            CourseCover cover = new CourseCover(question.coverHue(), question.courseId(), CourseCover.Size.SMALL);
            Label text = new Label(question.question());
            text.getStyleClass().addAll("serif-medium", "home-question-text");
            text.setWrapText(true);
            String preview = Formats.shorten(question.answerPreview().replaceAll("\\s*\\[\\d+]", ""), PREVIEW_LENGTH);
            Label answer = new Label(preview);
            answer.getStyleClass().add("home-question-answer");
            answer.setWrapText(true);
            answer.setMaxWidth(ANSWER_WIDTH);
            answer.setVisible(!preview.isEmpty());
            answer.setManaged(!preview.isEmpty());

            Label meta = new Label(question.courseTitle() + " · " + Formats.ago(question.askedAt(), clock));
            meta.getStyleClass().addAll("text-caption", "home-question-meta");
            FlowPane footer = new FlowPane(6, 6, meta);
            footer.setAlignment(Pos.CENTER_LEFT);
            footer.getStyleClass().add("home-question-footer");
            meta.setPadding(new javafx.geometry.Insets(0, 6, 0, 0));
            for (Citation citation : question.citations()) {
                CitationChip chip = new CitationChip(citation);
                chip.setOnOpen(c -> onCitation.accept(question, c));
                chips.add(chip);
                footer.getChildren().add(chip);
            }
            VBox body = new VBox(6, text, answer, footer);
            HBox.setHgrow(body, Priority.ALWAYS);
            getChildren().setAll(cover, body);
            setSpacing(16);
            setAlignment(Pos.TOP_LEFT);

            setOnMouseClicked(event -> {
                if (event.getButton() == MouseButton.PRIMARY && !fromButton(event.getPickResult().getIntersectedNode())) {
                    onOpen.accept(question);
                }
            });
            setOnKeyPressed(event -> {
                if (event.getCode() != KeyCode.ENTER && event.getCode() != KeyCode.SPACE) {
                    return;
                }
                event.consume();
                // A focused source chip answers Enter itself (it opens its page); Space it already handles.
                if (event.getTarget() instanceof Node target && target != this && fromButton(target)) {
                    if (event.getCode() == KeyCode.ENTER && buttonOf(target) instanceof ButtonBase chip
                            && !chip.isDisabled()) {
                        chip.fire();
                    }
                    return;
                }
                onOpen.accept(question);
            });
        }

        public RecentQuestion question() {
            return question;
        }

        public List<CitationChip> chips() {
            return List.copyOf(chips);
        }

        private boolean fromButton(Node node) {
            return buttonOf(node) != null;
        }

        /** The button inside this row that holds {@code node}, or null. */
        private ButtonBase buttonOf(Node node) {
            for (Node n = node; n != null && n != this; n = n.getParent()) {
                if (n instanceof ButtonBase button) {
                    return button;
                }
            }
            return null;
        }
    }
}
