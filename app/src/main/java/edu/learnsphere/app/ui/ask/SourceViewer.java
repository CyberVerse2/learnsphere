package edu.learnsphere.app.ui.ask;

import edu.learnsphere.app.theme.Motion;
import edu.learnsphere.app.theme.Theme;
import edu.learnsphere.app.ui.Disposer;
import edu.learnsphere.app.ui.UiTasks;
import edu.learnsphere.app.ui.components.Formats;
import edu.learnsphere.app.ui.components.IconButton;
import edu.learnsphere.app.ui.components.Icons;
import edu.learnsphere.app.ui.components.Skeleton;
import edu.learnsphere.core.error.LearnSphereException;
import edu.learnsphere.core.error.UserMessage;
import edu.learnsphere.core.model.Citation;
import edu.learnsphere.core.model.PageSource;
import edu.learnsphere.core.service.RenderedPage;
import edu.learnsphere.core.service.Workspace;
import edu.learnsphere.core.spi.NormalizedRect;
import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import javafx.animation.PauseTransition;
import javafx.application.Platform;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ContentDisplay;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyCodeCombination;
import javafx.scene.input.KeyCombination;
import javafx.scene.input.KeyEvent;
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
 * The page a citation points at, beside the conversation: the PDF page rendered sharply for the panel's width
 * (and the screen's pixel density), the cited passage marked in the highlight colour with a pencil mark in the
 * margin, and scrolled into view. The learner can turn pages (Page Up, Page Down), zoom (Shortcut with +, -
 * and 0) and open the course; Escape closes the panel. A page read from a scanned image says so. FX thread
 * only.
 */
final class SourceViewer extends VBox {

    /** Zoom steps, relative to fitting the page to the panel's width. */
    static final double[] ZOOMS = {1, 1.25, 1.5, 2, 3};
    /** US Letter width in points: the guess before a document's first page has been rendered. */
    private static final double DEFAULT_PAGE_POINTS = 612;
    private static final double MIN_SCALE = 0.5;
    private static final double MAX_SCALE = 6;
    /** Space around the page inside its well. */
    private static final double PAGE_INSET = 16;
    /** How long resizing or zooming waits before a sharper page is drawn. */
    private static final Duration RERENDER_DELAY = Duration.millis(220);

    private static final KeyCombination ZOOM_IN = new KeyCodeCombination(KeyCode.EQUALS, KeyCombination.SHORTCUT_DOWN);
    private static final KeyCombination ZOOM_IN_PLUS = new KeyCodeCombination(KeyCode.PLUS, KeyCombination.SHORTCUT_DOWN);
    private static final KeyCombination ZOOM_IN_ADD = new KeyCodeCombination(KeyCode.ADD, KeyCombination.SHORTCUT_DOWN);
    private static final KeyCombination ZOOM_OUT = new KeyCodeCombination(KeyCode.MINUS, KeyCombination.SHORTCUT_DOWN);
    private static final KeyCombination ZOOM_OUT_SUBTRACT =
            new KeyCodeCombination(KeyCode.SUBTRACT, KeyCombination.SHORTCUT_DOWN);
    private static final KeyCombination ZOOM_RESET = new KeyCodeCombination(KeyCode.DIGIT0, KeyCombination.SHORTCUT_DOWN);

    /** What the viewer asks of the screen. */
    interface Host {
        /** The open profile's services, or null. */
        Workspace workspace();

        /** Closes the viewer. */
        void close();

        /** Opens the course the source belongs to. */
        void openCourse();
    }

    private final Host host;
    private final Label title = new Label();
    private final Label where = new Label();
    private final IconButton close = new IconButton(Feather.X, "Close sources").small();
    private final IconButton previous = new IconButton(Feather.CHEVRON_LEFT, "Previous page").small();
    private final IconButton next = new IconButton(Feather.CHEVRON_RIGHT, "Next page").small();
    private final Label pageLabel = new Label();
    private final IconButton zoomOut = new IconButton(Feather.ZOOM_OUT, "Zoom out").small();
    private final IconButton zoomIn = new IconButton(Feather.ZOOM_IN, "Zoom in").small();
    private final Button zoomLabel = new Button("100%");
    private final HBox toolbar;
    private final Label note = new Label();
    private final VBox quote = new VBox();
    private final StackPane well = new StackPane();
    private final ScrollPane scroller = new ScrollPane();
    private final StackPane holder = new StackPane();
    private final StackPane sheet = new StackPane();
    private final ImageView imageView = new ImageView();
    private final Pane marks = new Pane();
    private final Button openInCourse = new Button("Open in course", Icons.of(Feather.ARROW_RIGHT, 14));
    private final VBox body;
    private final PauseTransition rerender = new PauseTransition(RERENDER_DELAY);
    /** Points per page width of each document rendered so far, so the next page renders at the right scale. */
    private final Map<String, Double> pagePoints = new HashMap<>();

    private Disposer loads = new Disposer();
    private int request;
    private Citation citation;
    /** The document whose page count is known. */
    private String shownDocument;
    private String passageText;
    private int page;
    private int pageCount;
    private int zoomIndex;
    private Image image;
    private int renderedWidthPx;
    private List<NormalizedRect> rects = List.of();
    private PageSource pageSource;
    private boolean revealPending;
    private boolean corrected;
    private boolean loading;
    private UserMessage error;

    SourceViewer(Host host) {
        this.host = Objects.requireNonNull(host, "host");
        getStyleClass().add("ask-source");
        setFocusTraversable(false);

        title.getStyleClass().addAll("text-heading", "ask-source-doc");
        title.setWrapText(true);
        title.setMaxHeight(44);
        where.getStyleClass().addAll("text-caption", "ask-source-where");
        VBox heading = new VBox(4, title, where);
        heading.setMinWidth(0);
        HBox.setHgrow(heading, Priority.ALWAYS);
        close.setOnAction(event -> host.close());
        HBox head = new HBox(12, heading, close);
        head.setAlignment(Pos.TOP_LEFT);
        head.getStyleClass().add("ask-source-head");

        previous.setOnAction(event -> turn(-1));
        next.setOnAction(event -> turn(1));
        pageLabel.getStyleClass().add("ask-source-page-label");
        pageLabel.setMinWidth(Region.USE_PREF_SIZE);
        zoomOut.setOnAction(event -> zoom(-1));
        zoomIn.setOnAction(event -> zoom(1));
        zoomLabel.getStyleClass().addAll("quiet", "neutral", "small", "ask-zoom-label");
        zoomLabel.setAccessibleText("Fit the page to the panel");
        zoomLabel.setOnAction(event -> setZoom(0));
        zoomLabel.setMinWidth(Region.USE_PREF_SIZE);
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        toolbar = new HBox(4, previous, pageLabel, next, spacer, zoomOut, zoomLabel, zoomIn);
        toolbar.setAlignment(Pos.CENTER_LEFT);
        toolbar.getStyleClass().add("ask-source-toolbar");

        note.getStyleClass().add("ask-source-note");
        note.setWrapText(true);
        note.setGraphic(Icons.of(Feather.INFO, 14));
        note.setGraphicTextGap(8);
        note.setMinHeight(Region.USE_PREF_SIZE);
        note.setMaxWidth(Double.MAX_VALUE);
        quote.getStyleClass().add("ask-source-quote");

        imageView.setPreserveRatio(false);
        imageView.setSmooth(true);
        marks.setMouseTransparent(true);
        marks.getStyleClass().add("ask-marks");
        sheet.getChildren().addAll(imageView, marks);
        sheet.getStyleClass().addAll("ask-page-sheet", Theme.SCOPE_LIGHT);
        sheet.setAlignment(Pos.TOP_LEFT);
        holder.getChildren().add(sheet);
        holder.setAlignment(Pos.TOP_CENTER);
        holder.getStyleClass().add("ask-page-holder");
        scroller.setContent(holder);
        scroller.getStyleClass().add("ask-page-scroller");
        scroller.setFitToWidth(true);
        scroller.setPannable(true);
        scroller.viewportBoundsProperty().addListener((obs, old, bounds) -> {
            if (old == null || Math.abs(old.getWidth() - bounds.getWidth()) > 0.5) {
                layoutSheet();
                scheduleSharperRender();
            }
        });
        well.getChildren().add(scroller);
        well.getStyleClass().add("ask-page-well");
        VBox.setVgrow(well, Priority.ALWAYS);

        openInCourse.getStyleClass().addAll("quiet", "small", "ask-open-course");
        openInCourse.setContentDisplay(ContentDisplay.RIGHT);
        openInCourse.setOnAction(event -> host.openCourse());
        HBox footer = new HBox(openInCourse);
        footer.getStyleClass().add("ask-source-footer");
        footer.setAlignment(Pos.CENTER_LEFT);

        body = new VBox(toolbar, note, quote, well, footer);
        body.getStyleClass().add("ask-source-body");
        VBox.setVgrow(body, Priority.ALWAYS);
        getChildren().addAll(head, body);

        rerender.setOnFinished(event -> {
            if (citation != null && image != null && !loading) {
                load(true);
            }
        });
        addEventFilter(KeyEvent.KEY_PRESSED, this::onKey);
        showHint();
    }

    // ---- what it shows ----------------------------------------------------------------------------

    /** The hint shown before any citation is chosen. */
    void showHint() {
        cancelLoads();
        citation = null;
        title.setText("Sources");
        where.setText("");
        where.setVisible(false);
        where.setManaged(false);
        StackPane icon = new StackPane(Icons.of(Feather.BOOK_OPEN, 22));
        icon.getStyleClass().add("ask-source-hint-icon");
        icon.setMaxSize(48, 48);
        icon.setMinSize(48, 48);
        Label text = new Label("Choose a page reference in an answer to see the passage it came from, marked on its page.");
        text.getStyleClass().add("ask-source-hint-text");
        text.setWrapText(true);
        text.setMaxWidth(280);
        text.setTextAlignment(javafx.scene.text.TextAlignment.CENTER);
        VBox hint = new VBox(14, icon, text);
        hint.setAlignment(Pos.CENTER);
        hint.getStyleClass().add("ask-source-hint");
        VBox.setVgrow(hint, Priority.ALWAYS);
        getChildren().set(1, hint);
    }

    /**
     * Opens the page {@code cited} starts on, with its passage marked.
     *
     * @param section the section the passage comes from, or null
     * @param passage the passage's full text, used to mark it when the citation has no snippet; or null
     */
    void show(Citation cited, String section, String passage) {
        Objects.requireNonNull(cited, "cited");
        boolean samePage = cited.equals(citation) && page == cited.pageStart() && image != null && error == null;
        citation = cited;
        passageText = passage;
        getChildren().set(1, body);
        title.setText(cited.documentTitle().isBlank() ? "Course material" : cited.documentTitle());
        String place = Formats.pages(cited) + (section == null || section.isBlank() ? "" : " · " + section.strip());
        where.setText(place);
        where.setVisible(true);
        where.setManaged(true);
        if (samePage) {
            revealHighlight();
            return;
        }
        if (!cited.documentId().equals(shownDocument)) {
            pageCount = 0;
            shownDocument = cited.documentId();
        }
        page = cited.pageStart();
        revealPending = true;
        load(false);
    }

    /** Names the section the shown passage comes from, once it is known. */
    void describe(String section) {
        if (citation == null) {
            return;
        }
        where.setText(Formats.pages(citation) + (section == null || section.isBlank() ? "" : " · " + section.strip()));
    }

    /** The citation shown, or null. */
    Citation citation() {
        return citation;
    }

    int page() {
        return page;
    }

    int pageCount() {
        return pageCount;
    }

    /** Rectangles marking the passage on the page shown. */
    int highlightCount() {
        return (int) marks.getChildren().stream().filter(n -> n.getStyleClass().contains("ask-highlight")).count();
    }

    boolean isLoading() {
        return loading;
    }

    /** True once a page image is on screen. */
    boolean hasPage() {
        return image != null && sheet.getParent() == holder && !loading;
    }

    String noteText() {
        return note.isVisible() ? note.getText() : "";
    }

    double zoom() {
        return ZOOMS[zoomIndex];
    }

    /** Stops loading pages (the screen is going away). */
    void dispose() {
        cancelLoads();
        rerender.stop();
    }

    // ---- pages ------------------------------------------------------------------------------------

    private void turn(int delta) {
        if (citation == null) {
            return;
        }
        int target = page + delta;
        if (target < 1 || (pageCount > 0 && target > pageCount)) {
            return;
        }
        page = target;
        revealPending = true;
        load(false);
    }

    private void zoom(int delta) {
        setZoom(Math.max(0, Math.min(ZOOMS.length - 1, zoomIndex + delta)));
    }

    private void setZoom(int index) {
        if (index == zoomIndex || citation == null) {
            return;
        }
        double before = ZOOMS[zoomIndex];
        double centreX = scroller.getHvalue();
        double centreY = scroller.getVvalue();
        zoomIndex = index;
        scroller.setFitToWidth(index == 0);
        updateToolbar();
        layoutSheet();
        if (before != ZOOMS[index]) {
            Platform.runLater(() -> {
                scroller.setHvalue(centreX);
                scroller.setVvalue(centreY);
            });
        }
        scheduleSharperRender();
    }

    /** True when the passage cited is on {@code number}. */
    private boolean cites(int number) {
        return citation != null && number >= citation.pageStart() && number <= Math.max(citation.pageStart(), citation.pageEnd());
    }

    private void load(boolean keepImage) {
        Workspace workspace = host.workspace();
        if (workspace == null || citation == null) {
            return;
        }
        cancelLoads();
        int id = ++request;
        loading = true;
        error = null;
        if (!keepImage) {
            showPlaceholder();
            corrected = false;
        }
        updateToolbar();
        String documentId = citation.documentId();
        int number = page;
        double scale = scaleFor(documentId);
        String wanted = cites(number) ? highlightText() : null;
        boolean needCount = pageCount <= 0;
        loads.add(UiTasks.run(() -> {
            int count = needCount ? workspace.documents().get(documentId).pageCount() : -1;
            PageSource source = null;
            try {
                source = workspace.documents().page(documentId, number).source();
            } catch (LearnSphereException e) {
                // The page's text isn't known; the image still shows.
            }
            RenderedPage png = workspace.documents().renderPage(documentId, number, scale);
            Image decoded = new Image(new ByteArrayInputStream(png.png()));
            List<NormalizedRect> found = wanted == null ? List.of()
                    : workspace.documents().locatePassage(documentId, number, wanted);
            return new Loaded(count, source, png, decoded, found, scale);
        }, loaded -> {
            if (id == request) {
                apply(loaded, keepImage);
            }
        }, message -> {
            if (id == request) {
                showError(message);
            }
        }));
    }

    private record Loaded(int pageCount, PageSource source, RenderedPage png, Image image, List<NormalizedRect> rects,
                          double scale) {
    }

    private String highlightText() {
        if (citation.snippet() != null && !citation.snippet().isBlank()) {
            return citation.snippet();
        }
        return passageText == null || passageText.isBlank() ? null : passageText;
    }

    private void apply(Loaded loaded, boolean refresh) {
        loading = false;
        if (loaded.pageCount() > 0) {
            pageCount = loaded.pageCount();
        }
        if (loaded.png().widthPx() > 0) {
            pagePoints.put(citation.documentId(), loaded.png().widthPx() / loaded.scale());
        }
        image = loaded.image();
        renderedWidthPx = loaded.png().widthPx();
        rects = loaded.rects();
        pageSource = loaded.source();
        imageView.setImage(image);
        if (sheet.getParent() != holder) {
            holder.getChildren().setAll(sheet);
        }
        updateNote();
        updateToolbar();
        layoutSheet();
        if (!refresh) {
            Motion.fadeIn(sheet, Motion.ROUTE);
            if (!marks.getChildren().isEmpty()) {
                Motion.fadeIn(marks, Duration.millis(320));
            }
        }
        if (revealPending) {
            revealPending = false;
            revealHighlight();
        }
        if (!corrected && needsSharperRender(1.6)) {
            // The first guess at the page's size was off: draw it once more at the right scale.
            corrected = true;
            load(true);
        }
    }

    private void showPlaceholder() {
        image = null;
        imageView.setImage(null);
        marks.getChildren().clear();
        note.setVisible(false);
        note.setManaged(false);
        quote.setVisible(false);
        quote.setManaged(false);
        double width = Math.max(120, availableWidth());
        Skeleton shape = Skeleton.block(width, width * 1.294);
        shape.getStyleClass().add("ask-page-skeleton");
        holder.getChildren().setAll(shape);
    }

    private void showError(UserMessage message) {
        loading = false;
        error = message;
        image = null;
        updateToolbar();
        Label text = new Label(message.text());
        text.getStyleClass().add("ask-source-error-text");
        text.setWrapText(true);
        text.setMaxWidth(280);
        text.setTextAlignment(javafx.scene.text.TextAlignment.CENTER);
        Button retry = new Button("Try again", Icons.of(Feather.ROTATE_CW, 14));
        retry.getStyleClass().add("small");
        retry.setOnAction(event -> load(false));
        VBox box = new VBox(12, Icons.of(Feather.ALERT_CIRCLE, 22), text, retry);
        box.setAlignment(Pos.CENTER);
        box.getStyleClass().add("ask-source-error");
        holder.getChildren().setAll(box);
    }

    private void updateNote() {
        boolean scanned = pageSource == PageSource.AI_READ;
        boolean cited = cites(page);
        String text = null;
        if (scanned) {
            text = cited && rects.isEmpty()
                    ? "This page was read from a scanned image, so the cited passage isn't marked on it."
                    : "This page was read from a scanned image.";
        }
        note.setText(text == null ? "" : text);
        note.setVisible(text != null);
        note.setManaged(text != null);
        boolean showQuote = cited && rects.isEmpty() && citation.snippet() != null && !citation.snippet().isBlank();
        if (showQuote) {
            Label overline = new Label("THE CITED PASSAGE");
            overline.getStyleClass().addAll("text-overline", "ask-source-quote-title");
            Label passage = new Label("“" + Formats.shorten(citation.snippet(), 320) + "”");
            passage.getStyleClass().addAll("ask-source-quote-text", "serif");
            passage.setWrapText(true);
            passage.setMinHeight(Region.USE_PREF_SIZE);
            quote.getChildren().setAll(overline, passage);
        }
        quote.setVisible(showQuote);
        quote.setManaged(showQuote);
    }

    private void updateToolbar() {
        boolean hasCitation = citation != null;
        pageLabel.setText(pageCount > 0 ? "Page " + page + " of " + pageCount : "Page " + page);
        previous.setDisable(!hasCitation || page <= 1);
        next.setDisable(!hasCitation || (pageCount > 0 && page >= pageCount));
        zoomOut.setDisable(!hasCitation || zoomIndex == 0 || image == null);
        zoomIn.setDisable(!hasCitation || zoomIndex == ZOOMS.length - 1 || image == null);
        zoomLabel.setText(Math.round(ZOOMS[zoomIndex] * 100) + "%");
    }

    // ---- layout and marks -----------------------------------------------------------------------

    /** The width the page fills at 100%. */
    private double availableWidth() {
        double viewport = scroller.getViewportBounds() == null ? 0 : scroller.getViewportBounds().getWidth();
        if (viewport <= 0) {
            viewport = Math.max(0, getWidth() - 48);
        }
        return Math.max(0, viewport - 2 * PAGE_INSET);
    }

    private double displayWidth() {
        return Math.floor(availableWidth() * ZOOMS[zoomIndex]);
    }

    private void layoutSheet() {
        if (image == null || image.getWidth() <= 0) {
            return;
        }
        double width = displayWidth();
        if (width <= 0) {
            return;
        }
        double height = Math.round(width * image.getHeight() / image.getWidth());
        imageView.setFitWidth(width);
        imageView.setFitHeight(height);
        sheet.setMinSize(width, height);
        sheet.setPrefSize(width, height);
        sheet.setMaxSize(width, height);
        marks.setPrefSize(width, height);
        drawMarks(width, height);
    }

    private void drawMarks(double width, double height) {
        List<Node> nodes = new ArrayList<>();
        for (NormalizedRect r : rects) {
            Rectangle mark = new Rectangle(r.x() * width - 2, r.y() * height - 1.5, r.width() * width + 4,
                    r.height() * height + 3);
            mark.getStyleClass().add("ask-highlight");
            mark.setArcWidth(5);
            mark.setArcHeight(5);
            nodes.add(mark);
        }
        for (NormalizedRect group : groups(rects)) {
            double x = Math.max(3, group.x() * width - 12);
            Rectangle pencil = new Rectangle(x, group.y() * height - 1.5, 3, group.height() * height + 3);
            pencil.getStyleClass().add("ask-highlight-mark");
            pencil.setArcWidth(3);
            pencil.setArcHeight(3);
            nodes.add(pencil);
        }
        marks.getChildren().setAll(nodes);
    }

    /** Rectangles merged into runs of lines that follow each other, for the margin marks. */
    static List<NormalizedRect> groups(List<NormalizedRect> rects) {
        List<NormalizedRect> sorted = new ArrayList<>(rects);
        sorted.sort(Comparator.comparingDouble(NormalizedRect::y));
        List<NormalizedRect> groups = new ArrayList<>();
        for (NormalizedRect r : sorted) {
            if (!groups.isEmpty()) {
                NormalizedRect last = groups.getLast();
                boolean follows = r.y() <= last.y() + last.height() + 0.025;
                boolean sameColumn = r.x() < last.x() + last.width() && last.x() < r.x() + r.width();
                if (follows && sameColumn) {
                    groups.set(groups.size() - 1, last.union(r));
                    continue;
                }
            }
            groups.add(r);
        }
        return groups;
    }

    /** Scrolls so the marked passage sits in the upper third of the panel. */
    private void revealHighlight() {
        Platform.runLater(() -> {
            if (image == null) {
                return;
            }
            scroller.applyCss();
            scroller.layout();
            double contentHeight = holder.getHeight();
            double viewport = scroller.getViewportBounds().getHeight();
            if (rects.isEmpty() || contentHeight <= viewport) {
                scroller.setVvalue(0);
                return;
            }
            double top = rects.stream().mapToDouble(NormalizedRect::y).min().orElse(0);
            double y = PAGE_INSET + top * sheet.getHeight() - viewport * 0.28;
            scroller.setVvalue(Math.max(0, Math.min(1, y / (contentHeight - viewport))));
            double contentWidth = holder.getWidth();
            double viewportWidth = scroller.getViewportBounds().getWidth();
            if (contentWidth > viewportWidth) {
                double left = rects.stream().mapToDouble(NormalizedRect::x).min().orElse(0);
                double x = PAGE_INSET + left * sheet.getWidth() - 24;
                scroller.setHvalue(Math.max(0, Math.min(1, x / (contentWidth - viewportWidth))));
            }
        });
    }

    // ---- sharpness ------------------------------------------------------------------------------

    private double outputScale() {
        if (getScene() != null && getScene().getWindow() != null) {
            return Math.max(1, getScene().getWindow().getOutputScaleX());
        }
        return 1;
    }

    /** The render scale for the panel's width at the current zoom, in quarter steps so pages share renders. */
    private double scaleFor(String documentId) {
        double points = pagePoints.getOrDefault(documentId, DEFAULT_PAGE_POINTS);
        double pixels = Math.max(200, displayWidth()) * outputScale();
        double raw = pixels / points;
        return Math.max(MIN_SCALE, Math.min(MAX_SCALE, Math.ceil(raw * 4) / 4));
    }

    private boolean needsSharperRender(double tooLargeFactor) {
        if (image == null || renderedWidthPx <= 0) {
            return false;
        }
        double needed = displayWidth() * outputScale();
        return needed > renderedWidthPx * 1.12 || (needed * tooLargeFactor < renderedWidthPx && needed > 0);
    }

    private void scheduleSharperRender() {
        if (needsSharperRender(2.5)) {
            rerender.playFromStart();
        }
    }

    private void cancelLoads() {
        loads.dispose();
        loads = new Disposer();
        loading = false;
    }

    // ---- keyboard -------------------------------------------------------------------------------

    private void onKey(KeyEvent event) {
        if (event.getTarget() instanceof javafx.scene.control.TextInputControl) {
            return;
        }
        boolean handled = true;
        if (event.getCode() == KeyCode.ESCAPE) {
            host.close();
        } else if (citation == null) {
            handled = false;
        } else if (event.getCode() == KeyCode.PAGE_UP || (zoomIndex == 0 && event.getCode() == KeyCode.LEFT)) {
            turn(-1);
        } else if (event.getCode() == KeyCode.PAGE_DOWN || (zoomIndex == 0 && event.getCode() == KeyCode.RIGHT)) {
            turn(1);
        } else if (ZOOM_IN.match(event) || ZOOM_IN_PLUS.match(event) || ZOOM_IN_ADD.match(event)) {
            zoom(1);
        } else if (ZOOM_OUT.match(event) || ZOOM_OUT_SUBTRACT.match(event)) {
            zoom(-1);
        } else if (ZOOM_RESET.match(event)) {
            setZoom(0);
        } else {
            handled = false;
        }
        if (handled) {
            event.consume();
        }
    }

    /** Puts the keyboard focus on the viewer's first control. */
    void focus() {
        if (citation != null && !previous.isDisabled()) {
            previous.requestFocus();
        } else if (citation != null && !next.isDisabled()) {
            next.requestFocus();
        } else {
            close.requestFocus();
        }
    }
}
