package edu.learnsphere.app.ui.components;

import edu.learnsphere.app.theme.Motion;
import javafx.animation.Animation;
import javafx.animation.FadeTransition;
import javafx.animation.Interpolator;
import javafx.geometry.Pos;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.util.Duration;

/**
 * A soft placeholder shape shown where content is loading, so a screen keeps its layout while it fills in.
 * Skeletons pulse gently while they are in a scene; with reduced motion they stay still. Build them with
 * {@link #line(double)}, {@link #block(double, double)}, {@link #circle(double)}, {@link #text(int)} or
 * {@link #card()}.
 */
public final class Skeleton extends Region {

    private final FadeTransition pulse;

    private Skeleton(double width, double height) {
        getStyleClass().add("skeleton");
        setMinSize(Region.USE_PREF_SIZE, height);
        setPrefSize(width, height);
        setMaxSize(width < 0 ? Double.MAX_VALUE : width, height);
        setMouseTransparent(true);
        pulse = new FadeTransition(Duration.millis(900), this);
        pulse.setFromValue(1);
        pulse.setToValue(0.45);
        pulse.setAutoReverse(true);
        pulse.setCycleCount(Animation.INDEFINITE);
        pulse.setInterpolator(Interpolator.EASE_BOTH);
        sceneProperty().addListener((obs, old, scene) -> update());
    }

    /** A line of text {@code width} pixels wide (-1 fills the width). */
    public static Skeleton line(double width) {
        Skeleton line = new Skeleton(width, 12);
        line.getStyleClass().add("line");
        return line;
    }

    /** A rectangle (images, charts). */
    public static Skeleton block(double width, double height) {
        return new Skeleton(width, height);
    }

    /** A circle (avatars, rings). */
    public static Skeleton circle(double diameter) {
        Skeleton circle = new Skeleton(diameter, diameter);
        circle.getStyleClass().add("circle");
        circle.setMinWidth(diameter);
        return circle;
    }

    /** A paragraph of {@code lines} lines, the last one shorter. */
    public static VBox text(int lines) {
        VBox box = new VBox(10);
        for (int i = 0; i < lines; i++) {
            Skeleton line = line(-1);
            if (i == lines - 1 && lines > 1) {
                line.setMaxWidth(220);
            }
            box.getChildren().add(line);
        }
        box.getStyleClass().add("skeleton-text");
        return box;
    }

    /** The shape of a course card while the library loads. */
    public static VBox card() {
        Skeleton cover = block(-1, 136);
        cover.getStyleClass().add("cover");
        Skeleton meta = line(90);
        Skeleton title = block(220, 20);
        Skeleton subtitle = line(160);
        VBox body = new VBox(10, meta, title, subtitle);
        body.getStyleClass().add("course-card-body");
        HBox footer = new HBox(10, circle(40), line(70));
        footer.setAlignment(Pos.CENTER_LEFT);
        footer.getStyleClass().add("course-card-footer");
        VBox card = new VBox(cover, body, footer);
        card.getStyleClass().addAll("card", "flat", "course-card", "skeleton-card");
        VBox.setVgrow(body, Priority.ALWAYS);
        return card;
    }

    /** True while pulsing. */
    public boolean isPulsing() {
        return pulse.getStatus() == Animation.Status.RUNNING;
    }

    private void update() {
        if (getScene() != null && !Motion.reduced()) {
            pulse.play();
        } else {
            pulse.stop();
            setOpacity(getScene() == null ? 1 : 0.8);
        }
    }
}
