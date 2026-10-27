package edu.learnsphere.app.ui.library;

import edu.learnsphere.app.theme.Motion;
import edu.learnsphere.app.ui.components.Overlays;
import java.util.Objects;
import javafx.animation.FadeTransition;
import javafx.animation.ParallelTransition;
import javafx.animation.ScaleTransition;
import javafx.application.Platform;
import javafx.beans.value.ChangeListener;
import javafx.geometry.Pos;
import javafx.scene.AccessibleRole;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBase;
import javafx.scene.control.Label;
import javafx.scene.control.TextInputControl;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.util.Duration;

/**
 * A dialog over a dimmed window for work that takes more than a yes or no: a form, a preview, a running
 * import. It has a title, an optional line under it, a body the caller fills, and a footer of buttons on the
 * right. Escape closes it (unless {@link #setDismissible(boolean)} turned that off), Enter presses the
 * default button from a text field, and focus stays inside while it is open. It opens in the window's modal
 * layer ({@link Overlays}), like {@code ConfirmDialog}.
 *
 * <pre>{@code
 * ModalSheet sheet = new ModalSheet("New course").subtitle("Give it a name and a cover.");
 * sheet.body().getChildren().add(form);
 * sheet.setButtons(cancel, create);
 * sheet.setDefaultButton(create);
 * sheet.show(page);
 * }</pre>
 */
public final class ModalSheet extends StackPane {

    /** Width of a sheet unless {@link #setCardWidth(double)} changes it. */
    public static final double DEFAULT_WIDTH = 520;

    private final VBox card = new VBox();
    private final Label title;
    private final VBox heading;
    private final VBox body = new VBox(16);
    private final HBox footer = new HBox(12);
    private Label subtitle;
    private Button defaultButton;
    private Node initialFocus;
    private boolean dismissible = true;
    private Runnable onClose = () -> { };
    private ChangeListener<Node> focusTrap;
    private Node focusBefore;
    private boolean closing;

    public ModalSheet(String title) {
        getStyleClass().add("modal");
        getStylesheets().add(LibraryStyles.URL);
        Region scrim = new Region();
        scrim.getStyleClass().add("modal-scrim");
        scrim.setOnMouseClicked(event -> event.consume());

        this.title = new Label(Objects.requireNonNull(title, "title"));
        this.title.getStyleClass().addAll("modal-title", "sheet-title");
        this.title.setWrapText(true);
        heading = new VBox(6, this.title);
        heading.getStyleClass().add("sheet-heading");
        body.getStyleClass().add("sheet-body");
        footer.getStyleClass().add("sheet-footer");
        footer.setAlignment(Pos.CENTER_RIGHT);

        card.getChildren().addAll(heading, body, footer);
        card.getStyleClass().add("sheet-card");
        card.setMaxSize(Region.USE_PREF_SIZE, Region.USE_PREF_SIZE);
        card.setAccessibleRole(AccessibleRole.DIALOG);
        card.setAccessibleText(title);
        setCardWidth(DEFAULT_WIDTH);
        getChildren().addAll(scrim, card);

        addEventFilter(KeyEvent.KEY_PRESSED, event -> {
            if (event.getCode() == KeyCode.ESCAPE) {
                event.consume();
                if (dismissible) {
                    close();
                }
            } else if (event.getCode() == KeyCode.ENTER && !event.isShiftDown()) {
                Node focused = getScene() == null ? null : getScene().getFocusOwner();
                if (focused instanceof ButtonBase button && isInside(button)) {
                    event.consume();
                    if (!button.isDisabled()) {
                        button.fire();
                    }
                } else if (focused instanceof TextInputControl && defaultButton != null) {
                    event.consume();
                    if (!defaultButton.isDisabled() && defaultButton.isVisible()) {
                        defaultButton.fire();
                    }
                }
            }
        });
    }

