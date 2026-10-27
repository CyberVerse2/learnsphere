package edu.learnsphere.app.ui.studio.review;

import edu.learnsphere.app.theme.Motion;
import edu.learnsphere.app.ui.components.IconButton;
import edu.learnsphere.app.ui.components.Overlays;
import java.util.List;
import java.util.Objects;
import javafx.animation.FadeTransition;
import javafx.animation.ParallelTransition;
import javafx.animation.ScaleTransition;
import javafx.application.Platform;
import javafx.beans.InvalidationListener;
import javafx.beans.value.ChangeListener;
import javafx.css.PseudoClass;
import javafx.geometry.Pos;
import javafx.scene.AccessibleRole;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.ButtonBase;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.util.Duration;
import org.kordamp.ikonli.feather.Feather;

/**
 * The review queue's dialogs: a card over the dimmed window with a title, a scrolling body and a row of
 * buttons. Escape and the close button ask {@link #setOnCloseRequest the owner} whether to close; keyboard
 * focus stays inside while it is the top dialog, and returns to where it was when it closes.
 */
final class ReviewModal extends StackPane {

    /** On the card while its body is taller than the space it has, so the content clears the scroll bar. */
    private static final PseudoClass SCROLLING = PseudoClass.getPseudoClass("scrolling");

    private final InvalidationListener overflowCheck = obs -> checkOverflow();
    private final VBox card = new VBox();
    private final Label title = new Label();
    private final Label subtitle = new Label();
    private final ScrollPane scroller = new ScrollPane();
    private final HBox footer = new HBox(12);
    private Runnable onCloseRequest = this::close;
    private ChangeListener<Node> focusTrap;
    private Node focusBefore;
    private Node initialFocus;
    private Runnable onClosed;
    private boolean closed;
    private Object owner;

    /**
     * @param titleText the dialog's title
     * @param width     the card's width in pixels
     */
    ReviewModal(String titleText, double width) {
        getStyleClass().addAll("modal", "review-modal");
        Region scrim = new Region();
        scrim.getStyleClass().add("modal-scrim");
        scrim.setOnMouseClicked(event -> event.consume());

        title.setText(Objects.requireNonNull(titleText, "titleText"));
        title.getStyleClass().add("modal-title");
        title.setWrapText(true);
        subtitle.getStyleClass().add("text-caption");
        subtitle.setWrapText(true);
        subtitle.setManaged(false);
        subtitle.setVisible(false);
        VBox titles = new VBox(4, title, subtitle);
        HBox.setHgrow(titles, Priority.ALWAYS);
        IconButton close = new IconButton(Feather.X, "Close").small();
        close.setOnAction(event -> onCloseRequest.run());
        HBox head = new HBox(16, titles, close);
        head.setAlignment(Pos.TOP_LEFT);
        head.getStyleClass().add("review-modal-head");

        scroller.getStyleClass().add("review-modal-body");
        scroller.setFitToWidth(true);
        scroller.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        scroller.viewportBoundsProperty().addListener(overflowCheck);
        VBox.setVgrow(scroller, Priority.ALWAYS);

        footer.setAlignment(Pos.CENTER_RIGHT);
        footer.getStyleClass().add("review-modal-footer");

        card.getChildren().addAll(head, scroller, footer);
        card.getStyleClass().add("review-modal-card");
        card.setMinWidth(Region.USE_PREF_SIZE);
        card.setPrefWidth(width);
        card.setMaxWidth(width);
        card.setMaxHeight(Region.USE_PREF_SIZE);
        card.setAccessibleRole(AccessibleRole.DIALOG);
        card.setAccessibleText(titleText);
        // Leave the window's edges visible around a tall dialog.
        heightProperty().addListener((obs, old, height) -> scroller.setMaxHeight(Math.max(160, height.doubleValue() - 220)));
        getChildren().addAll(scrim, card);
        StackPane.setAlignment(card, Pos.CENTER);

        addEventFilter(KeyEvent.KEY_PRESSED, event -> {
            if (event.getCode() == KeyCode.ESCAPE) {
                event.consume();
                onCloseRequest.run();
            }
        });
    }

    /** A line under the title, or null for none. */
    ReviewModal subtitle(String text) {
        boolean show = text != null && !text.isBlank();
        subtitle.setText(show ? text : "");
        subtitle.setManaged(show);
        subtitle.setVisible(show);
        return this;
    }

    /** The scrolling content. */
    ReviewModal body(Node content) {
        if (scroller.getContent() != null) {
            scroller.getContent().layoutBoundsProperty().removeListener(overflowCheck);
        }
        content.getStyleClass().add("review-modal-content");
        content.layoutBoundsProperty().addListener(overflowCheck);
        scroller.setContent(content);
        checkOverflow();
        return this;
    }

    /**
     * The bottom row, right-aligned (put a spacer first to push something left). Buttons keep their whole
     * label; text beside them wraps.
     */
    ReviewModal footer(Node... nodes) {
        for (Node node : nodes) {
            if (node instanceof ButtonBase button) {
                button.setMinWidth(Region.USE_PREF_SIZE);
            } else if (node instanceof Label label) {
                label.setMinWidth(0);
            }
        }
        footer.getChildren().setAll(nodes);
        return this;
    }

