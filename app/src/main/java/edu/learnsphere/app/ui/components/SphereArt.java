package edu.learnsphere.app.ui.components;

import edu.learnsphere.app.theme.Theme;
import java.util.ArrayList;
import java.util.List;
import javafx.beans.property.IntegerProperty;
import javafx.beans.property.SimpleIntegerProperty;
import javafx.beans.value.ChangeListener;
import javafx.beans.value.WeakChangeListener;
import javafx.scene.Group;
import javafx.scene.Node;
import javafx.scene.layout.Region;
import javafx.scene.paint.Color;
import javafx.scene.paint.CycleMethod;
import javafx.scene.paint.LinearGradient;
import javafx.scene.paint.Paint;
import javafx.scene.paint.RadialGradient;
import javafx.scene.paint.Stop;
import javafx.scene.shape.Arc;
import javafx.scene.shape.ArcType;
import javafx.scene.shape.Circle;
import javafx.scene.shape.Ellipse;
import javafx.scene.shape.ArcTo;
import javafx.scene.shape.ClosePath;
import javafx.scene.shape.LineTo;
import javafx.scene.shape.MoveTo;
import javafx.scene.shape.Path;
import javafx.scene.shape.Rectangle;
import javafx.scene.shape.Shape;
import javafx.scene.transform.Rotate;

/**
 * Generated art from a hue (0–359): a soft two-stop gradient field with a sphere built from orbit lines and
 * one small moon, drawn with shapes and redrawn when resized or when the theme changes. {@link Style#COVER}
 * is a course cover (rounded rectangle); {@link Style#MARK} is the circular app mark.
 */
public final class SphereArt extends Region {

    /** Hue of the app mark and the accent colour. */
    public static final int ACCENT_HUE = 226;

    public enum Style {
        COVER,
        MARK
    }

    /** Covers smaller than this draw a simplified sphere that stays crisp (sidebar, lists). */
    public static final double DETAIL_MIN_SIZE = 56;

    private static final double TILT = -16;
    private static final double[] TILTS = {-16, -22, -11, -19};
    private static final double[] MOON_ANGLES = {152, 32, 208, 120, 332};
    private static final double[] SPHERE_X = {0.63, 0.58, 0.67};

    private final Style style;
    private final IntegerProperty hue = new SimpleIntegerProperty(this, "hue");
    private final ChangeListener<Boolean> themeListener = (obs, was, dark) -> invalidate();
    private final ChangeListener<Object> placementListener = (obs, old, value) -> invalidate();
    private double cornerRadius = 14;
    private boolean roundTop = true;
    private boolean roundBottom = true;
    private long seed;
    private double drawnWidth = -1;
    private double drawnHeight = -1;

    public SphereArt(int hue, Style style) {
        this.style = style;
        this.hue.set(Math.floorMod(hue, 360));
        this.hue.addListener((obs, old, value) -> invalidate());
        Theme.darkProperty().addListener(new WeakChangeListener<>(themeListener));
        // A cover moved into a part of the window with the other palette redraws for it.
        sceneProperty().addListener(placementListener);
        parentProperty().addListener(placementListener);
        getStyleClass().add("sphere-art");
        setMouseTransparent(true);
    }

    /** A course cover for {@code hue}. */
    public static SphereArt cover(int hue) {
        return new SphereArt(hue, Style.COVER);
    }

    /** The circular app mark at {@code size} pixels. */
    public static SphereArt mark(double size) {
        SphereArt art = new SphereArt(ACCENT_HUE, Style.MARK);
        art.setMinSize(size, size);
        art.setPrefSize(size, size);
        art.setMaxSize(size, size);
        return art;
    }

    public IntegerProperty hueProperty() {
        return hue;
    }

    /**
     * Varies the composition (orbit tilt, where the moon sits, where the sphere sits) so covers of the same
     * hue still differ. Covers use a hash of the course id.
     */
    public void setSeed(long value) {
        seed = value;
        invalidate();
    }

    /** Which corners of a cover are rounded (a cover at the top of a card rounds only the top). */
    public void setRoundedCorners(boolean top, boolean bottom) {
        roundTop = top;
        roundBottom = bottom;
        invalidate();
    }

    /** Corner radius of a cover (ignored for the mark). */
    public void setCornerRadius(double radius) {
        cornerRadius = radius;
        invalidate();
    }

