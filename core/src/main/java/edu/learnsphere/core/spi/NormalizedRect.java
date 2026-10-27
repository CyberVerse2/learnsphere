package edu.learnsphere.core.spi;

/**
 * A rectangle on a page in normalized coordinates: 0–1 of the page's width and height, origin at the
 * top-left corner. Independent of render scale, so the UI can draw it over any page image.
 */
public record NormalizedRect(double x, double y, double width, double height) {

    public NormalizedRect {
        if (x < 0 || y < 0 || width < 0 || height < 0 || x + width > 1.000001 || y + height > 1.000001) {
            throw new IllegalArgumentException("Rectangle outside the page: " + x + "," + y + " " + width + "x" + height);
        }
    }

    /** The smallest rectangle containing both. */
    public NormalizedRect union(NormalizedRect other) {
        double left = Math.min(x, other.x);
        double top = Math.min(y, other.y);
        double right = Math.max(x + width, other.x + other.width);
        double bottom = Math.max(y + height, other.y + other.height);
        return new NormalizedRect(left, top, right - left, bottom - top);
    }
}
