package edu.learnsphere.app.ui.study;

import edu.learnsphere.app.theme.Motion;
import edu.learnsphere.app.ui.Disposer;
import edu.learnsphere.app.ui.UiTasks;
import edu.learnsphere.app.ui.components.Formats;
import edu.learnsphere.app.ui.components.IconButton;
import edu.learnsphere.app.ui.components.Icons;
import edu.learnsphere.app.ui.components.Skeleton;
import edu.learnsphere.core.error.UserMessage;
import edu.learnsphere.core.service.ItemDetail;
import edu.learnsphere.core.service.RenderedPage;
import edu.learnsphere.core.service.SourcePassage;
import edu.learnsphere.core.service.SourceRef;
import edu.learnsphere.core.service.Workspace;
import edu.learnsphere.core.spi.NormalizedRect;
import java.io.ByteArrayInputStream;
import java.util.List;
import java.util.Objects;
import javafx.animation.FadeTransition;
import javafx.animation.ParallelTransition;
import javafx.animation.TranslateTransition;
import javafx.geometry.Pos;
import javafx.scene.AccessibleRole;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Pane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.shape.Rectangle;
import javafx.util.Duration;
import org.kordamp.ikonli.feather.Feather;

/**
 * "From your material" without leaving the session: a sheet over the right of the study page showing the page a
 * question comes from, with its passage highlighted and quoted. Pages of a passage that spans several are
 * stepped through with the arrows. Escape, the close button or a click beside the sheet closes it, and the
 * session carries on where it was. FX thread only.
 */
final class SourceSheet extends StackPane {

    /** Width of the sheet. */
    static final double WIDTH = 600;
    /** Width the page image is shown at. */
    private static final double PAGE_WIDTH = WIDTH - 2 * 32;
    /** Render scale: crisp on high-density screens. */
    private static final double SCALE = 2;

    private final Workspace workspace;
    private final Disposer disposer;
    private final String itemId;
    private final SourceRef source;
    private final Runnable onClose;
    private final VBox sheet = new VBox();
    private final VBox content = new VBox(24);
    private final Label pageLabel = new Label();
    private final Button previous = new IconButton(Feather.CHEVRON_LEFT, "Previous page").small();
    private final Button next = new IconButton(Feather.CHEVRON_RIGHT, "Next page").small();
    private final Button close = new IconButton(Feather.X, "Close");
    private String passageText;
    private int page;
    private boolean closing;

    SourceSheet(Workspace workspace, Disposer disposer, String itemId, SourceRef source, Runnable onClose) {
        this.workspace = Objects.requireNonNull(workspace, "workspace");
        this.disposer = Objects.requireNonNull(disposer, "disposer");
        this.itemId = Objects.requireNonNull(itemId, "itemId");
        this.source = Objects.requireNonNull(source, "source");
        this.onClose = Objects.requireNonNull(onClose, "onClose");
        this.page = Math.max(1, source.pageStart());
        getStyleClass().add("source-layer");

        Region scrim = new Region();
        scrim.getStyleClass().add("source-scrim");
        scrim.setOnMouseClicked(event -> close());

        Label overline = new Label("FROM YOUR MATERIAL");
        overline.getStyleClass().addAll("text-overline", "source-overline");
        Label title = new Label(source.documentTitle().isBlank() ? "Your material" : source.documentTitle());
        title.getStyleClass().addAll("text-title", "source-title");
        title.setWrapText(true);
        title.setMinHeight(Region.USE_PREF_SIZE);
        VBox titles = new VBox(6, overline, title);
        HBox.setHgrow(titles, Priority.ALWAYS);
        HBox header = new HBox(16, titles, close);
        header.setAlignment(Pos.TOP_LEFT);
        header.getStyleClass().add("source-header");
        close.setOnAction(event -> close());

        pageLabel.getStyleClass().addAll("medium", "source-page-label");
        previous.setOnAction(event -> show(page - 1));
        next.setOnAction(event -> show(page + 1));
        HBox pager = new HBox(4, previous, pageLabel, next);
        pager.setAlignment(Pos.CENTER_LEFT);
        pager.getStyleClass().add("source-pager");
        boolean severalPages = source.pageEnd() > source.pageStart();
        previous.setVisible(severalPages);
        previous.setManaged(severalPages);
        next.setVisible(severalPages);
        next.setManaged(severalPages);

        ScrollPane scroller = new ScrollPane(content);
        scroller.setFitToWidth(true);
        scroller.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        scroller.getStyleClass().add("source-scroller");
        content.getStyleClass().add("source-content");
        VBox.setVgrow(scroller, Priority.ALWAYS);

        sheet.getChildren().addAll(header, pager, scroller);
        sheet.getStyleClass().add("source-sheet");
        sheet.setMinWidth(WIDTH);
        sheet.setPrefWidth(WIDTH);
        sheet.setMaxWidth(WIDTH);
        sheet.setAccessibleRole(AccessibleRole.DIALOG);
        sheet.setAccessibleText("From your material: " + title.getText());
        StackPane.setAlignment(sheet, Pos.CENTER_RIGHT);
        getChildren().addAll(scrim, sheet);
    }