    @Override
    protected double computePrefWidth(double height) {
        return style == Style.MARK ? 32 : 320;
    }

    @Override
    protected double computePrefHeight(double width) {
        return style == Style.MARK ? 32 : 200;
    }

    private void invalidate() {
        drawnWidth = -1;
        requestLayout();
    }

    @Override
    protected void layoutChildren() {
        double w = getWidth();
        double h = getHeight();
        if (w <= 0 || h <= 0 || (w == drawnWidth && h == drawnHeight)) {
            return;
        }
        drawnWidth = w;
        drawnHeight = h;
        boolean dark = Theme.isDark(this);
        List<Node> nodes;
        if (style == Style.MARK) {
            nodes = drawMark(w, h, dark);
        } else if (Math.min(w, h) < DETAIL_MIN_SIZE) {
            nodes = drawSmall(w, h, dark);
        } else {
            nodes = drawCover(w, h, dark);
        }
        getChildren().setAll(nodes);
        Shape clip = style == Style.MARK
                ? new Circle(w / 2, h / 2, Math.min(w, h) / 2)
                : roundedRect(w, h, cornerRadius, roundTop, roundBottom);
        setClip(clip);
    }

    private int pick(int salt, int size) {
        long mixed = (seed ^ (seed >>> 29) ^ (salt * 0x9E3779B97F4A7C15L)) * 0xBF58476D1CE4E5B9L;
        return Math.floorMod(mixed ^ (mixed >>> 31), size);
    }

    private double tilt() {
        return TILTS[pick(1, TILTS.length)];
    }

    private List<Node> drawCover(double w, double h, boolean dark) {
        int base = hue.get();
        double tilt = tilt();
        double s = Math.min(w, h);
        double r = s * 0.31;
        double cx = w * SPHERE_X[pick(3, SPHERE_X.length)];
        double cy = h * 0.55;
        double line = Math.max(0.8, s / 200);

        List<Node> nodes = new ArrayList<>();
        Rectangle field = new Rectangle(w, h);
        field.setFill(new LinearGradient(0, 0, 1, 1, true, CycleMethod.NO_CYCLE,
                new Stop(0, dark ? hsb(base - 6, 0.42, 0.27) : hsb(base - 6, 0.10, 0.99)),
                new Stop(1, dark ? hsb(base + 16, 0.58, 0.15) : hsb(base + 16, 0.30, 0.91))));
        nodes.add(field);

        Circle glow = new Circle(cx, cy, r * 1.9);
        glow.setFill(new RadialGradient(0, 0, 0.5, 0.5, 0.5, true, CycleMethod.NO_CYCLE,
                new Stop(0, Color.gray(1, dark ? 0.10 : 0.55)), new Stop(1, Color.gray(1, 0))));
        nodes.add(glow);

        Color ring = dark ? hsb(base, 0.25, 0.92, 0.42) : hsb(base, 0.55, 0.55, 0.38);
        Color ringFaint = dark ? hsb(base, 0.25, 0.92, 0.16) : hsb(base, 0.55, 0.55, 0.14);
        double outerRx = r * 2.15;
        double outerRy = r * 0.62;
        double ringRx = r * 1.58;
        double ringRy = r * 0.44;
        nodes.add(halfOrbit(cx, cy, outerRx, outerRy, true, ringFaint, line, tilt));
        nodes.add(halfOrbit(cx, cy, ringRx, ringRy, true, ring, line * 1.2, tilt));

        Circle body = new Circle(cx, cy, r);
        body.setFill(new RadialGradient(0, 0, cx - r * 0.38, cy - r * 0.42, r * 1.55, false, CycleMethod.NO_CYCLE,
                new Stop(0, dark ? hsb(base - 4, 0.22, 0.86) : hsb(base - 4, 0.06, 1.0)),
                new Stop(0.55, dark ? hsb(base + 4, 0.48, 0.62) : hsb(base + 4, 0.34, 0.93)),
                new Stop(1, dark ? hsb(base + 10, 0.66, 0.36) : hsb(base + 10, 0.62, 0.74))));
        nodes.add(body);

        Group lines = new Group();
        Color lineColor = Color.gray(1, dark ? 0.30 : 0.55);
        for (double k : new double[] {0.16, 0.48, 0.80}) {
            lines.getChildren().add(orbit(cx, cy, r, r * k, lineColor, line, tilt));
        }
        lines.getChildren().add(orbit(cx, cy, r * 0.42, r, Color.gray(1, dark ? 0.18 : 0.38), line, tilt));
        lines.setClip(new Circle(cx, cy, r - line / 2));
        nodes.add(lines);

        Circle rim = new Circle(cx, cy, r);
        rim.setFill(null);
        rim.setStroke(Color.gray(1, dark ? 0.14 : 0.6));
        rim.setStrokeWidth(line);
        nodes.add(rim);

        nodes.add(halfOrbit(cx, cy, ringRx, ringRy, false, ring.deriveColor(0, 1, 1, 1.9), line * 1.2, tilt));
        nodes.add(halfOrbit(cx, cy, outerRx, outerRy, false, ringFaint.deriveColor(0, 1, 1, 1.6), line, tilt));

        double[] moonAt = pointOnOrbit(cx, cy, ringRx, ringRy, MOON_ANGLES[pick(2, MOON_ANGLES.length)], tilt);
        double mr = r * 0.135;
        Circle moon = new Circle(moonAt[0], moonAt[1], mr);
        moon.setFill(new RadialGradient(0, 0, moonAt[0] - mr * 0.4, moonAt[1] - mr * 0.45, mr * 1.6, false,
                CycleMethod.NO_CYCLE,
                new Stop(0, dark ? hsb(base + 34, 0.12, 0.98) : Color.WHITE),
                new Stop(1, dark ? hsb(base + 34, 0.40, 0.74) : hsb(base + 34, 0.30, 0.90))));
        moon.setStroke(Color.gray(1, dark ? 0.2 : 0.8));
        moon.setStrokeWidth(line * 0.8);
        nodes.add(moon);
        return nodes;
    }

