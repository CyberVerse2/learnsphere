package edu.learnsphere.app.ui.components.charts;

import edu.learnsphere.app.theme.Motion;
import edu.learnsphere.app.theme.Typography;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.IntConsumer;
import javafx.animation.Animation;
import javafx.beans.property.DoubleProperty;
import javafx.beans.property.SimpleDoubleProperty;
import javafx.beans.value.ObservableValue;
import javafx.css.CssMetaData;
import javafx.css.Styleable;
import javafx.css.StyleableProperty;
import javafx.css.StyleablePropertyFactory;
import javafx.geometry.Insets;
import javafx.geometry.Point2D;
import javafx.scene.AccessibleRole;
import javafx.scene.Cursor;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.control.Label;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;
import javafx.scene.text.Text;
import javafx.util.Duration;

/**
 * Base of the charts drawn on a {@link Canvas}.
 *
 * <p><b>Colours</b> come from the stylesheet through CSS properties ({@code -chart-accent},
 * {@code -chart-accent-soft}, {@code -chart-track}, {@code -chart-text}, {@code -chart-text-muted},
 * {@code -chart-text-faint}, {@code -chart-grid}, {@code -chart-warning}, {@code -chart-warning-text},
 * {@code -chart-surface}, {@code -chart-hover}) that map to the theme tokens, so charts follow light and dark
 * like everything else and redraw when the appearance changes. Labels use the app's fonts; axis and value
 * labels are set in the caption role (Inter Medium 12).
 *
 * <p><b>Sharpness.</b> JavaFX renders a canvas at the screen's pixel scale, so charts stay crisp on HiDPI
 * displays; hairlines are drawn on half-pixel positions ({@link #crisp(double)}) so they cover whole pixels.
 *
 * <p><b>Entrance.</b> The first time a chart receives data it grows in over {@link Motion#PROGRESS} (600 ms),
 * one element after another. With reduced motion the chart fades in instead. {@link #setAnimated(boolean)}
 * turns this off.
 *
 * <p><b>Inspecting values.</b> Hovering an element (a bar, a column, a point, a day) highlights it and shows
 * a tooltip with its exact value. The chart takes keyboard focus when it has elements: the arrow keys, Home
 * and End move between them, Escape hides the tooltip, and Enter or Space activates the element when the
 * chart has an {@link #setOnItemActivated(IntConsumer) activation handler} (a click does the same).
 *
 * <p><b>Screen readers</b> read {@link #setTitle(String) the title} and a description of every element as
 * the chart's accessible text; the element under keyboard focus is read as its help text.
 *
 * <p>Subclasses draw in {@link #draw(GraphicsContext, double, double)} using {@link #reveal()} and
 * {@link #active()}, and call {@link #dataChanged()} when their data changes. FX thread only.
 */
public abstract class CanvasChart extends Region {

    /**
     * What a tooltip says about one element.
     *
     * @param title what the element is, e.g. "Wed 28 Oct"
     * @param value its exact value in words, e.g. "12 reviews due"
     */
    public record Tip(String title, String value) {
        public Tip {
            Objects.requireNonNull(title, "title");
            Objects.requireNonNull(value, "value");
        }

        /** As one line, for screen readers. */
        public String text() {
            return title + ", " + value;
        }
    }

    private static final String STYLESHEET = Objects.requireNonNull(
            CanvasChart.class.getResource("charts.css"), "charts.css").toExternalForm();
    /** Space between an element and its tooltip. */
    private static final double TIP_GAP = 8;
    /** How far a tooltip may rise above the chart before it flips below its element. */
    private static final double TIP_OVERHANG = 28;
    /** Tooltips wrap beyond this width. */
    static final double MAX_TIP_WIDTH = 280;
    /** Text widths remembered by {@link #textWidth(String, Font)}. */
    static final int WIDTH_CACHE_SIZE = 1024;

    private record WidthKey(String text, Font font) {
    }

