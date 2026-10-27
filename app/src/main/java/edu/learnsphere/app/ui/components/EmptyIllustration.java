package edu.learnsphere.app.ui.components;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import javafx.scene.Group;
import javafx.scene.Node;
import javafx.scene.layout.Region;
import javafx.scene.shape.Circle;
import javafx.scene.shape.Ellipse;
import javafx.scene.shape.Line;
import javafx.scene.shape.Polyline;
import javafx.scene.shape.Rectangle;
import javafx.scene.shape.Shape;
import javafx.scene.shape.StrokeLineCap;
import javafx.scene.shape.SVGPath;
import javafx.scene.transform.Rotate;

/**
 * The small drawings of empty states, built from shapes in the orbit language of the course covers: a
 * sphere, an orbit, and one object that says what belongs here (books, a page, a question, a check).
 * Every shape takes its colour from the stylesheet ({@code illo-*} classes), so the drawing follows the
 * palette.
 */
public final class EmptyIllustration extends Region {

    /** What the empty place is for. */
    public enum Kind {
        /** No courses yet. */
        LIBRARY,
        /** Nothing due: all caught up. */
        CAUGHT_UP,
        /** No questions asked yet. */
        QUESTIONS,
        /** No PDFs in a course. */
        DOCUMENTS,
        /** A search or filter found nothing. */
        SEARCH,
        /** No study history yet. */
        PROGRESS
    }

    public static final double WIDTH = 200;
    public static final double HEIGHT = 140;

    private final Kind kind;

    public EmptyIllustration(Kind kind) {
        this.kind = Objects.requireNonNull(kind, "kind");
        getStyleClass().addAll("empty-illustration", "illo-" + kind.name().toLowerCase(java.util.Locale.ROOT));
        setMinSize(WIDTH, HEIGHT);
        setPrefSize(WIDTH, HEIGHT);
        setMaxSize(WIDTH, HEIGHT);
        setMouseTransparent(true);
        getChildren().setAll(draw());
    }

    public Kind kind() {
        return kind;
    }

    private List<Node> draw() {
        List<Node> nodes = new ArrayList<>();
        nodes.add(styled(new Ellipse(100, 120, 78, 10), "illo-shadow"));
        nodes.add(styled(new Circle(100, 70, 62), "illo-glow"));
        switch (kind) {
            case LIBRARY -> library(nodes);
            case CAUGHT_UP -> caughtUp(nodes);
            case QUESTIONS -> questions(nodes);
            case DOCUMENTS -> documents(nodes);
            case SEARCH -> search(nodes);
            case PROGRESS -> progress(nodes);
        }
        return nodes;
    }

    private static void sphere(List<Node> nodes, double cx, double cy, double r, boolean withOrbit, double moonAngle) {
        double rx = r * 1.8;
        double ry = r * 0.5;
        if (withOrbit) {
            nodes.add(halfOrbit(cx, cy, rx, ry, true));
        }
        nodes.add(styled(new Circle(cx, cy, r), "illo-sphere"));
        Group lines = new Group();
        for (double k : new double[] {0.3, 0.72}) {
            Ellipse e = new Ellipse(cx, cy, r, r * k);
            e.getTransforms().add(new Rotate(-16, cx, cy));
            lines.getChildren().add(styled(e, "illo-sphere-line"));
        }
        lines.setClip(new Circle(cx, cy, r - 0.5));
        nodes.add(lines);
        if (withOrbit) {
            nodes.add(halfOrbit(cx, cy, rx, ry, false));
            double t = Math.toRadians(moonAngle);
            double x = rx * Math.cos(t);
            double y = ry * Math.sin(t);
            double tilt = Math.toRadians(-16);
            nodes.add(styled(new Circle(cx + x * Math.cos(tilt) - y * Math.sin(tilt),
                    cy + x * Math.sin(tilt) + y * Math.cos(tilt), Math.max(3, r * 0.16)), "illo-moon"));
        }
    }

    private static Shape halfOrbit(double cx, double cy, double rx, double ry, boolean back) {
        javafx.scene.shape.Arc arc = new javafx.scene.shape.Arc(cx, cy, rx, ry, back ? 0 : 180, 180);
        arc.setType(javafx.scene.shape.ArcType.OPEN);
        arc.getTransforms().add(new Rotate(-16, cx, cy));
        return styled(arc, back ? "illo-orbit-back" : "illo-orbit");
    }

