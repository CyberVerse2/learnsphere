package edu.learnsphere.app.ui.studio.review;

import edu.learnsphere.app.ui.components.ActivityIndicator;
import edu.learnsphere.app.ui.components.Chip;
import edu.learnsphere.app.ui.components.Icons;
import edu.learnsphere.app.ui.components.Tone;
import edu.learnsphere.core.model.Concept;
import edu.learnsphere.core.model.Flag;
import edu.learnsphere.core.model.ItemOrigin;
import edu.learnsphere.core.model.ItemStatus;
import edu.learnsphere.core.model.ItemType;
import edu.learnsphere.core.model.LearningItem;
import edu.learnsphere.core.service.ItemDetail;
import edu.learnsphere.core.service.SourcePassage;
import java.util.ArrayList;
import java.util.List;
import javafx.css.PseudoClass;
import javafx.geometry.Pos;
import javafx.scene.AccessibleRole;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.scene.text.Text;
import javafx.scene.text.TextFlow;
import javafx.util.Duration;
import org.kordamp.ikonli.feather.Feather;

/**
 * One question in the review queue: the draft on the left (question, answer and options, explanation,
 * concepts), its source passage on the right with the support verdict, open flags with their reasons, and
 * the actions its status allows. The card the keyboard acts on shows an accent outline.
 */
final class ReviewCard extends VBox {

    /** What the cards ask the review queue to do. */
    interface Host {
        void approve(ReviewCard card);

        void edit(ReviewCard card);

        void regenerate(ReviewCard card);

        void stopRegenerating(ReviewCard card);

        void reject(ReviewCard card);

        void retire(ReviewCard card);

        void backToReview(ReviewCard card);

        void resolveFlag(ReviewCard card, Flag flag);

        void openPage(SourcePassage source, String textToFind);

        void selectionChanged(ReviewCard card, boolean selected);

        void makeCurrent(ReviewCard card);
    }

    private static final PseudoClass CURRENT = PseudoClass.getPseudoClass("current");
    private static final PseudoClass SELECTED = PseudoClass.getPseudoClass("selected");
    private static final PseudoClass BUSY = PseudoClass.getPseudoClass("busy");

    private final ItemDetail detail;
    private final Host host;
    private final CheckBox select = new CheckBox();
    private final HBox columns;
    private final HBox busyLine = new HBox(10);
    private final Label busyText = new Label();
    private final Label errorLine = new Label();
    private final HBox actions = new HBox(8);
    private final List<Button> actionButtons = new ArrayList<>();
    private final SourcePassageView sourceView;
    private final boolean fresh;
    private boolean current;

