package edu.learnsphere.testbench.material;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.awt.image.WritableRaster;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Random;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.graphics.image.JPEGFactory;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;

/**
 * Makes an image-only copy of some pages of a PDF that looks like a photocopy put through an office scanner:
 * each page is rendered in greyscale at {@value #DPI} DPI, turned by a slight angle, given an off-white paper
 * tone, sensor noise, dust specks and a shadow along the binding edge, then stored as a JPEG at quality
 * {@value #JPEG_QUALITY}. The result has no text layer, so importing it exercises page reading. Deterministic
 * for a given seed.
 */
public final class ScanSimulator {

    /** Resolution the pages are scanned at. */
    public static final int DPI = 200;
    /** JPEG quality of the scanned images (0–1). */
    public static final float JPEG_QUALITY = 0.55f;
    private static final double[] ANGLES = {0.8, -1.1, 0.55, -0.7};

    private ScanSimulator() {
    }

    /** The largest angle, in degrees, a scanned page is turned by. */
    public static double maxAngleDegrees() {
        return java.util.Arrays.stream(ANGLES).map(Math::abs).max().orElse(0);
    }

    /**
     * Writes pages {@code pageNumbers} (1-based) of {@code source} to {@code target} as scanned images.
     *
     * @param seed seeds the noise, so a run is repeatable
     */
    public static void scan(Path source, List<Integer> pageNumbers, Path target, long seed) throws IOException {
        Path parent = target.toAbsolutePath().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Random random = new Random(seed);
        try (PDDocument original = Loader.loadPDF(source.toFile()); PDDocument scanned = new PDDocument()) {
            PDFRenderer renderer = new PDFRenderer(original);
            for (int i = 0; i < pageNumbers.size(); i++) {
                int number = pageNumbers.get(i);
                BufferedImage clean = renderer.renderImageWithDPI(number - 1, DPI, ImageType.GRAY);
                BufferedImage aged = age(clean, ANGLES[i % ANGLES.length], random);
                PDPage page = new PDPage(PDRectangle.A4);
                scanned.addPage(page);
                PDImageXObject image = JPEGFactory.createFromImage(scanned, aged, JPEG_QUALITY, DPI);
                try (PDPageContentStream out = new PDPageContentStream(scanned, page)) {
                    out.drawImage(image, 0, 0, PDRectangle.A4.getWidth(), PDRectangle.A4.getHeight());
                }
            }
            scanned.getDocumentInformation().setTitle("Scanned pages");
            scanned.save(target.toFile());
        }
    }

    /** The scanner's view of a clean greyscale page. */
    static BufferedImage age(BufferedImage clean, double angleDegrees, Random random) {
        int width = clean.getWidth();
        int height = clean.getHeight();
        BufferedImage turned = new BufferedImage(width, height, BufferedImage.TYPE_BYTE_GRAY);
        Graphics2D g = turned.createGraphics();
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, width, height);
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        // A little off-centre as well as turned, as a page laid on the glass by hand.
        g.translate(width * 0.006, height * 0.004);
        g.rotate(Math.toRadians(angleDegrees), width / 2.0, height / 2.0);
        g.drawImage(clean, 0, 0, null);
        g.dispose();

        WritableRaster raster = turned.getRaster();
        int[] row = new int[width];
        for (int y = 0; y < height; y++) {
            raster.getPixels(0, y, width, 1, row);
            for (int x = 0; x < width; x++) {
                double value = row[x];
                // Paper tone: white becomes light grey, ink stays dark.
                value = 236 - (255 - value) * 0.92;
                // Shadow along the binding (left) edge.
                double edge = x / (double) width;
                if (edge < 0.05) {
                    value -= (0.05 - edge) / 0.05 * 70;
                }
                value += random.nextGaussian() * 13;
                row[x] = (int) Math.max(0, Math.min(255, Math.round(value)));
            }
            raster.setPixels(0, y, width, 1, row);
        }
        // Dust and toner specks.
        int specks = 260 + random.nextInt(120);
        for (int i = 0; i < specks; i++) {
            int cx = random.nextInt(width);
            int cy = random.nextInt(height);
            int radius = 1 + random.nextInt(2);
            int shade = 40 + random.nextInt(90);
            for (int y = Math.max(0, cy - radius); y <= Math.min(height - 1, cy + radius); y++) {
                for (int x = Math.max(0, cx - radius); x <= Math.min(width - 1, cx + radius); x++) {
                    if ((x - cx) * (x - cx) + (y - cy) * (y - cy) <= radius * radius) {
                        raster.setSample(x, y, 0, Math.min(raster.getSample(x, y, 0), shade));
                    }
                }
            }
        }
        return turned;
    }
}