    private static void library(List<Node> nodes) {
        sphere(nodes, 72, 72, 34, true, 150);
        double[][] books = {{118, 46, 26, 66, 0}, {140, 52, 24, 60, 4}, {160, 60, 22, 54, 10}};
        String[] spines = {"illo-tint-accent", "illo-tint-warning", "illo-tint-success"};
        for (int i = 0; i < books.length; i++) {
            double[] b = books[i];
            Rectangle book = new Rectangle(b[0], b[1], b[2], b[3]);
            book.setArcWidth(6);
            book.setArcHeight(6);
            book.getTransforms().add(new Rotate(b[4], b[0] + b[2] / 2, b[1] + b[3]));
            nodes.add(styled(book, "illo-paper"));
            Rectangle spine = new Rectangle(b[0] + 5, b[1] + 8, b[2] - 10, 8);
            spine.setArcWidth(3);
            spine.setArcHeight(3);
            spine.getTransforms().add(new Rotate(b[4], b[0] + b[2] / 2, b[1] + b[3]));
            nodes.add(styled(spine, spines[i]));
        }
    }

    private static void caughtUp(List<Node> nodes) {
        sphere(nodes, 100, 70, 40, true, 330);
        nodes.add(styled(new Circle(146, 34, 15), "illo-badge"));
        Polyline check = new Polyline(139, 34.5, 144, 39.5, 153.5, 29);
        check.setStrokeLineCap(StrokeLineCap.ROUND);
        nodes.add(styled(check, "illo-check"));
        nodes.add(styled(new Circle(44, 38, 2.5), "illo-spark"));
        nodes.add(styled(new Circle(160, 96, 2), "illo-spark"));
        nodes.add(styled(new Circle(34, 92, 1.8), "illo-spark"));
    }

    private static void questions(List<Node> nodes) {
        sphere(nodes, 70, 78, 32, true, 200);
        SVGPath bubble = new SVGPath();
        bubble.setContent("M112 22 h56 a10 10 0 0 1 10 10 v30 a10 10 0 0 1 -10 10 h-34 l-12 12 v-12 h-10 "
                + "a10 10 0 0 1 -10 -10 v-30 a10 10 0 0 1 10 -10 z");
        nodes.add(styled(bubble, "illo-paper"));
        nodes.add(styled(new Line(114, 38, 164, 38), "illo-ink"));
        nodes.add(styled(new Line(114, 48, 152, 48), "illo-ink"));
        nodes.add(styled(new Line(114, 58, 140, 58), "illo-ink-accent"));
    }

    private static void documents(List<Node> nodes) {
        Rectangle back = new Rectangle(70, 26, 64, 84);
        back.setArcWidth(8);
        back.setArcHeight(8);
        back.getTransforms().add(new Rotate(-7, 102, 68));
        nodes.add(styled(back, "illo-paper"));
        SVGPath page = new SVGPath();
        page.setContent("M78 20 h44 l18 18 v70 a6 6 0 0 1 -6 6 h-56 a6 6 0 0 1 -6 -6 v-82 a6 6 0 0 1 6 -6 z");
        nodes.add(styled(page, "illo-paper"));
        SVGPath fold = new SVGPath();
        fold.setContent("M122 20 v14 a4 4 0 0 0 4 4 h14");
        nodes.add(styled(fold, "illo-fold"));
        for (int i = 0; i < 5; i++) {
            double y = 50 + i * 10;
            nodes.add(styled(new Line(82, y, i == 4 ? 112 : 128, y), i == 1 ? "illo-ink-accent" : "illo-ink"));
        }
        sphere(nodes, 152, 96, 14, false, 0);
    }

    private static void search(List<Node> nodes) {
        sphere(nodes, 88, 74, 30, true, 30);
        nodes.add(styled(new Circle(132, 52, 20), "illo-lens"));
        Line handle = new Line(146, 66, 162, 82);
        handle.setStrokeLineCap(StrokeLineCap.ROUND);
        nodes.add(styled(handle, "illo-handle"));
    }

    private static void progress(List<Node> nodes) {
        double[][] bars = {{108, 76, 14, 34}, {128, 60, 14, 50}, {148, 44, 14, 66}};
        for (double[] b : bars) {
            Rectangle bar = new Rectangle(b[0], b[1], b[2], b[3]);
            bar.setArcWidth(6);
            bar.setArcHeight(6);
            nodes.add(styled(bar, b == bars[2] ? "illo-bar-accent" : "illo-bar"));
        }
        sphere(nodes, 66, 72, 30, true, 160);
    }

    private static <S extends Shape> S styled(S shape, String styleClass) {
        shape.getStyleClass().add(styleClass);
        return shape;
    }
}
