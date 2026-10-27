package edu.learnsphere.pdf;

import java.awt.geom.Point2D;
import java.io.IOException;
import org.apache.pdfbox.contentstream.PDFGraphicsStreamEngine;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.font.PDSimpleFont;
import org.apache.pdfbox.pdmodel.graphics.image.PDImage;
import org.apache.pdfbox.util.Matrix;
import org.apache.pdfbox.util.Vector;

/**
 * Runs through one page's content counting text characters and measuring how much of the page images cover,
 * without laying anything out or drawing anything.
 *
 * <p>Image coverage is measured on a {@value #GRID}×{@value #GRID} grid over the crop box, so overlapping
 * images are not counted twice and images reaching past the page edge count only for the visible part.
 * Glyphs of Type 3 fonts are counted as text but their drawings (which can be tiny images) are not run.
 */
final class PageScanner extends PDFGraphicsStreamEngine {

    static final int GRID = 128;

    private final boolean[] covered = new boolean[GRID * GRID];
    private final float cropLeft;
    private final float cropBottom;
    private final float cropWidth;
    private final float cropHeight;
    private int coveredCells;
    private int textChars;

    PageScanner(PDPage page) {
        super(page);
        PDRectangle crop = page.getCropBox();
        cropLeft = Math.min(crop.getLowerLeftX(), crop.getUpperRightX());
        cropBottom = Math.min(crop.getLowerLeftY(), crop.getUpperRightY());
        cropWidth = Math.abs(crop.getWidth());
        cropHeight = Math.abs(crop.getHeight());
    }

    /** Processes the page's content. */
    PageScanner scan() throws IOException {
        processPage(getPage());
        return this;
    }

    /** Non-whitespace characters in the text layer. */
    int textChars() {
        return textChars;
    }

    /** Share of the page covered by images, 0–1. */
    double imageCoverage() {
        return coveredCells / (double) (GRID * GRID);
    }

    @Override
    protected void showGlyph(Matrix textRenderingMatrix, PDFont font, int code, Vector displacement) throws IOException {
        String unicode = font.toUnicode(code);
        if (unicode == null) {
            // Text extraction falls back to the character code for simple fonts and skips composite fonts.
            if (font instanceof PDSimpleFont && code > ' ' && code != 0xA0) {
                textChars++;
            }
            return;
        }
        for (int i = 0; i < unicode.length(); i++) {
            char c = unicode.charAt(i);
            if (!Character.isWhitespace(c) && !Character.isSpaceChar(c) && !Character.isISOControl(c)) {
                textChars++;
            }
        }
    }

    @Override
    public void drawImage(PDImage image) {
        if (cropWidth <= 0 || cropHeight <= 0) {
            return;
        }
        Matrix ctm = getGraphicsState().getCurrentTransformationMatrix();
        float minX = Float.MAX_VALUE;
        float minY = Float.MAX_VALUE;
        float maxX = -Float.MAX_VALUE;
        float maxY = -Float.MAX_VALUE;
        // Images fill the unit square of their transformation matrix.
        for (int corner = 0; corner < 4; corner++) {
            Point2D.Float point = ctm.transformPoint(corner & 1, corner >> 1);
            minX = Math.min(minX, point.x);
            maxX = Math.max(maxX, point.x);
            minY = Math.min(minY, point.y);
            maxY = Math.max(maxY, point.y);
        }
        int columnFrom = cell((minX - cropLeft) / cropWidth, true);
        int columnTo = cell((maxX - cropLeft) / cropWidth, false);
        int rowFrom = cell((minY - cropBottom) / cropHeight, true);
        int rowTo = cell((maxY - cropBottom) / cropHeight, false);
        for (int row = rowFrom; row <= rowTo; row++) {
            for (int column = columnFrom; column <= columnTo; column++) {
                int index = row * GRID + column;
                if (!covered[index]) {
                    covered[index] = true;
                    coveredCells++;
                }
            }
        }
    }

    /** The first (or last) grid cell whose centre lies at or after (or before) {@code fraction} of the page. */
    private static int cell(float fraction, boolean first) {
        if (Float.isNaN(fraction)) {
            return first ? GRID : -1;
        }
        double position = fraction * GRID - 0.5;
        int index = first ? (int) Math.ceil(position) : (int) Math.floor(position);
        return Math.max(first ? 0 : -1, Math.min(first ? GRID : GRID - 1, index));
    }

    // Paths are not needed: only images and text count.

    @Override
    public void appendRectangle(Point2D p0, Point2D p1, Point2D p2, Point2D p3) {
    }

    @Override
    public void clip(int windingRule) {
    }

    @Override
    public void moveTo(float x, float y) {
    }

    @Override
    public void lineTo(float x, float y) {
    }

    @Override
    public void curveTo(float x1, float y1, float x2, float y2, float x3, float y3) {
    }

    @Override
    public Point2D getCurrentPoint() {
        return new Point2D.Float();
    }

    @Override
    public void closePath() {
    }

    @Override
    public void endPath() {
    }

    @Override
    public void strokePath() {
    }

    @Override
    public void fillPath(int windingRule) {
    }

    @Override
    public void fillAndStrokePath(int windingRule) {
    }

    @Override
    public void shadingFill(COSName shadingName) {
    }
}
