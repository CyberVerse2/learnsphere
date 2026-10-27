package edu.learnsphere.app;

import java.util.List;
import javafx.geometry.Rectangle2D;

/**
 * The main window's size and position, remembered between runs.
 *
 * @param x         left edge in screen coordinates
 * @param y         top edge in screen coordinates
 * @param width     window width
 * @param height    window height
 * @param maximized whether the window was maximised (the bounds are then the size to restore to)
 */
public record WindowState(double x, double y, double width, double height, boolean maximized) {

    /** How much of the window's top strip must be on a screen for the saved position to be used. */
    static final double MIN_VISIBLE_WIDTH = 120;
    static final double MIN_VISIBLE_HEIGHT = 40;

    /**
     * The default window, {@code width} × {@code height} centred on {@code screen} (shrunk to fit it).
     */
    public static WindowState centred(Rectangle2D screen, double width, double height) {
        double w = Math.min(width, screen.getWidth());
        double h = Math.min(height, screen.getHeight());
        return new WindowState(screen.getMinX() + (screen.getWidth() - w) / 2,
                screen.getMinY() + (screen.getHeight() - h) / 2, w, h, false);
    }

    /**
     * This state adjusted to the screens that exist now: when too little of the window's top edge would be
     * on any screen (a monitor was unplugged, say) the window is centred on the primary screen at the
     * default size; otherwise it keeps its place, shrunk to fit the screen it is on, never below the minimum
     * size.
     *
     * @param screens     the visual bounds of every screen; the first is the primary one
     * @param minWidth    the window's minimum width
     * @param minHeight   the window's minimum height
     * @param defaultSize the default width and height, as a rectangle at 0,0
     */
    public WindowState fitTo(List<Rectangle2D> screens, double minWidth, double minHeight, Rectangle2D defaultSize) {
        if (screens.isEmpty()) {
            return this;
        }
        Rectangle2D primary = screens.getFirst();
        if (!(width > 0) || !(height > 0) || !Double.isFinite(x) || !Double.isFinite(y)) {
            return centred(primary, defaultSize.getWidth(), defaultSize.getHeight());
        }
        Rectangle2D titleStrip = new Rectangle2D(x, y, width, Math.min(height, MIN_VISIBLE_HEIGHT));
        Rectangle2D home = null;
        for (Rectangle2D screen : screens) {
            if (overlap(titleStrip, screen) >= MIN_VISIBLE_WIDTH * Math.min(height, MIN_VISIBLE_HEIGHT)) {
                home = screen;
                break;
            }
        }
        if (home == null) {
            return centred(primary, defaultSize.getWidth(), defaultSize.getHeight()).withMaximized(maximized);
        }
        double w = Math.max(Math.min(width, home.getWidth()), Math.min(minWidth, home.getWidth()));
        double h = Math.max(Math.min(height, home.getHeight()), Math.min(minHeight, home.getHeight()));
        double nx = Math.min(Math.max(x, home.getMinX()), home.getMaxX() - w);
        double ny = Math.min(Math.max(y, home.getMinY()), home.getMaxY() - h);
        return new WindowState(nx, ny, w, h, maximized);
    }

    WindowState withMaximized(boolean value) {
        return new WindowState(x, y, width, height, value);
    }

    private static double overlap(Rectangle2D a, Rectangle2D b) {
        double w = Math.min(a.getMaxX(), b.getMaxX()) - Math.max(a.getMinX(), b.getMinX());
        double h = Math.min(a.getMaxY(), b.getMaxY()) - Math.max(a.getMinY(), b.getMinY());
        return w > 0 && h > 0 ? w * h : 0;
    }
}