    /** The most recently measured widths, least recently used dropped first. */
    private static final Map<WidthKey, Double> WIDTHS = Collections.synchronizedMap(
            new LinkedHashMap<>(256, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<WidthKey, Double> eldest) {
                    return size() > WIDTH_CACHE_SIZE;
                }
            });

    private static final StyleablePropertyFactory<CanvasChart> FACTORY =
            new StyleablePropertyFactory<>(Region.getClassCssMetaData());

    private final StyleableProperty<Color> accent = FACTORY.createStyleableColorProperty(
            this, "accent", "-chart-accent", c -> c.accent, Color.web("#2F55D4"));
    private final StyleableProperty<Color> accentSoft = FACTORY.createStyleableColorProperty(
            this, "accentSoft", "-chart-accent-soft", c -> c.accentSoft, Color.web("#C9D3F5"));
    private final StyleableProperty<Color> track = FACTORY.createStyleableColorProperty(
            this, "track", "-chart-track", c -> c.track, Color.web("#EFEBE4"));
    private final StyleableProperty<Color> text = FACTORY.createStyleableColorProperty(
            this, "text", "-chart-text", c -> c.text, Color.web("#1E1C19"));
    private final StyleableProperty<Color> textMuted = FACTORY.createStyleableColorProperty(
            this, "textMuted", "-chart-text-muted", c -> c.textMuted, Color.web("#6E685E"));
    private final StyleableProperty<Color> textFaint = FACTORY.createStyleableColorProperty(
            this, "textFaint", "-chart-text-faint", c -> c.textFaint, Color.web("#9C958A"));
    private final StyleableProperty<Color> grid = FACTORY.createStyleableColorProperty(
            this, "grid", "-chart-grid", c -> c.grid, Color.web("#E4DED4"));
    private final StyleableProperty<Color> warning = FACTORY.createStyleableColorProperty(
            this, "warning", "-chart-warning", c -> c.warning, Color.web("#B26B12"));
    private final StyleableProperty<Color> warningText = FACTORY.createStyleableColorProperty(
            this, "warningText", "-chart-warning-text", c -> c.warningText, Color.web("#8F5208"));
    private final StyleableProperty<Color> surface = FACTORY.createStyleableColorProperty(
            this, "surface", "-chart-surface", c -> c.surface, Color.WHITE);
    private final StyleableProperty<Color> hover = FACTORY.createStyleableColorProperty(
            this, "hover", "-chart-hover", c -> c.hover, Color.rgb(30, 28, 25, 0.05));

    private final Canvas canvas = new Canvas();
    private final VBox tooltip = new VBox();
    private final Label tooltipTitle = new Label();
    private final Label tooltipValue = new Label();
    private final DoubleProperty reveal = new SimpleDoubleProperty(this, "reveal", 1);
    private boolean animated = true;
    private boolean introduced;
    private Animation intro;
    private int active = -1;
    private boolean keyboardActive;
    private IntConsumer onItemActivated;
    private String title;

    protected CanvasChart() {
        getStyleClass().add("chart");
        getStylesheets().add(STYLESHEET);
        tooltip.getStyleClass().add("chart-tooltip");
        tooltipTitle.getStyleClass().addAll("chart-tooltip-title", "medium");
        tooltipValue.getStyleClass().add("chart-tooltip-value");
        tooltipTitle.setWrapText(true);
        tooltipValue.setWrapText(true);
        tooltip.setMaxWidth(MAX_TIP_WIDTH);
        tooltip.getChildren().addAll(tooltipTitle, tooltipValue);
        tooltip.setManaged(false);
        tooltip.setVisible(false);
        tooltip.setMouseTransparent(true);
        getChildren().addAll(canvas, tooltip);
        setAccessibleRole(AccessibleRole.TEXT);
        setFocusTraversable(false);
        for (StyleableProperty<Color> property : List.of(accent, accentSoft, track, text, textMuted, textFaint, grid,
                warning, warningText, surface, hover)) {
            ((ObservableValue<?>) property).addListener((obs, old, value) -> redraw());
        }
        reveal.addListener((obs, old, value) -> redraw());
        addEventHandler(MouseEvent.MOUSE_MOVED, event -> pointAt(event.getX(), event.getY()));
        addEventHandler(MouseEvent.MOUSE_EXITED, event -> {
            if (!keyboardActive) {
                setActive(-1, false);
            }
        });
        addEventHandler(MouseEvent.MOUSE_CLICKED, event -> {
            int index = itemAt(event.getX() - snappedLeftInset(), event.getY() - snappedTopInset());
            if (index >= 0 && onItemActivated != null) {
                onItemActivated.accept(index);
                event.consume();
            }
        });
        addEventHandler(KeyEvent.KEY_PRESSED, this::keyPressed);
        focusedProperty().addListener((obs, was, focused) -> {
            if (!focused) {
                setActive(-1, false);
            }
        });
    }

    public static List<CssMetaData<? extends Styleable, ?>> getClassCssMetaData() {
        return FACTORY.getCssMetaData();
    }

    @Override
    public List<CssMetaData<? extends Styleable, ?>> getCssMetaData() {
        return FACTORY.getCssMetaData();
    }

    // ---- colours and type ---------------------------------------------------------------------

    protected Color accent() {
        return accent.getValue();
    }

    protected Color accentSoft() {
        return accentSoft.getValue();
    }

    protected Color track() {
        return track.getValue();
    }

    protected Color text() {
        return text.getValue();
    }

    protected Color textMuted() {
        return textMuted.getValue();
    }

    protected Color textFaint() {
        return textFaint.getValue();
    }

    protected Color grid() {
        return grid.getValue();
    }

    protected Color warning() {
        return warning.getValue();
    }

    /** The warning colour for text, darker than {@link #warning()} so small type stays readable. */
    protected Color warningText() {
        return warningText.getValue();
    }

    protected Color surface() {
        return surface.getValue();
    }

    /** The tint under a hovered row or column. */
    protected Color hover() {
        return hover.getValue();
    }

    /** Interface type at {@code size} (Inter Regular). */
    protected static Font sans(double size) {
        return Typography.font(Typography.Face.SANS_REGULAR, size);
    }

    /** Interface type at {@code size} (Inter Medium). */
    protected static Font sansMedium(double size) {
        return Typography.font(Typography.Face.SANS_MEDIUM, size);
    }

    /** Interface type at {@code size} (Inter SemiBold). */
    protected static Font sansSemibold(double size) {
        return Typography.font(Typography.Face.SANS_SEMIBOLD, size);
    }

    /** The caption role: axis labels, value labels, dates (Inter Medium 12). */
    protected static Font caption() {
        return sansMedium(12);
    }

    /**
     * The width of {@code value} in {@code font}. Widths are remembered (the most recent
     * {@value #WIDTH_CACHE_SIZE}), as charts measure the same labels again on every frame of their entrance.
     */
    protected static double textWidth(String value, Font font) {
        WidthKey key = new WidthKey(value, font);
        Double known = WIDTHS.get(key);
        if (known != null) {
            return known;
        }
        Text t = new Text(value);
        t.setFont(font);
        double width = t.getLayoutBounds().getWidth();
        WIDTHS.put(key, width);
        return width;
    }

    /**
     * {@code value} shortened with an ellipsis to fit {@code width} (unchanged when it fits): the longest start
     * of it that fits, found by halving the range, so a long label costs a handful of measurements.
     */
    static String fit(String value, Font font, double width) {
        if (value.isEmpty() || textWidth(value, font) <= width) {
            return value;
        }
        int low = 1;
        int high = value.length() - 1;
        int best = 1;
        while (low <= high) {
            int middle = (low + high) >>> 1;
            if (textWidth(start(value, middle) + "…", font) <= width) {
                best = middle;
                low = middle + 1;
            } else {
                high = middle - 1;
            }
        }
        return start(value, best) + "…";
    }

    /** The first {@code length} characters of {@code value} without trailing space, never half a character. */
    private static String start(String value, int length) {
        int end = length;
        if (end < value.length() && Character.isHighSurrogate(value.charAt(end - 1))) {
            end = end > 1 ? end - 1 : end + 1;
        }
        return value.substring(0, end).strip();
    }

    /** {@code position} moved onto the middle of a pixel, where a 1 px line is sharp. */
    protected static double crisp(double position) {
        return Math.floor(position) + 0.5;
    }

    // ---- entrance -----------------------------------------------------------------------------

    /** How far the entrance has come, 0 to 1 (1 when it is over or there is none). */
    protected final double reveal() {
        return reveal.get();
    }

    /** The entrance share of element {@code index} of {@code count}, which enter one after another. */
    protected static double staggered(double reveal, int index, int count) {
        if (count <= 1) {
            return Math.clamp(reveal, 0, 1);
        }
        double spread = Math.min(0.35, 0.035 * (count - 1));
        double start = spread * index / (count - 1);
        return Math.clamp((reveal - start) / (1 - spread), 0, 1);
    }

    /** Whether the chart grows in the first time it gets data (default true). */
    public void setAnimated(boolean value) {
        animated = value;
        if (!value) {
            stopIntro();
            reveal.set(1);
            canvas.setOpacity(1);
        }
    }

    public boolean isAnimated() {
        return animated;
    }

    /** Plays the entrance again (a fade when motion is reduced). */
    public void replayIntro() {
        stopIntro();
        if (Motion.reduced()) {
            reveal.set(1);
            canvas.setOpacity(0);
            intro = Motion.fadeIn(canvas, Motion.ROUTE);
        } else {
            canvas.setOpacity(1);
            reveal.set(0);
            intro = Motion.animate(reveal, 1, Motion.PROGRESS);
        }
    }

    /** True while the entrance is playing. */
    public boolean isIntroPlaying() {
        return intro != null && intro.getStatus() == Animation.Status.RUNNING;
    }

    private void stopIntro() {
        if (intro != null) {
            intro.stop();
            intro = null;
        }
    }

    // ---- inspecting ---------------------------------------------------------------------------

    /** The element under the pointer or keyboard focus, or -1. */
    protected final int active() {
        return active;
    }

    /** The element whose tooltip shows, or -1. */
    public int activeIndex() {
        return active;
    }

    /** The tooltip on show, or null. */
    public Tip shownTip() {
        return active < 0 ? null : tipOf(active);
    }

    /**
     * Shows the tooltip of element {@code index} (-1 hides it), as hovering or the keyboard does.
     */
    public void inspect(int index) {
        setActive(index, false);
    }

    /** Called with an element's index when it is clicked, or chosen with Enter or Space (null: none). */
    public void setOnItemActivated(IntConsumer handler) {
        onItemActivated = handler;
        updateCursor(active);
    }

    /** What the chart shows, read before the description of its elements (e.g. "Reviews coming up"). */
    public void setTitle(String value) {
        title = value;
        updateAccessibleText();
    }

    public String getTitle() {
        return title;
    }

    private void pointAt(double x, double y) {
        int index = itemAt(x - snappedLeftInset(), y - snappedTopInset());
        if (index != active || keyboardActive) {
            setActive(index, false);
        }
    }

    private void setActive(int index, boolean fromKeyboard) {
        int count = itemCount();
        int next = index >= 0 && index < count ? index : -1;
        keyboardActive = fromKeyboard && next >= 0;
        updateCursor(next);
        if (next == active) {
            return;
        }
        active = next;
        if (next >= 0) {
            Tip tip = tipOf(next);
            tooltipTitle.setText(tip.title());
            tooltipValue.setText(tip.value());
            tooltipValue.setManaged(!tip.value().isEmpty());
            tooltipValue.setVisible(!tip.value().isEmpty());
            tooltip.setVisible(true);
            setAccessibleHelp(tip.text());
        } else {
            tooltip.setVisible(false);
            setAccessibleHelp(null);
        }
        requestLayout();
        redraw();
    }

    private void updateCursor(int index) {
        setCursor(index >= 0 && onItemActivated != null ? Cursor.HAND : Cursor.DEFAULT);
    }

    private void keyPressed(KeyEvent event) {
        int count = itemCount();
        if (count == 0) {
            return;
        }
        int next = switch (event.getCode()) {
            case RIGHT, DOWN, KP_RIGHT, KP_DOWN -> active < 0 ? 0 : Math.min(count - 1, active + 1);
            case LEFT, UP, KP_LEFT, KP_UP -> active < 0 ? count - 1 : Math.max(0, active - 1);
            case HOME -> 0;
            case END -> count - 1;
            case ESCAPE -> active < 0 ? -2 : -1;
            case ENTER, SPACE -> {
                if (active >= 0 && onItemActivated != null) {
                    onItemActivated.accept(active);
                    event.consume();
                }
                yield -2;
            }
            default -> -2;
        };
        if (next == -2) {
            return;
        }
        setActive(next, true);
        event.consume();
    }

    // ---- drawing ------------------------------------------------------------------------------

    /**
     * Call after the data changes: lays the chart out again, redraws it, and plays the entrance the first
     * time there is something to show.
     */
    protected final void dataChanged() {
        int count = itemCount();
        setFocusTraversable(count > 0);
        if (active >= count) {
            setActive(-1, false);
        }
        if (animated && !introduced && count > 0) {
            introduced = true;
            replayIntro();
        }
        updateAccessibleText();
        requestLayout();
        redraw();
    }

    /** Draws everything again. */
    public final void redraw() {
        double w = canvas.getWidth();
        double h = canvas.getHeight();
        GraphicsContext g = canvas.getGraphicsContext2D();
        g.clearRect(0, 0, w, h);
        if (w > 0 && h > 0) {
            g.save();
            draw(g, w, h);
            g.restore();
        }
        updateAccessibleText();
    }

    private void updateAccessibleText() {
        String description = describe();
        setAccessibleText(title == null || title.isBlank() ? description
                : description.isEmpty() ? title : title + ": " + description);
    }

    @Override
    protected void layoutChildren() {
        Insets in = getInsets();
        double w = snapSizeX(getWidth() - in.getLeft() - in.getRight());
        double h = snapSizeY(getHeight() - in.getTop() - in.getBottom());
        canvas.relocate(in.getLeft(), in.getTop());
        if (w != canvas.getWidth() || h != canvas.getHeight()) {
            canvas.setWidth(Math.max(0, w));
            canvas.setHeight(Math.max(0, h));
            redraw();
        }
        if (active >= 0 && tooltip.isVisible()) {
            Point2D anchor = anchorOf(active);
            double ax = in.getLeft() + anchor.getX();
            double ay = in.getTop() + anchor.getY();
            double tw = snapSizeX(Math.min(MAX_TIP_WIDTH, tooltip.prefWidth(-1)));
            double th = snapSizeY(tooltip.prefHeight(tw));
            double x = Math.clamp(ax - tw / 2, 0, Math.max(0, getWidth() - tw));
            double y = ay - TIP_GAP - th;
            if (y < -TIP_OVERHANG) {
                y = ay + TIP_GAP + belowOffset(active);
            }
            tooltip.resizeRelocate(snapPositionX(x), snapPositionY(y), tw, th);
        }
    }

    /** How far below its anchor an element ends, for a tooltip that has to sit underneath it. */
    protected double belowOffset(int index) {
        return 0;
    }

    /** The number of elements that can be inspected. */
    protected abstract int itemCount();

    /** The element at {@code (x, y)} in drawing coordinates, or -1. */
    protected abstract int itemAt(double x, double y);

    /** Where the tooltip of element {@code index} points, in drawing coordinates (its top centre). */
    protected abstract Point2D anchorOf(int index);

    /** The tooltip of element {@code index}. */
    protected abstract Tip tipOf(int index);

    /** Draws the chart into a {@code width} × {@code height} area. */
    protected abstract void draw(GraphicsContext g, double width, double height);

    /** What the chart shows, for screen readers (empty when there is nothing). */
    protected abstract String describe();

    /** The canvas the chart draws on (tests read its pixels). */
    Canvas canvas() {
        return canvas;
    }

    /** The tooltip node (tests). */
    Region tooltipNode() {
        return tooltip;
    }

    /** The duration of the entrance. */
    static Duration introDuration() {
        return Motion.PROGRESS;
    }
}