    /** Slides the sheet in and loads the page. */
    void open() {
        if (Motion.reduced()) {
            Motion.fadeIn(this, Motion.ROUTE);
        } else {
            FadeTransition fade = new FadeTransition(Motion.ROUTE, this);
            fade.setFromValue(0);
            fade.setToValue(1);
            TranslateTransition slide = new TranslateTransition(Duration.millis(220), sheet);
            slide.setFromX(48);
            slide.setToX(0);
            slide.setInterpolator(Motion.EASE_OUT);
            new ParallelTransition(fade, slide).play();
        }
        close.requestFocus();
        show(page);
    }

    /** True once {@link #close()} started. */
    boolean isClosing() {
        return closing;
    }

    /** Closes the sheet and tells the page. */
    void close() {
        if (closing) {
            return;
        }
        closing = true;
        Runnable done = () -> {
            if (getParent() instanceof Pane parent) {
                parent.getChildren().remove(this);
            }
            onClose.run();
        };
        if (Motion.reduced() || getScene() == null) {
            done.run();
        } else {
            Motion.fadeOut(this, Duration.millis(140), done);
        }
    }

    private void show(int number) {
        page = Math.max(source.pageStart(), Math.min(Math.max(source.pageStart(), source.pageEnd()), number));
        pageLabel.setText(source.pageEnd() > source.pageStart()
                ? "Page " + page + "  ·  " + Formats.pages(source.pageStart(), source.pageEnd())
                : "Page " + page);
        previous.setDisable(page <= source.pageStart());
        next.setDisable(page >= source.pageEnd());
        Skeleton placeholder = Skeleton.block(PAGE_WIDTH, PAGE_WIDTH * 1.3);
        placeholder.getStyleClass().add("source-page-skeleton");
        content.getChildren().setAll(Skeleton.text(3), placeholder);
        int wanted = page;
        disposer.add(UiTasks.run(() -> load(wanted), loaded -> {
            if (wanted == page && !closing) {
                render(loaded);
            }
        }, this::showError));
    }

    private record Loaded(String passage, RenderedPage page, List<NormalizedRect> highlights) {
    }

    private Loaded load(int number) {
        String passage = passageText;
        if (passage == null) {
            ItemDetail detail = workspace.items().get(itemId);
            SourcePassage from = detail.source();
            passage = from == null ? "" : from.text();
        }
        RenderedPage rendered = workspace.documents().renderPage(source.documentId(), number, SCALE);
        List<NormalizedRect> highlights = passage.isBlank() ? List.of()
                : workspace.documents().locatePassage(source.documentId(), number, passage);
        return new Loaded(passage, rendered, highlights);
    }

    private void render(Loaded loaded) {
        passageText = loaded.passage();
        content.getChildren().clear();
        if (!loaded.passage().isBlank()) {
            Label quote = new Label(Formats.shorten(loaded.passage(), 600));
            quote.getStyleClass().addAll("text-reading", "source-quote");
            quote.setWrapText(true);
            quote.setMinHeight(Region.USE_PREF_SIZE);
            HBox.setHgrow(quote, Priority.ALWAYS);
            Region rule = new Region();
            rule.getStyleClass().add("gloss-rule");
            HBox passage = new HBox(16, rule, quote);
            passage.getStyleClass().add("gloss");
            content.getChildren().add(passage);
        }
        Image image = new Image(new ByteArrayInputStream(loaded.page().png()));
        ImageView view = new ImageView(image);
        view.setPreserveRatio(true);
        view.setSmooth(true);
        view.setFitWidth(PAGE_WIDTH);
        double height = image.getWidth() <= 0 ? PAGE_WIDTH * 1.3 : PAGE_WIDTH * image.getHeight() / image.getWidth();
        Pane marks = new Pane();
        marks.setMouseTransparent(true);
        for (NormalizedRect rect : loaded.highlights()) {
            Rectangle mark = new Rectangle(rect.x() * PAGE_WIDTH - 2, rect.y() * height - 2,
                    rect.width() * PAGE_WIDTH + 4, rect.height() * height + 4);
            mark.setArcWidth(4);
            mark.setArcHeight(4);
            mark.getStyleClass().add("passage-highlight");
            marks.getChildren().add(mark);
        }
        marks.setPrefSize(PAGE_WIDTH, height);
        StackPane paper = new StackPane(view, marks);
        paper.getStyleClass().add("source-paper");
        paper.setMinSize(PAGE_WIDTH, height);
        paper.setMaxSize(PAGE_WIDTH, height);
        paper.setAccessibleText("Page " + page + (loaded.highlights().isEmpty() ? "" : ", with the passage highlighted"));
        content.getChildren().add(paper);
        Motion.fadeIn(content, Motion.ROUTE);
    }

    private void showError(UserMessage message) {
        Label text = new Label(message.text(), Icons.of(Feather.ALERT_CIRCLE, 16));
        text.getStyleClass().add("source-error");
        text.setWrapText(true);
        Button retry = new Button("Try again");
        retry.getStyleClass().add("small");
        retry.setOnAction(event -> show(page));
        VBox box = new VBox(12, text, retry);
        box.getStyleClass().add("source-error-box");
        content.getChildren().setAll(box);
    }

    /** The close button (tests). */
    Button closeButton() {
        return close;
    }

    /** Whether a passage highlight is drawn (tests). */
    boolean hasHighlight() {
        return content.lookup(".passage-highlight") != null;
    }

    /** The node that keeps focus inside the sheet. */
    Node focusTarget() {
        return close;
    }
}
