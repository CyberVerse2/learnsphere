package edu.learnsphere.pdf;

import edu.learnsphere.core.spi.NormalizedRect;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.common.PDRectangle;

/**
 * The visible area of a page (its crop box) and how it is turned for display. "Display" coordinates are
 * points with the origin at the top-left corner of the page as rendered, after the page's rotation; they are
 * what {@link NormalizedRect}s and rendered images use.
 *
 * @param cropWidth  crop box width before rotation
 * @param cropHeight crop box height before rotation
 * @param rotation   clockwise display rotation: 0, 90, 180 or 270
 */
record PageGeometry(float cropWidth, float cropHeight, int rotation) {

    static PageGeometry of(PDPage page) {
        PDRectangle crop = page.getCropBox();
        int rotation = ((page.getRotation() % 360) + 360) % 360;
        rotation = rotation - rotation % 90;
        return new PageGeometry(Math.abs(crop.getWidth()), Math.abs(crop.getHeight()), rotation);
    }

    /** Width of the page as displayed. */
    float displayWidth() {
        return quarterTurned() ? cropHeight : cropWidth;
    }

    /** Height of the page as displayed. */
    float displayHeight() {
        return quarterTurned() ? cropWidth : cropHeight;
    }

    private boolean quarterTurned() {
        return rotation == 90 || rotation == 270;
    }

    /** Display x of a point given in user space relative to the crop box's lower-left corner. */
    float displayX(float ux, float uy) {
        return switch (rotation) {
            case 90 -> uy;
            case 180 -> cropWidth - ux;
            case 270 -> cropHeight - uy;
            default -> ux;
        };
    }

    /** Display y (downwards from the top) of a point given in user space relative to the crop box's lower-left corner. */
    float displayY(float ux, float uy) {
        return switch (rotation) {
            case 90 -> ux;
            case 180 -> uy;
            case 270 -> cropWidth - ux;
            default -> cropHeight - uy;
        };
    }

    /**
     * Width of the layout frame for text running in {@code direction} (0, 90, 180 or 270 degrees): PDFBox's
     * direction-adjusted coordinates turn such text upright in a frame of this size.
     */
    float frameWidth(int direction) {
        return direction == 90 || direction == 270 ? cropHeight : cropWidth;
    }

    /** Height of the layout frame for text running in {@code direction}. */
    float frameHeight(int direction) {
        return direction == 90 || direction == 270 ? cropWidth : cropHeight;
    }

    /** A rectangle in display points as a normalized rectangle, clipped to the page; null when it lies outside. */
    NormalizedRect normalize(float left, float top, float right, float bottom) {
        float width = displayWidth();
        float height = displayHeight();
        if (width <= 0 || height <= 0) {
            return null;
        }
        double x0 = clamp(left / width);
        double x1 = clamp(right / width);
        double y0 = clamp(top / height);
        double y1 = clamp(bottom / height);
        if (x1 < x0 || y1 < y0 || (x1 == x0 && right > left) || (y1 == y0 && bottom > top)) {
            return null;
        }
        return new NormalizedRect(x0, y0, Math.min(x1 - x0, 1 - x0), Math.min(y1 - y0, 1 - y0));
    }

    private static double clamp(double value) {
        return Double.isNaN(value) ? 0 : Math.max(0, Math.min(1, value));
    }
}
