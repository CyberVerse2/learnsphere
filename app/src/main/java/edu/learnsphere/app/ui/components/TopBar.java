package edu.learnsphere.app.ui.components;

import java.util.List;
import java.util.Objects;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import org.kordamp.ikonli.feather.Feather;

/**
 * The quiet bar above the content: a back button, breadcrumbs that say where the learner is ("Library ›
 * Operating Systems Principles › Ask"), and a slot on the right for status and window-wide controls.
 * Earlier crumbs are links; the last one is the current place.
 */
public final class TopBar extends HBox {

    /**
     * One step of the breadcrumbs.
     *
     * @param label  what the place is called
     * @param action what clicking it does, or null for the current place
     */
    public record Crumb(String label, Runnable action) {
        public Crumb {
            Objects.requireNonNull(label, "label");
        }

        /** The current place (not a link). */
        public static Crumb current(String label) {
            return new Crumb(label, null);
        }
    }

    /** Longest a crumb gets before its text is shortened with an ellipsis. */
    public static final double CRUMB_MAX_WIDTH = 300;
    private static final double GAP = 8;
    /** How far a crumb's text sits inside its own edge. */
    private static final double CRUMB_INSET = 6;
    /** How far the back button's arrow sits inside its own edge. */
    private static final double BACK_ICON_INSET = 10;

    private final IconButton back = new IconButton(Feather.CHEVRON_LEFT, "Back");
    private final HBox crumbs = new HBox(4);
    private final HBox trailing = new HBox(12);
    private final BooleanProperty canGoBack = new SimpleBooleanProperty(this, "canGoBack", false);
    private double columnMaxWidth = Page.MAX_WIDTH;
    private Node aligned;
    private double alignedSpace = -1;

    public TopBar() {
        getStyleClass().add("top-bar");
        setAlignment(Pos.CENTER_LEFT);
        setSpacing(GAP);
        back.getStyleClass().add("top-bar-back");
        back.disableProperty().bind(canGoBack.not());
        back.visibleProperty().bind(canGoBack);
        back.managedProperty().bind(canGoBack);
        crumbs.getStyleClass().add("breadcrumbs");
        crumbs.setAlignment(Pos.CENTER_LEFT);
        crumbs.setMinWidth(0);
        HBox.setHgrow(crumbs, Priority.ALWAYS);
        trailing.getStyleClass().add("top-bar-trailing");
        trailing.setAlignment(Pos.CENTER_RIGHT);
        trailing.setMinWidth(Region.USE_PREF_SIZE);
        getChildren().addAll(back, crumbs, trailing);
    }

    /**
     * The widest the content column gets: {@link Page#MAX_WIDTH} for pages (the default), or
     * {@link Double#POSITIVE_INFINITY} for views that fill the content area with {@link Page#SIDE_PADDING} margins.
     */
    public void setColumnMaxWidth(double maxWidth) {
        if (maxWidth != columnMaxWidth) {
            columnMaxWidth = maxWidth;
            requestLayout();
        }
    }

    /** The back arrow, or the first crumb's text when there is no way back, starts on the content column's edge. */
    @Override
    protected void layoutChildren() {
        double column = Math.max(0, (getWidth() - columnMaxWidth) / 2) + Page.SIDE_PADDING;
        Node first = back.isManaged() ? back : crumbs;
        double space = Math.max(0, column - snappedLeftInset() - (first == back ? BACK_ICON_INSET : CRUMB_INSET));
        if (first != aligned || space != alignedSpace) {
            if (aligned != null && aligned != first) {
                HBox.setMargin(aligned, null);
            }
            HBox.setMargin(first, new Insets(0, 0, 0, space));
            aligned = first;
            alignedSpace = space;
        }
        super.layoutChildren();
    }

    /** Whether the back button is enabled. Bind it to the navigator's history. */
    public BooleanProperty canGoBackProperty() {
        return canGoBack;
    }

    /** What the back button does. */
    public void setOnBack(Runnable action) {
        Objects.requireNonNull(action, "action");
        back.setOnAction(event -> action.run());
    }

    public Button backButton() {
        return back;
    }

    /** Replaces the breadcrumbs. */
    public void setCrumbs(List<Crumb> list) {
        crumbs.getChildren().clear();
        for (int i = 0; i < list.size(); i++) {
            Crumb crumb = list.get(i);
            if (i > 0) {
                var separator = Icons.of(Feather.CHEVRON_RIGHT, 14);
                separator.getStyleClass().add("crumb-separator");
                crumbs.getChildren().add(separator);
            }
            boolean last = i == list.size() - 1;
            if (last || crumb.action() == null) {
                Label label = new Label(crumb.label());
                label.getStyleClass().addAll("crumb", last ? "current" : "inactive");
                label.setMnemonicParsing(false);
                label.setMaxWidth(CRUMB_MAX_WIDTH);
                label.setMinWidth(0);
                crumbs.getChildren().add(label);
            } else {
                Button link = new Button(crumb.label());
                link.getStyleClass().setAll("crumb", "crumb-link");
                link.setMnemonicParsing(false);
                link.setMaxWidth(CRUMB_MAX_WIDTH);
                link.setMinWidth(0);
                Runnable action = crumb.action();
                link.setOnAction(event -> action.run());
                crumbs.getChildren().add(link);
            }
        }
    }

    /** The crumb texts in order (tests and accessibility). */
    public List<String> crumbTexts() {
        return crumbs.getChildren().stream()
                .filter(node -> node.getStyleClass().contains("crumb"))
                .map(node -> node instanceof Label l ? l.getText() : ((Button) node).getText())
                .toList();
    }

    /** Puts {@code nodes} in the right-hand slot. */
    public void setTrailing(Node... nodes) {
        trailing.getChildren().setAll(nodes);
    }
}
