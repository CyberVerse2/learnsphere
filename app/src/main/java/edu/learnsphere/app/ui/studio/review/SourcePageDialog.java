package edu.learnsphere.app.ui.studio.review;

import edu.learnsphere.app.ui.components.Formats;
import edu.learnsphere.app.ui.components.Icons;
import edu.learnsphere.app.ui.components.SegmentedControl;
import edu.learnsphere.app.ui.components.Skeleton;
import edu.learnsphere.core.error.UserMessage;
import edu.learnsphere.core.service.RenderedPage;
import edu.learnsphere.core.service.SourcePassage;
import edu.learnsphere.core.spi.NormalizedRect;
import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.IntStream;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Pane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.shape.Rectangle;
import org.kordamp.ikonli.feather.Feather;

/**
 * The PDF page a passage comes from, with the passage highlighted, so the creator can check a question
 * against the page itself. A passage that runs over several pages offers each of them.
 */
final class SourcePageDialog {

    /** Width the page is shown at. */
    static final double PAGE_WIDTH = 640;
    /** The card's padding, the body's padding beside a scroll bar, and the scroll bar. */
    private static final double CHROME = 96;

    /** A rendered page and where the passage is on it. */
    record Page(RenderedPage image, List<NormalizedRect> marks) {
    }

    private final ReviewQueueTab tab;
    private final SourcePassage source;
    private final String textToFind;
    private final ReviewModal modal;
    private final StackPane stage = new StackPane();
    private final Label caption = new Label();
    private int page;

    SourcePageDialog(ReviewQueueTab tab, SourcePassage source, String textToFind) {
        this.tab = tab;
        this.source = source;
        this.textToFind = textToFind;
        int first = source.ref().pageStart();
        int last = Math.max(first, source.ref().pageEnd());
        stage.getStyleClass().add("source-page");
        stage.setMinHeight(420);
        caption.getStyleClass().add("text-caption");
        caption.setWrapText(true);
        HBox.setHgrow(caption, Priority.ALWAYS);
        caption.setMaxWidth(Double.MAX_VALUE);

        VBox body = new VBox(16);
        if (last > first) {
            List<Integer> pages = IntStream.rangeClosed(first, last).boxed().toList();
            SegmentedControl<Integer> picker = new SegmentedControl<>(pages, p -> "Page " + p);
            picker.setValue(first);
            picker.setAccessibleText("Page");
            picker.valueProperty().addListener((obs, old, value) -> {
                if (value != null) {
                    show(value);
                }
            });
            body.getChildren().add(picker);
        }
        body.getChildren().add(stage);
        Button close = new Button("Close");
        modal = new ReviewModal(source.ref().documentTitle(), PAGE_WIDTH + CHROME)
                .owner(this)
                .subtitle(Formats.pages(first, last)
                        + (source.sectionTitle() == null || source.sectionTitle().isBlank() ? "" : " · " + source.sectionTitle()))
                .body(body)
                .footer(caption, close);
        close.setOnAction(event -> modal.close());
        modal.getStylesheets().add(ReviewQueueTab.STYLESHEET);
        modal.setInitialFocus(close);
        show(first);
    }

    void open(Node anchor) {
        modal.open(anchor);
    }

    ReviewModal modal() {
        return modal;
    }

    /** The page on screen. */
    int page() {
        return page;
    }

    /** How many highlight marks the page on screen has (tests). */
    int markCount() {
        return stage.lookupAll(".passage-highlight").size();
    }

    private void show(int number) {
        page = number;
        stage.getChildren().setAll(Skeleton.block(PAGE_WIDTH, 420));
        caption.setText("");
        String documentId = source.ref().documentId();
        tab.call(() -> {
            RenderedPage image = tab.workspace().documents().renderPage(documentId, number, 2);
            List<NormalizedRect> marks = textToFind == null || textToFind.isBlank() ? List.of()
                    : tab.workspace().documents().locatePassage(documentId, number, textToFind);
            return new Page(image, marks);
        }, loaded -> {
            if (page == number) {
                stage.getChildren().setAll(render(loaded));
                caption.setText(loaded.marks().isEmpty()
                        ? "The passage isn't marked on this page. Look for it near the section heading."
                        : "Highlighted: the passage the question was written from.");
            }
        }, message -> {
            if (page == number) {
                showProblem(message);
            }
        });
    }

    private Node render(Page loaded) {
        Image image = new Image(new ByteArrayInputStream(loaded.image().png()));
        ImageView view = new ImageView(image);
        view.setPreserveRatio(true);
        view.setFitWidth(PAGE_WIDTH);
        double height = PAGE_WIDTH * image.getHeight() / Math.max(1, image.getWidth());
        Pane overlay = new Pane();
        overlay.setMouseTransparent(true);
        List<Node> marks = new ArrayList<>();
        for (NormalizedRect rect : loaded.marks()) {
            Rectangle mark = new Rectangle(rect.x() * PAGE_WIDTH, rect.y() * height, rect.width() * PAGE_WIDTH,
                    rect.height() * height);
            mark.getStyleClass().add("passage-highlight");
            mark.setArcWidth(6);
            mark.setArcHeight(6);
            marks.add(mark);
        }
        overlay.getChildren().setAll(marks);
        StackPane sheet = new StackPane(view, overlay);
        sheet.getStyleClass().add("source-page-image");
        sheet.setMaxSize(PAGE_WIDTH, height);
        view.setAccessibleText("Page " + page + " of " + source.ref().documentTitle());
        if (!loaded.marks().isEmpty()) {
            double top = loaded.marks().getFirst().y();
            javafx.application.Platform.runLater(() -> modal.scroller().setVvalue(Math.max(0, top - 0.12)));
        }
        return sheet;
    }

    private void showProblem(UserMessage message) {
        Label text = new Label(message.text(), Icons.of(Feather.ALERT_CIRCLE, 16));
        text.getStyleClass().add("text-muted");
        text.setWrapText(true);
        VBox box = new VBox(text);
        box.setAlignment(Pos.CENTER);
        stage.getChildren().setAll(box);
    }
}