    /**
     * The simplified cover for small sizes: the field, a solid sphere with one orbit and the moon, drawn
     * with fewer, stronger strokes so it reads at 22 px.
     */
    private List<Node> drawSmall(double w, double h, boolean dark) {
        int base = hue.get();
        double s = Math.min(w, h);
        double cx = w / 2;
        double cy = h / 2;
        double r = s * 0.27;
        double line = Math.max(1, s / 24);
        List<Node> nodes = new ArrayList<>();
        Rectangle field = new Rectangle(w, h);
        field.setFill(new LinearGradient(0, 0, 1, 1, true, CycleMethod.NO_CYCLE,
                new Stop(0, dark ? hsb(base - 6, 0.40, 0.34) : hsb(base - 6, 0.14, 0.97)),
                new Stop(1, dark ? hsb(base + 16, 0.55, 0.22) : hsb(base + 16, 0.34, 0.88))));
        nodes.add(field);
        double ringRx = r * 1.75;
        double ringRy = r * 0.5;
        Color ring = dark ? hsb(base, 0.20, 0.95, 0.55) : hsb(base, 0.62, 0.48, 0.55);
        nodes.add(halfOrbit(cx, cy, ringRx, ringRy, true, ring, line, TILT));
        Circle body = new Circle(cx, cy, r);
        body.setFill(new RadialGradient(0, 0, cx - r * 0.4, cy - r * 0.45, r * 1.7, false, CycleMethod.NO_CYCLE,
                new Stop(0, dark ? hsb(base - 4, 0.25, 0.95) : hsb(base - 4, 0.12, 1.0)),
                new Stop(1, dark ? hsb(base + 10, 0.62, 0.62) : hsb(base + 10, 0.66, 0.70))));
        nodes.add(body);
        nodes.add(halfOrbit(cx, cy, ringRx, ringRy, false, ring.deriveColor(0, 1, 1, 1.6), line, TILT));
        double[] moonAt = pointOnOrbit(cx, cy, ringRx, ringRy, 152, TILT);
        Circle moon = new Circle(moonAt[0], moonAt[1], Math.max(1.4, r * 0.28),
                dark ? hsb(base + 34, 0.10, 1.0) : Color.WHITE);
        nodes.add(moon);
        return nodes;
    }