    /**
     * @param moduleTitle the item's module, or null
     * @param fresh       true right after the study assistant wrote a new version of it
     */
    ReviewCard(ItemDetail detail, String moduleTitle, boolean fresh, Host host) {
        this.detail = detail;
        this.host = host;
        this.fresh = fresh;
        LearningItem item = detail.item();
        getStyleClass().addAll("card", "review-card", "review-item");
        setSpacing(18);
        setAccessibleRole(AccessibleRole.LIST_ITEM);
        setAccessibleText(ReviewLabels.type(item.type()) + ": " + item.prompt());

        select.getStyleClass().add("review-select");
        select.setAccessibleText("Select this question");
        select.selectedProperty().addListener((obs, old, on) -> {
            pseudoClassStateChanged(SELECTED, on);
            host.selectionChanged(this, on);
        });
        List<String> meta = new ArrayList<>();
        if (moduleTitle != null) {
            meta.add(moduleTitle);
        }
        meta.add(ReviewLabels.type(item.type()));
        meta.add(ReviewLabels.difficulty(item.difficulty()));
        Label overline = new Label(String.join("  ·  ", meta));
        overline.getStyleClass().addAll("text-overline", "review-meta");
        overline.setMinWidth(0);
        HBox.setHgrow(overline, Priority.ALWAYS);
        overline.setMaxWidth(Double.MAX_VALUE);
        HBox top = new HBox(12, select, overline);
        top.setAlignment(Pos.CENTER_LEFT);
        if (fresh) {
            top.getChildren().add(new Chip("New version", Tone.SUCCESS, Feather.REFRESH_CW));
        }
        top.getChildren().add(item.origin() == ItemOrigin.AI
                ? new Chip(ReviewLabels.origin(item.origin()), Tone.ACCENT, Feather.FEATHER)
                : new Chip(ReviewLabels.origin(item.origin()), Tone.NEUTRAL, Feather.EDIT_3));

        VBox draft = draftColumn(item);
        draft.setPrefWidth(440);
        draft.setMinWidth(0);
        HBox.setHgrow(draft, Priority.ALWAYS);
        sourceView = new SourcePassageView(item, detail.source(), host::openPage);
        sourceView.setPrefWidth(420);
        sourceView.setMinWidth(0);
        HBox.setHgrow(sourceView, Priority.ALWAYS);
        columns = new HBox(28, draft, sourceView);
        columns.getStyleClass().add("review-columns");
        getChildren().add(top);

        // Reports come first: they are the reason to read this question again.
        List<Flag> open = detail.flags().stream().filter(Flag::isOpen).toList();
        if (!open.isEmpty()) {
            VBox flags = new VBox(8);
            flags.getStyleClass().add("review-flags");
            for (Flag flag : open) {
                flags.getChildren().add(flagLine(flag));
            }
            getChildren().add(flags);
        }
        getChildren().add(columns);

        ActivityIndicator spinner = new ActivityIndicator(16);
        busyText.getStyleClass().add("text-muted");
        busyText.setWrapText(true);
        Region busyGap = new Region();
        HBox.setHgrow(busyGap, Priority.ALWAYS);
        Button stop = new Button("Stop");
        stop.getStyleClass().addAll("quiet", "small", "neutral");
        stop.setOnAction(event -> host.stopRegenerating(this));
        busyLine.getChildren().addAll(spinner, busyText, busyGap, stop);
        busyLine.setAlignment(Pos.CENTER_LEFT);
        busyLine.getStyleClass().add("review-busy");
        show(busyLine, false);

        errorLine.getStyleClass().addAll("text-danger", "review-error");
        errorLine.setGraphic(Icons.of(Feather.ALERT_CIRCLE, 14));
        errorLine.setWrapText(true);
        show(errorLine, false);

        actions.setAlignment(Pos.CENTER_LEFT);
        actions.getStyleClass().add("review-actions");
        buildActions(item);
        getChildren().addAll(busyLine, errorLine, actions);

        setOnMouseClicked(event -> host.makeCurrent(this));
        focusWithinProperty().addListener((obs, old, within) -> {
            if (within) {
                host.makeCurrent(this);
            }
        });
    }

    ItemDetail detail() {
        return detail;
    }

    String itemId() {
        return detail.item().id();
    }

    LearningItem item() {
        return detail.item();
    }

    /** True when it shows a version the study assistant has just written. */
    boolean isFresh() {
        return fresh;
    }

    /** True for the card the keyboard shortcuts act on. */
    boolean isCurrent() {
        return current;
    }

    void setCurrent(boolean value) {
        current = value;
        pseudoClassStateChanged(CURRENT, value);
    }

    boolean isSelected() {
        return select.isSelected();
    }

    void setSelected(boolean value) {
        select.setSelected(value);
    }

    /** The selection check box (selection is offered for drafts). */
    CheckBox selectBox() {
        return select;
    }

    /** Shows that the study assistant is writing a new version, with its latest progress line. */
    void setBusy(String message) {
        boolean busy = message != null;
        busyText.setText(busy ? message : "");
        show(busyLine, busy);
        pseudoClassStateChanged(BUSY, busy);
        columns.setOpacity(busy ? 0.55 : 1);
        for (Button button : actionButtons) {
            button.setDisable(busy);
        }
        if (busy) {
            setError(null);
        }
    }

    boolean isBusy() {
        return busyLine.isVisible();
    }

    /** Shows what went wrong with the last action on this card, or nothing for null. */
    void setError(String message) {
        errorLine.setText(message == null ? "" : message);
        show(errorLine, message != null);
    }

    String errorText() {
        return errorLine.isVisible() ? errorLine.getText() : null;
    }

    SourcePassageView sourceView() {
        return sourceView;
    }

