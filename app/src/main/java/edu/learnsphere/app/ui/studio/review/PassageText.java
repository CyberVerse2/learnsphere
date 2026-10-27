package edu.learnsphere.app.ui.studio.review;

import java.util.List;
import java.util.Objects;
import javafx.geometry.Pos;
import javafx.scene.layout.Pane;
import javafx.scene.layout.StackPane;
import javafx.scene.shape.Path;
import javafx.scene.text.Text;
import javafx.scene.text.TextFlow;

/**
 * Passage text with part of it marked like a highlighter pen: a soft band drawn behind the lines of the
 * marked range, following the text as it wraps.
 */
final class PassageText extends StackPane {

    private final Text run = new Text();
    private final Path mark = new Path();
    private final TextFlow flow;
    private SupportingText.Range range;
    /** What the mark was last drawn for: the range and the width the text wrapped to. */
    private SupportingText.Range drawnRange;
    private double drawnWidth = -1;

    PassageText(String... styleClasses) {
        getStyleClass().add("passage-text");
        run.getStyleClass().addAll(styleClasses);
        run.getStyleClass().add("passage-run");
        flow = new TextFlow(run) {
            @Override
            protected void layoutChildren() {
                super.layoutChildren();
                if (!Objects.equals(range, drawnRange) || getWidth() != drawnWidth) {
                    drawMark();
                }
            }
        };
        flow.getStyleClass().add("passage-flow");
        mark.getStyleClass().add("passage-mark");
        mark.setManaged(false);
        Pane layer = new Pane(mark);
        layer.setMouseTransparent(true);
        layer.setMinSize(0, 0);
        setAlignment(Pos.TOP_LEFT);
        getChildren().addAll(layer, flow);
    }

    /** Shows {@code text} with {@code marked} (offsets into {@code text}) highlighted, or nothing when null. */
    void set(String text, SupportingText.Range marked) {
        run.setText(text);
        range = marked != null && marked.end() <= text.length() ? marked : null;
        setAccessibleText(text);
        drawMark();
        // The text wraps afresh on the next layout: draw the mark again then.
        drawnWidth = -1;
        flow.requestLayout();
    }

    String text() {
        return run.getText();
    }

    /** The marked range, or null. */
    SupportingText.Range marked() {
        return range;
    }

    /** The marked text, or null. */
    String markedText() {
        return range == null ? null : run.getText().substring(range.start(), range.end());
    }

    private void drawMark() {
        drawnRange = range;
        drawnWidth = flow.getWidth();
        if (range == null || range.length() == 0) {
            mark.getElements().clear();
            return;
        }
        mark.getElements().setAll(List.of(flow.getRangeShape(range.start(), range.end(), false)));
    }
}