    private List<Node> drawMark(double w, double h, boolean dark) {
        int base = hue.get();
        double s = Math.min(w, h);
        double cx = w / 2;
        double cy = h / 2;
        double r = s * 0.25;
        double line = Math.max(1, s / 22);

        List<Node> nodes = new ArrayList<>();
        Circle field = new Circle(cx, cy, s / 2);
        field.setFill(new LinearGradient(0, 0, 1, 1, true, CycleMethod.NO_CYCLE,
                new Stop(0, dark ? hsb(base - 4, 0.46, 1.0) : hsb(base - 4, 0.72, 0.90)),
                new Stop(1, dark ? hsb(base + 14, 0.62, 0.80) : hsb(base + 14, 0.86, 0.62))));
        nodes.add(field);

        Color ring = Color.gray(1, 0.55);
        double ringRx = r * 1.72;
        double ringRy = r * 0.52;
        nodes.add(halfOrbit(cx, cy, ringRx, ringRy, true, ring, line, TILT));
        Circle body = new Circle(cx, cy, r);
        body.setFill(new RadialGradient(0, 0, cx - r * 0.4, cy - r * 0.45, r * 1.6, false, CycleMethod.NO_CYCLE,
                new Stop(0, Color.WHITE), new Stop(1, hsb(base, 0.18, 0.96))));
        nodes.add(body);
        nodes.add(halfOrbit(cx, cy, ringRx, ringRy, false, Color.WHITE, line, TILT));
        double[] moonAt = pointOnOrbit(cx, cy, ringRx, ringRy, 152, TILT);
        nodes.add(new Circle(moonAt[0], moonAt[1], Math.max(1.4, r * 0.24), Color.WHITE));
        return nodes;
    }

    /** Back (upper) or front (lower) half of a tilted orbit. */
    private static Arc halfOrbit(double cx, double cy, double rx, double ry, boolean back, Paint stroke, double width,
                                 double tilt) {
        Arc arc = new Arc(cx, cy, rx, ry, back ? 0 : 180, 180);
        arc.setType(ArcType.OPEN);
        arc.setFill(null);
        arc.setStroke(stroke);
        arc.setStrokeWidth(width);
        arc.getTransforms().add(new Rotate(tilt, cx, cy));
        return arc;
    }

    private static Ellipse orbit(double cx, double cy, double rx, double ry, Paint stroke, double width, double tilt) {
        Ellipse ellipse = new Ellipse(cx, cy, rx, ry);
        ellipse.setFill(null);
        ellipse.setStroke(stroke);
        ellipse.setStrokeWidth(width);
        ellipse.getTransforms().add(new Rotate(tilt, cx, cy));
        return ellipse;
    }

    /** A point on a tilted orbit; {@code degrees} runs counter-clockwise from 3 o'clock, front half below. */
    private static double[] pointOnOrbit(double cx, double cy, double rx, double ry, double degrees, double tiltDegrees) {
        double t = Math.toRadians(degrees);
        double x = rx * Math.cos(t);
        double y = ry * Math.sin(t);
        double tilt = Math.toRadians(tiltDegrees);
        return new double[] {cx + x * Math.cos(tilt) - y * Math.sin(tilt), cy + x * Math.sin(tilt) + y * Math.cos(tilt)};
    }

    private static Shape roundedRect(double w, double h, double radius, boolean top, boolean bottom) {
        if (top == bottom) {
            Rectangle rect = new Rectangle(w, h);
            double arc = top ? radius * 2 : 0;
            rect.setArcWidth(arc);
            rect.setArcHeight(arc);
            return rect;
        }
        double rt = top ? radius : 0;
        double rb = bottom ? radius : 0;
        Path path = new Path(
                new MoveTo(rt, 0), new LineTo(w - rt, 0), new ArcTo(rt, rt, 0, w, rt, false, true),
                new LineTo(w, h - rb), new ArcTo(rb, rb, 0, w - rb, h, false, true),
                new LineTo(rb, h), new ArcTo(rb, rb, 0, 0, h - rb, false, true),
                new LineTo(0, rt), new ArcTo(rt, rt, 0, rt, 0, false, true), new ClosePath());
        path.setFill(Color.BLACK);
        path.setStroke(null);
        return path;
    }

    private static Color hsb(double hue, double saturation, double brightness) {
        return Color.hsb(Math.floorMod((int) Math.round(hue), 360), clamp(saturation), clamp(brightness));
    }

    private static Color hsb(double hue, double saturation, double brightness, double opacity) {
        return Color.hsb(Math.floorMod((int) Math.round(hue), 360), clamp(saturation), clamp(brightness), clamp(opacity));
    }

    private static double clamp(double v) {
        return Math.max(0, Math.min(1, v));
    }
}