    /** The action button with this text, or null (tests and screenshots). */
    Button action(String text) {
        for (Button button : actionButtons) {
            if (text.equals(button.getText())) {
                return button;
            }
        }
        return null;
    }

    // ---- building ------------------------------------------------------------------------------------

    private VBox draftColumn(LearningItem item) {
        VBox column = new VBox(14);
        column.getStyleClass().add("review-draft");
        column.getChildren().add(prompt(item));
        Node body = switch (item.type()) {
            case MCQ -> options(item);
            case TRUE_FALSE -> trueFalse(item);
            case CLOZE -> clozeAnswer(item);
            case FLASHCARD -> part("Back", text(item.answer(), "serif", "review-back"));
            case SHORT_ANSWER -> shortAnswer(item);
        };
        if (body != null) {
            column.getChildren().add(body);
        }
        if (!item.explanation().isBlank()) {
            column.getChildren().add(part("Explanation", text(item.explanation(), "text-body", "text-muted")));
        }
        List<Concept> concepts = detail.concepts();
        if (!concepts.isEmpty()) {
            FlowPane chips = new FlowPane(6, 6);
            chips.getStyleClass().add("review-concepts");
            for (Concept concept : concepts) {
                chips.getChildren().add(new Chip(concept.name()));
            }
            column.getChildren().add(chips);
        }
        return column;
    }

    private Node prompt(LearningItem item) {
        if (item.type() == ItemType.CLOZE && ItemRules.blanks(item.prompt()) == 1) {
            int at = item.prompt().indexOf(ItemType.CLOZE_BLANK);
            Text before = promptRun(item.prompt().substring(0, at));
            Text fill = promptRun(item.answer().isBlank() ? ItemType.CLOZE_BLANK : item.answer());
            fill.getStyleClass().add("cloze-fill");
            Text after = promptRun(item.prompt().substring(at + ItemType.CLOZE_BLANK.length()));
            TextFlow flow = new TextFlow(before, fill, after);
            flow.getStyleClass().add("review-prompt-flow");
            flow.setAccessibleText(item.prompt().replace(ItemType.CLOZE_BLANK, "blank") + " Missing words: " + item.answer());
            return flow;
        }
        Label prompt = text(item.prompt(), "review-prompt");
        return prompt;
    }

    private static Text promptRun(String value) {
        Text run = new Text(value);
        run.getStyleClass().addAll("review-prompt", "review-prompt-run");
        return run;
    }

    private Node options(LearningItem item) {
        VBox options = new VBox(8);
        options.getChildren().add(option(item.answer(), true));
        for (String distractor : item.distractors()) {
            options.getChildren().add(option(distractor, false));
        }
        return options;
    }

    private static Label option(String value, boolean correct) {
        Label label = new Label(value, Icons.of(correct ? Feather.CHECK_CIRCLE : Feather.CIRCLE, 16));
        label.getStyleClass().addAll("review-option", correct ? "correct" : "distractor");
        label.setWrapText(true);
        label.setAccessibleText((correct ? "Correct answer: " : "Wrong option: ") + value);
        return label;
    }

    private Node trueFalse(LearningItem item) {
        boolean truth = item.answer().strip().equalsIgnoreCase("true");
        boolean known = truth || item.answer().strip().equalsIgnoreCase("false");
        Label answer = new Label(known ? (truth ? "The statement is true." : "The statement is false.") : item.answer(),
                Icons.of(truth ? Feather.CHECK : Feather.X, 14));
        answer.getStyleClass().addAll("review-answer", "medium");
        answer.setWrapText(true);
        return answer;
    }

    private Node clozeAnswer(LearningItem item) {
        if (ItemRules.blanks(item.prompt()) == 1) {
            return null;
        }
        return part("Missing words", text(item.answer(), "review-answer"));
    }

    private Node shortAnswer(LearningItem item) {
        ShortAnswerText parsed = ShortAnswerText.parse(item.answer());
        VBox box = new VBox(12, part("Model answer", text(parsed.modelAnswer(), "serif", "review-back")));
        if (!parsed.keyPoints().isEmpty()) {
            VBox points = new VBox(4);
            for (String point : parsed.keyPoints()) {
                Label bullet = text("•  " + point, "text-body");
                points.getChildren().add(bullet);
            }
            box.getChildren().add(part(ShortAnswerText.HEADING.replace(":", ""), points));
        }
        return box;
    }

