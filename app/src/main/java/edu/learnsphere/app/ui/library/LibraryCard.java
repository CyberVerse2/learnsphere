package edu.learnsphere.app.ui.library;

import edu.learnsphere.app.ui.components.CourseCard;
import edu.learnsphere.app.ui.components.IconButton;
import edu.learnsphere.core.service.CourseSummary;
import java.util.Objects;
import java.util.function.Supplier;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.geometry.Side;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Label;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.StackPane;
import org.kordamp.ikonli.feather.Feather;

/**
 * A course card in the Library with its menu: a round "more" button on the cover (shown on hover and for the
 * keyboard), the same menu on right-click, and on the keyboard with the context-menu key or Shift+F10 while
 * the card has focus. A shared course shows its version on the cover. Archived courses and courses the learner
 * left look quieter than the rest.
 */
public final class LibraryCard extends StackPane {

    private final CourseCard card;
    private final IconButton more;
    private final Supplier<ContextMenu> menu;
    private ContextMenu open;

    /**
     * @param menu builds the card's menu each time it opens, so it reflects the course as it is
     */
    public LibraryCard(CourseSummary course, CourseCard.Mode mode, Supplier<ContextMenu> menu) {
        this.menu = Objects.requireNonNull(menu, "menu");
        getStyleClass().add("library-card");
        card = new CourseCard(course, mode);
        if (course.isArchived()) {
            getStyleClass().add("archived");
        } else if (!course.enrolled() && mode == CourseCard.Mode.STUDYING) {
            getStyleClass().add("not-studying");
        }
        more = new IconButton(Feather.MORE_HORIZONTAL, "More for " + course.title()).small();
        more.getStyleClass().add("card-menu-button");
        more.setOnAction(event -> showMenu(true));
        StackPane.setAlignment(more, Pos.TOP_RIGHT);
        StackPane.setMargin(more, new Insets(10, 10, 0, 0));
        getChildren().add(card);
        if (mode == CourseCard.Mode.STUDYING && !course.createdHere() && course.versionNumber() > 0) {
            Label version = new Label("Version " + course.versionNumber());
            version.getStyleClass().addAll("text-caption", "card-version");
            version.setMouseTransparent(true);
            StackPane.setAlignment(version, Pos.TOP_LEFT);
            StackPane.setMargin(version, new Insets(12, 0, 0, 12));
            getChildren().add(version);
        }
        getChildren().add(more);

        card.setOnContextMenuRequested(event -> {
            event.consume();
            close();
            open = menu.get();
            open.show(card, event.getScreenX(), event.getScreenY());
        });
        card.addEventFilter(KeyEvent.KEY_PRESSED, event -> {
            if (event.getCode() == KeyCode.CONTEXT_MENU || (event.getCode() == KeyCode.F10 && event.isShiftDown())) {
                event.consume();
                showMenu(false);
            }
        });
    }

    public CourseCard card() {
        return card;
    }

    public CourseSummary course() {
        return card.course();
    }

    /** The "more" button (tests). */
    public IconButton menuButton() {
        return more;
    }

    /** The menu while it is open, or null. */
    public ContextMenu openMenu() {
        return open != null && open.isShowing() ? open : null;
    }

    /** Opens the menu under the "more" button. */
    public void showMenu(boolean fromButton) {
        close();
        open = menu.get();
        open.show(fromButton ? more : card, fromButton ? Side.BOTTOM : Side.TOP, fromButton ? -160 : 16,
                fromButton ? 4 : 48);
    }

    private void close() {
        if (open != null) {
            open.hide();
            open = null;
        }
    }
}