    /** One or two sentences under the title. */
    public ModalSheet subtitle(String text) {
        if (subtitle == null) {
            subtitle = new Label();
            subtitle.getStyleClass().addAll("modal-message", "sheet-subtitle");
            subtitle.setWrapText(true);
            heading.getChildren().add(subtitle);
        }
        subtitle.setText(text);
        subtitle.setVisible(text != null && !text.isBlank());
        subtitle.setManaged(subtitle.isVisible());
        return this;
    }

    /** Changes the title (a sheet that moves through steps, such as an import). */
    public void setTitle(String text) {
        title.setText(text);
        card.setAccessibleText(text);
    }

    public String titleText() {
        return title.getText();
    }

    public String subtitleText() {
        return subtitle == null || !subtitle.isVisible() ? "" : subtitle.getText();
    }

    /** The width of the card (the sheet shrinks with a narrow window). */
    public void setCardWidth(double width) {
        card.setPrefWidth(width);
        card.setMaxWidth(width);
    }

    /** The area between the title and the buttons. */
    public VBox body() {
        return body;
    }

    /** The card (tests and styling). */
    public VBox card() {
        return card;
    }

    /** Replaces the footer: {@code leading} nodes sit on the left, {@code buttons} on the right. */
    public void setFooter(Node[] leading, Node... buttons) {
        footer.getChildren().setAll(leading);
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        footer.getChildren().add(spacer);
        footer.getChildren().addAll(buttons);
        footer.setVisible(!footer.getChildren().isEmpty());
        footer.setManaged(footer.isVisible());
    }

    /** Replaces the footer buttons (right-aligned, in order). */
    public void setButtons(Node... buttons) {
        setFooter(new Node[0], buttons);
    }

    /** The button Enter presses from a text field. */
    public void setDefaultButton(Button button) {
        defaultButton = button;
    }

    /** What gets the keyboard focus when the sheet opens (default: the default button, else the first button). */
    public void setInitialFocus(Node node) {
        initialFocus = node;
    }

    /** Whether Escape closes the sheet (default true). */
    public void setDismissible(boolean value) {
        dismissible = value;
    }

    /** Called once the sheet closes, however it was closed. */
    public void setOnClose(Runnable action) {
        onClose = Objects.requireNonNull(action, "action");
    }

    /** True while the sheet is in a window. */
    public boolean isShowing() {
        return getParent() != null && !closing;
    }

    /** Opens the sheet in the window of {@code anchor}. */
    public void show(Node anchor) {
        StackPane layer = Overlays.find(anchor).modals();
        layer.getChildren().add(this);
        Scene scene = getScene();
        if (scene != null) {
            focusBefore = scene.getFocusOwner();
            focusTrap = (obs, old, owner) -> {
                if (owner != null && !isInside(owner) && !closing) {
                    focusFirst();
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
            grow.setFromX(0.97);
            grow.setFromY(0.97);
            grow.setToX(1);
            grow.setToY(1);
            grow.setInterpolator(Motion.EASE_OUT);
            new ParallelTransition(fade, grow).play();
        }
    }

    /** Moves the focus to the initial control (call after replacing the body). */
    public void focusFirst() {
        if (closing || getScene() == null) {
            return;
        }
        Node target = initialFocus != null && initialFocus.getScene() != null && !initialFocus.isDisabled()
                ? initialFocus : defaultButton != null && !defaultButton.isDisabled() ? defaultButton : null;
        if (target == null) {
            target = footer.getChildren().stream()
                    .filter(n -> n instanceof ButtonBase && !n.isDisabled() && n.isVisible())
                    .reduce((first, second) -> second).orElse(card);
        }
        target.requestFocus();
    }

    /** Closes the sheet. Idempotent. */
    public void close() {
        if (closing || getParent() == null) {
            return;
        }
        closing = true;
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
        };
        if (Motion.reduced() || scene == null) {
            remove.run();
        } else {
            Motion.fadeOut(this, Duration.millis(120), remove);
        }
        onClose.run();
    }

    private boolean isInside(Node node) {
        for (Node n = node; n != null; n = n.getParent()) {
            if (n == card) {
                return true;
            }
        }
        return false;
    }
}