    private static VBox part(String heading, Node content) {
        Label label = new Label(heading);
        label.getStyleClass().addAll("text-caption", "review-part-label");
        VBox part = new VBox(4, label, content);
        part.getStyleClass().add("review-part");
        return part;
    }

    private static Label text(String value, String... classes) {
        Label label = new Label(value);
        label.getStyleClass().addAll(classes);
        label.setWrapText(true);
        label.setMinHeight(Region.USE_PREF_SIZE);
        return label;
    }

    private Node flagLine(Flag flag) {
        Label who = new Label(ReviewLabels.flagSource(flag.source()) + " · " + ReviewLabels.flagReason(flag.reason()),
                Icons.of(Feather.FLAG, 14));
        who.getStyleClass().addAll("medium", "flag-reason");
        who.setWrapText(true);
        VBox text = new VBox(2, who);
        if (!flag.note().isBlank()) {
            Label note = new Label("“" + flag.note() + "”");
            note.getStyleClass().addAll("text-caption", "flag-note");
            note.setWrapText(true);
            text.getChildren().add(note);
        }
        HBox.setHgrow(text, Priority.ALWAYS);
        Button resolve = new Button("Resolve");
        resolve.getStyleClass().addAll("quiet", "small", "neutral");
        resolve.setMinWidth(Region.USE_PREF_SIZE);
        resolve.setAccessibleText("Resolve this flag");
        resolve.setOnAction(event -> host.resolveFlag(this, flag));
        HBox line = new HBox(12, text, resolve);
        line.setAlignment(Pos.CENTER_LEFT);
        line.getStyleClass().add("flag-line");
        return line;
    }

    private void buildActions(LearningItem item) {
        Region gap = new Region();
        HBox.setHgrow(gap, Priority.ALWAYS);
        switch (item.status()) {
            case DRAFT -> {
                Button approve = button("Approve", Feather.CHECK, "A", () -> host.approve(this), "positive");
                Button edit = button("Edit", Feather.EDIT_2, "E", () -> host.edit(this));
                actions.getChildren().addAll(approve, edit);
                if (canRegenerate()) {
                    actions.getChildren().add(button("Regenerate", Feather.REFRESH_CW, null, () -> host.regenerate(this)));
                }
                actions.getChildren().addAll(gap, button("Reject", Feather.X, "R", () -> host.reject(this), "quiet", "danger-text"));
            }
            case APPROVED -> actions.getChildren().addAll(
                    button("Edit", Feather.EDIT_2, "E", () -> host.edit(this)), gap,
                    button("Retire", Feather.ARCHIVE, null, () -> host.retire(this), "quiet", "neutral"));
            case REJECTED, RETIRED -> actions.getChildren().addAll(
                    button("Edit", Feather.EDIT_2, "E", () -> host.edit(this)),
                    button("Back to review", Feather.CORNER_UP_LEFT, null, () -> host.backToReview(this)), gap);
        }
        select.setVisible(item.status() == ItemStatus.DRAFT);
        select.setManaged(item.status() == ItemStatus.DRAFT);
    }

    /** True when the study assistant can write a new version: an AI draft whose passage is still there. */
    boolean canRegenerate() {
        LearningItem item = detail.item();
        return item.status() == ItemStatus.DRAFT && item.origin() == ItemOrigin.AI && detail.source() != null;
    }

    private Button button(String label, Feather icon, String key, Runnable action, String... classes) {
        Button button = new Button(label, Icons.of(icon, 14));
        button.getStyleClass().add("small");
        button.getStyleClass().addAll(classes);
        button.setMinWidth(Region.USE_PREF_SIZE);
        button.setOnAction(event -> action.run());
        if (key != null) {
            Tooltip tip = new Tooltip(label + " (" + key + ")");
            tip.setShowDelay(Duration.millis(500));
            button.setTooltip(tip);
        }
        actionButtons.add(button);
        return button;
    }

    private static void show(Node node, boolean visible) {
        node.setVisible(visible);
        node.setManaged(visible);
    }
}