    private void checkOverflow() {
        Node content = scroller.getContent();
        boolean overflows = content != null
                && content.getLayoutBounds().getHeight() > scroller.getViewportBounds().getHeight() + 0.5;
        card.pseudoClassStateChanged(SCROLLING, overflows);
    }

    /** What Escape and the close button do (default: close). */
    void setOnCloseRequest(Runnable action) {
        onCloseRequest = Objects.requireNonNull(action, "action");
    }

    /** Runs once after the dialog has closed. */
    void setOnClosed(Runnable action) {
        onClosed = action;
    }

    /** The node that gets keyboard focus when the dialog opens (default: the first focusable one). */
    void setInitialFocus(Node node) {
        initialFocus = node;
    }

    /** Records the dialog this modal belongs to, so {@link #top} can find it. */
    ReviewModal owner(Object dialog) {
        owner = dialog;
        return this;
    }

    /** The dialog this modal belongs to, or null. */
    Object owner() {
        return owner;
    }

    /** The review dialog on top in the window of {@code node}, or null when none is open. */
    static ReviewModal top(Node node) {
        Overlays overlays;
        try {
            overlays = Overlays.find(node);
        } catch (IllegalStateException noOverlays) {
            return null;
        }
        List<Node> open = overlays.modals().getChildren();
        for (int i = open.size() - 1; i >= 0; i--) {
            if (open.get(i) instanceof ReviewModal modal && !modal.isClosed()) {
                return modal;
            }
        }
        return null;
    }

    /** The card, for tests and for lookups. */
    VBox card() {
        return card;
    }

    String titleText() {
        return title.getText();
    }

    void setTitle(String text) {
        title.setText(text);
        card.setAccessibleText(text);
    }

    /** The body's scroll pane. */
    ScrollPane scroller() {
        return scroller;
    }

    boolean isClosed() {
        return closed;
    }

    /** Opens the dialog in the window of {@code anchor}. */
    void open(Node anchor) {
        StackPane layer = Overlays.find(anchor).modals();
        layer.getChildren().add(this);
        Scene scene = getScene();
        if (scene != null) {
            focusBefore = scene.getFocusOwner();
            focusTrap = (obs, old, owner) -> {
                if (owner != null && !isInside(owner) && isTop()) {
                    Platform.runLater(this::focusFirst);
                }
            };
            scene.focusOwnerProperty().addListener(focusTrap);
        }
        Platform.runLater(this::focusFirst);
        FadeTransition fade = new FadeTransition(Duration.millis(160), this);
        fade.setFromValue(0);
        fade.setToValue(1);
        if (Motion.reduced()) {
            fade.play();
        } else {
            ScaleTransition grow = new ScaleTransition(Duration.millis(180), card);
            grow.setFromX(0.98);
            grow.setFromY(0.98);
            grow.setToX(1);
            grow.setToY(1);
            grow.setInterpolator(Motion.EASE_OUT);
            new ParallelTransition(fade, grow).play();
        }
    }

    /** Closes the dialog and gives focus back. */
    void close() {
        if (closed) {
            return;
        }
        closed = true;
        Scene scene = getScene();
        if (scene != null && focusTrap != null) {
            scene.focusOwnerProperty().removeListener(focusTrap);
        }
        Runnable remove = () -> {
            if (getParent() instanceof StackPane layer) {
                layer.getChildren().remove(this);
            }
            if (focusBefore != null && focusBefore.getScene() != null) {
                focusBefore.requestFocus();
            }
            if (onClosed != null) {
                onClosed.run();
            }
        };
        if (Motion.reduced()) {
            remove.run();
        } else {
            Motion.fadeOut(this, Duration.millis(120), remove);
        }
    }

    private void focusFirst() {
        if (closed) {
            return;
        }
        Node target = initialFocus != null && initialFocus.getScene() != null ? initialFocus : firstFocusable(card);
        if (target != null) {
            target.requestFocus();
        }
    }

    private boolean isTop() {
        Parent parent = getParent();
        return parent instanceof StackPane layer && !layer.getChildren().isEmpty()
                && layer.getChildren().getLast() == this;
    }

    private boolean isInside(Node node) {
        for (Node n = node; n != null; n = n.getParent()) {
            if (n == card) {
                return true;
            }
        }
        return false;
    }

    private static Node firstFocusable(Node node) {
        if (node.isFocusTraversable() && !node.isDisabled() && node.isVisible()) {
            return node;
        }
        if (node instanceof Parent parent) {
            for (Node child : parent.getChildrenUnmodifiable()) {
                Node found = firstFocusable(child);
                if (found != null) {
                    return found;
                }
            }
        }
        if (node instanceof ScrollPane pane && pane.getContent() != null) {
            return firstFocusable(pane.getContent());
        }
        return null;
    }
}
