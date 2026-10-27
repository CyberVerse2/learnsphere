package edu.learnsphere.app.ui.components.charts;

import edu.learnsphere.app.theme.Typography;
import java.util.List;
import javafx.css.CssMetaData;
import javafx.css.Styleable;
import javafx.css.StyleableProperty;
import javafx.css.StyleablePropertyFactory;
import javafx.geometry.Insets;
import javafx.scene.AccessibleRole;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.layout.Region;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;
import javafx.scene.text.Text;

/**
 * Base of the charts drawn on a {@link Canvas}. Colours come from the stylesheet through CSS properties
 * ({@code -chart-accent}, {@code -chart-accent-soft}, {@code -chart-track}, {@code -chart-text},
 * {@code -chart-text-muted}, {@code -chart-grid}, {@code -chart-warning}, {@code -chart-surface}), which
 * {@code learnsphere.css} maps to the theme tokens, so charts follow light and dark like everything else.
 * Labels use the app's fonts. Subclasses draw in {@link #draw(GraphicsContext, double, double)}; the chart
 * redraws when it is resized, its data changes ({@link #redraw()}) or its colours change.
 */
public abstract class CanvasChart extends Region {

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
    private final StyleableProperty<Color> grid = FACTORY.createStyleableColorProperty(
            this, "grid", "-chart-grid", c -> c.grid, Color.web("#E4DED4"));
    private final StyleableProperty<Color> warning = FACTORY.createStyleableColorProperty(
            this, "warning", "-chart-warning", c -> c.warning, Color.web("#B26B12"));
    private final StyleableProperty<Color> surface = FACTORY.createStyleableColorProperty(
            this, "surface", "-chart-surface", c -> c.surface, Color.WHITE);

    private final Canvas canvas = new Canvas();

    protected CanvasChart() {
        getStyleClass().add("chart");
        getChildren().add(canvas);
        setAccessibleRole(AccessibleRole.TEXT);
        for (StyleableProperty<Color> property : List.of(accent, accentSoft, track, text, textMuted, grid, warning, surface)) {
            ((javafx.beans.value.ObservableValue<?>) property).addListener((obs, old, value) -> redraw());
        }
    }

    public static List<CssMetaData<? extends Styleable, ?>> getClassCssMetaData() {
        return FACTORY.getCssMetaData();
    }

    @Override
    public List<CssMetaData<? extends Styleable, ?>> getCssMetaData() {
        return FACTORY.getCssMetaData();
    }

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

    protected Color grid() {
        return grid.getValue();
    }

    protected Color warning() {
        return warning.getValue();
    }

    protected Color surface() {
        return surface.getValue();
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

    /** The width of {@code value} in {@code font}. */
    protected static double textWidth(String value, Font font) {
        Text t = new Text(value);
        t.setFont(font);
        return t.getLayoutBounds().getWidth();
    }

    /** Draws everything again (call after changing the data). */
    public final void redraw() {
        double w = canvas.getWidth();
        double h = canvas.getHeight();
        GraphicsContext g = canvas.getGraphicsContext2D();
        g.clearRect(0, 0, w, h);
        if (w > 0 && h > 0) {
            draw(g, w, h);
        }
        setAccessibleText(describe());
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
    }

    /** Draws the chart into a {@code width} × {@code height} area. */
    protected abstract void draw(GraphicsContext g, double width, double height);

    /** What the chart shows, for screen readers. */
    protected abstract String describe();
}
