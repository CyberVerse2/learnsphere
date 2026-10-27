package edu.learnsphere.app.ui.components;

import edu.learnsphere.core.error.UserMessage;
import edu.learnsphere.core.events.DocumentChanged;
import edu.learnsphere.core.model.DocumentStatus;
import edu.learnsphere.core.service.DocumentSummary;
import java.util.Objects;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Region;
import javafx.scene.shape.Circle;

/**
 * The state of something that is processed in the background, as a small tinted pill: ready (success),
 * working (accent, with a turning arc and a progress line such as "Reading your PDF… page 14 of 120"),
 * needs review (warning) or failed (danger, with the reason in a tooltip).
 *
 * <p>{@link #show(DocumentSummary)} and {@link #show(DocumentChanged)} turn a PDF's import state into the
 * pill's words, so every screen describes imports the same way.
 */
public final class StatusPill extends HBox {

    /** The four states a pill can show. */
    public enum Status {
        READY("Ready", Tone.SUCCESS),
        WORKING("Working…", Tone.ACCENT),
        NEEDS_REVIEW("Needs review", Tone.WARNING),
        FAILED("Couldn't finish", Tone.DANGER);

        private final String defaultText;
        private final Tone tone;

        Status(String defaultText, Tone tone) {
            this.defaultText = defaultText;
            this.tone = tone;
        }

        public String defaultText() {
            return defaultText;
        }

        public Tone tone() {
            return tone;
        }
    }

    private final Label label = new Label();
    private Status status;
    private Node indicator;
    private Tooltip tooltip;

    public StatusPill(Status status) {
        this(status, status.defaultText());
    }

    public StatusPill(Status status, String text) {
        super(6);
        getStyleClass().add("status-pill");
        setAlignment(Pos.CENTER_LEFT);
        setMinWidth(Region.USE_PREF_SIZE);
        setMaxWidth(Region.USE_PREF_SIZE);
        label.getStyleClass().add("status-pill-label");
        label.setMnemonicParsing(false);
        getChildren().add(label);
        set(status, text);
    }

    /** A pill showing a PDF's import state. */
    public static StatusPill forDocument(DocumentSummary document) {
        StatusPill pill = new StatusPill(Status.WORKING);
        pill.show(document);
        return pill;
    }

    public Status status() {
        return status;
    }

    public String text() {
        return label.getText();
    }

    /** Shows {@code status} with its default words. */
    public void set(Status status) {
        set(status, status.defaultText());
    }

    /** Shows {@code status} with {@code text}. */
    public void set(Status status, String text) {
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(text, "text");
        if (status != this.status) {
            this.status = status;
            Tone.apply(this, status.tone());
            if (indicator != null) {
                getChildren().remove(indicator);
            }
            indicator = status == Status.WORKING ? new ActivityIndicator(10) : dot();
            getChildren().addFirst(indicator);
        }
        label.setText(text);
        setAccessibleText(text);
        if (status != Status.FAILED) {
            setTooltipText(null);
        }
    }

    /** Shows a PDF's import state from its summary. */
    public void show(DocumentSummary document) {
        show(document.status(), document.stepDone(), document.stepTotal(), document.error());
    }

    /** Shows a PDF's import state from a change event. */
    public void show(DocumentChanged change) {
        show(change.status(), change.stepDone(), change.stepTotal(), change.error());
    }

    /** Shows a PDF's import state. */
    public void show(DocumentStatus documentStatus, int stepDone, int stepTotal, UserMessage error) {
        set(statusOf(documentStatus), describe(documentStatus, stepDone, stepTotal));
        if (documentStatus == DocumentStatus.FAILED && error != null) {
            setTooltipText(error.text());
        }
    }

    /** The pill state for a PDF's import status. */
    public static Status statusOf(DocumentStatus status) {
        return switch (status) {
            case READY -> Status.READY;
            case NEEDS_REVIEW -> Status.NEEDS_REVIEW;
            case FAILED -> Status.FAILED;
            case QUEUED, CHECKING, EXTRACTING, READING, INDEXING -> Status.WORKING;
        };
    }

    /** The words for a PDF's import status, e.g. "Reading your PDF… page 14 of 120". */
    public static String describe(DocumentStatus status, int stepDone, int stepTotal) {
        boolean steps = stepTotal > 0;
        return switch (status) {
            case QUEUED -> "Waiting to start";
            case CHECKING -> "Checking your PDF…";
            case EXTRACTING -> steps ? "Reading your PDF… page " + stepDone + " of " + stepTotal : "Reading your PDF…";
            case READING -> steps ? "Reading scanned pages… " + stepDone + " of " + stepTotal : "Reading scanned pages…";
            case INDEXING -> "Preparing for questions…";
            case NEEDS_REVIEW -> "Needs review";
            case READY -> "Ready";
            case FAILED -> "Couldn't import";
        };
    }

    private void setTooltipText(String text) {
        if (tooltip != null) {
            Tooltip.uninstall(this, tooltip);
            tooltip = null;
        }
        if (text != null) {
            tooltip = new Tooltip(text);
            tooltip.setWrapText(true);
            tooltip.setMaxWidth(300);
            Tooltip.install(this, tooltip);
        }
    }

    /** The reason a failed pill shows on hover, or null. */
    public String tooltipText() {
        return tooltip == null ? null : tooltip.getText();
    }

    private static Node dot() {
        Circle dot = new Circle(3);
        dot.getStyleClass().add("status-dot");
        return dot;
    }
}
