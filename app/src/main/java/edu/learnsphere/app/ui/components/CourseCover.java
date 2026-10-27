package edu.learnsphere.app.ui.components;

import java.util.Objects;
import javafx.scene.layout.StackPane;

/**
 * A course's generated cover art (docs/DESIGN.md, "Course covers"): a soft two-stop gradient field in the
 * course's hue with a sphere of orbit lines and one small moon. The course id seeds small variations (orbit
 * tilt, moon and sphere position) so two courses of the same hue still look different. Below 56 px the
 * sphere is drawn in a simplified form that stays crisp.
 */
public final class CourseCover extends StackPane {

    /** Standard cover sizes. */
    public enum Size {
        /** 22 px square, sidebar and dense lists. */
        TINY(22, 22, 6),
        /** 40 px square, list rows and pickers. */
        SMALL(40, 40, 9),
        /** 64 px square, list headers. */
        MEDIUM(64, 64, 12),
        /** Fills a course card's width, 136 px tall. */
        CARD(-1, 136, 10),
        /** Fills a page's width, 200 px tall, for the course header. */
        BANNER(-1, 200, 14);

        private final double width;
        private final double height;
        private final double radius;

        Size(double width, double height, double radius) {
            this.width = width;
            this.height = height;
            this.radius = radius;
        }

        /** Fixed width in pixels, or -1 to fill the available width. */
        public double width() {
            return width;
        }

        public double height() {
            return height;
        }

        public double radius() {
            return radius;
        }
    }

    private final SphereArt art;
    private final Size size;

    /**
     * @param hue  the course's cover hue (0–359)
     * @param seed a stable value per course (its id) that varies the composition
     */
    public CourseCover(int hue, String seed, Size size) {
        this.size = Objects.requireNonNull(size, "size");
        art = SphereArt.cover(hue);
        art.setSeed(seed == null ? 0 : seed.hashCode());
        art.setCornerRadius(size.radius());
        getStyleClass().addAll("course-cover", "cover-" + size.name().toLowerCase(java.util.Locale.ROOT));
        getChildren().add(art);
        setMouseTransparent(true);
        if (size.width() > 0) {
            setMinSize(size.width(), size.height());
            setPrefSize(size.width(), size.height());
            setMaxSize(size.width(), size.height());
        } else {
            setMinSize(0, size.height());
            setPrefSize(320, size.height());
            setMaxSize(Double.MAX_VALUE, size.height());
            art.setMaxSize(Double.MAX_VALUE, Double.MAX_VALUE);
        }
    }

    public Size size() {
        return size;
    }

    /** The cover's hue. */
    public int hue() {
        return art.hueProperty().get();
    }

    /** Changes the hue (the Studio's cover picker). */
    public void setHue(int hue) {
        art.hueProperty().set(Math.floorMod(hue, 360));
    }

    /** The art itself. */
    public SphereArt art() {
        return art;
    }
}
