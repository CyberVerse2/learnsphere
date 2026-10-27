package edu.learnsphere.app.ui.studio;

import edu.learnsphere.app.ui.Disposer;
import edu.learnsphere.app.ui.UiTasks;
import edu.learnsphere.app.ui.components.ActivityIndicator;
import edu.learnsphere.app.ui.components.IconButton;
import edu.learnsphere.core.service.DocumentSummary;
import edu.learnsphere.core.service.RenderedPage;
import edu.learnsphere.core.service.Workspace;
import java.io.ByteArrayInputStream;
import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import org.kordamp.ikonli.feather.Feather;

/**
 * Shows a PDF's pages one at a time in a dialog, with previous and next buttons (and the left and right
 * arrows), so a creator can check what they added.
 */
final class PagePreviewDialog {

    private static final double PAGE_WIDTH = 600;
    private static final double PAGE_HEIGHT = 520;
    private static final double SCALE = 2;

    private final Workspace workspace;
    private final DocumentSummary document;
    private final ImageView image = new ImageView();
    private final StackPane frame;
    private final Label position = new Label();
    private final IconButton previous = new IconButton(Feather.CHEVRON_LEFT, "Previous page");
    private final IconButton next = new IconButton(Feather.CHEVRON_RIGHT, "Next page");
    private final Disposer disposer = new Disposer();
    private int page = 1;

    private PagePreviewDialog(Workspace workspace, DocumentSummary document) {
        this.workspace = workspace;
        this.document = document;
        image.setPreserveRatio(true);
        image.setFitWidth(PAGE_WIDTH);
        image.setFitHeight(PAGE_HEIGHT);
        image.setSmooth(true);
        frame = new StackPane(image);
        frame.getStyleClass().add("page-preview");
        frame.setMinHeight(PAGE_HEIGHT + 32);
        position.getStyleClass().add("text-caption");
        position.setMinWidth(120);
        position.setAlignment(Pos.CENTER);
        previous.small();
        next.small();
        previous.setOnAction(event -> go(page - 1));
        next.setOnAction(event -> go(page + 1));
    }

    /** Opens the preview of {@code document} at its first page. */
    static void show(javafx.scene.Node anchor, Workspace workspace, DocumentSummary document) {
        PagePreviewDialog preview = new PagePreviewDialog(workspace, document);
        HBox pager = new HBox(8, preview.previous, preview.position, preview.next);
        pager.setAlignment(Pos.CENTER);
        VBox content = new VBox(12, preview.frame, pager);
        content.addEventFilter(KeyEvent.KEY_PRESSED, event -> {
            switch (event.getCode()) {
                case LEFT, PAGE_UP -> {
                    event.consume();
                    preview.go(preview.page - 1);
                }
                case RIGHT, PAGE_DOWN -> {
                    event.consume();
                    preview.go(preview.page + 1);
                }
                default -> {
                }
            }
        });
        StudioDialog dialog = StudioDialog.of(document.title())
                .message(StudioFormats.documentMeta(document))
                .content(content)
                .wide()
                .closeOnly("Close");
        dialog.show(anchor).thenAccept(closed -> preview.disposer.dispose());
        preview.go(1);
    }

    private void go(int target) {
        int pages = Math.max(1, document.pageCount());
        if (target < 1 || target > pages) {
            return;
        }
        page = target;
        position.setText("Page " + page + " of " + pages);
        previous.setDisable(page <= 1);
        next.setDisable(page >= pages);
        ActivityIndicator spinner = new ActivityIndicator(24);
        frame.getChildren().setAll(image, spinner);
        image.setOpacity(0.35);
        int requested = page;
        disposer.add(UiTasks.run(() -> workspace.documents().renderPage(document.id(), requested, SCALE), rendered -> {
            if (requested == page) {
                showImage(rendered);
            }
        }, message -> {
            if (requested == page) {
                Label error = new Label(message.text());
                error.getStyleClass().add("text-muted");
                error.setWrapText(true);
                frame.getChildren().setAll(error);
            }
        }));
    }

    private void showImage(RenderedPage rendered) {
        image.setImage(new Image(new ByteArrayInputStream(rendered.png())));
        image.setOpacity(1);
        image.setAccessibleText("Page " + rendered.pageNumber() + " of " + document.title());
        frame.getChildren().setAll(image);
    }
}
