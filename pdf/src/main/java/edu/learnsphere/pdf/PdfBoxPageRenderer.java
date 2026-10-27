package edu.learnsphere.pdf;

import edu.learnsphere.core.spi.PageRenderer;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.nio.file.Path;
import java.util.Iterator;
import java.util.concurrent.Semaphore;
import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageOutputStream;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.pdfbox.rendering.RenderDestination;

/**
 * Renders PDF pages to PNG with PDFBox.
 *
 * <p>Thread-safe with bounded memory: at most {@code maxConcurrentRenders} pages are rendered at once (other
 * callers wait), every image is capped at {@code maxPixels} pixels (a larger request is scaled down to fit,
 * keeping the page's proportions), and large page images, such as 600 DPI scans, are subsampled while
 * drawing instead of being decoded at full size. Each call opens the file on its own, so no file stays open
 * between calls.
 */
public final class PdfBoxPageRenderer implements PageRenderer {

    /** Default pixel cap: about 16 megapixels, a Letter page at 400 DPI (64 MB while drawing). */
    public static final long DEFAULT_MAX_PIXELS = 16_000_000L;
    /** Default number of pages rendered at the same time. */
    public static final int DEFAULT_MAX_CONCURRENT_RENDERS = 2;
    /** Longest side of a rendered image in pixels. */
    private static final int MAX_SIDE = 16_384;
    /** PNG compression: quality 0.5 is zlib level 4, several times faster than the default with similar size. */
    private static final float PNG_COMPRESSION_QUALITY = 0.5f;

    private final Semaphore permits;
    private final long maxPixels;

    /** A renderer with {@link #DEFAULT_MAX_CONCURRENT_RENDERS} and {@link #DEFAULT_MAX_PIXELS}. */
    public PdfBoxPageRenderer() {
        this(DEFAULT_MAX_CONCURRENT_RENDERS, DEFAULT_MAX_PIXELS);
    }

    /**
     * @param maxConcurrentRenders pages rendered at the same time (at least 1)
     * @param maxPixels            largest image in pixels (at least 10,000)
     */
    public PdfBoxPageRenderer(int maxConcurrentRenders, long maxPixels) {
        if (maxConcurrentRenders < 1) {
            throw new IllegalArgumentException("maxConcurrentRenders must be at least 1: " + maxConcurrentRenders);
        }
        if (maxPixels < 10_000) {
            throw new IllegalArgumentException("maxPixels must be at least 10000: " + maxPixels);
        }
        this.permits = new Semaphore(maxConcurrentRenders, true);
        this.maxPixels = maxPixels;
    }

    /**
     * {@inheritDoc}
     *
     * @throws IllegalArgumentException when {@code scale} is not a positive number
     * @throws PdfReadException         when the file is missing, password-protected or damaged, or has no such page
     * @throws InterruptedIOException   when the thread is interrupted while waiting for its turn
     */
    @Override
    public byte[] renderPng(Path pdf, int pageNumber, float scale) throws IOException {
        if (!(scale > 0) || Float.isInfinite(scale)) {
            throw new IllegalArgumentException("Scale must be a positive number: " + scale);
        }
        PdfDocuments.checkReadable(pdf);
        try {
            permits.acquire();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            InterruptedIOException interrupted = new InterruptedIOException("Interrupted while waiting to render " + pdf);
            interrupted.initCause(e);
            throw interrupted;
        }
        try (PDDocument document = PdfDocuments.open(pdf)) {
            PageGeometry geometry = PageGeometry.of(PdfDocuments.page(document, pageNumber, pdf));
            float effectiveScale = fitScale(geometry.displayWidth(), geometry.displayHeight(), scale);
            PDFRenderer renderer = new PDFRenderer(document);
            renderer.setSubsamplingAllowed(true);
            BufferedImage image;
            try {
                image = renderer.renderImage(pageNumber - 1, effectiveScale, ImageType.RGB, RenderDestination.VIEW);
            } catch (IOException | RuntimeException e) {
                throw new PdfReadException(PdfReadException.Reason.DAMAGED,
                        "Page " + pageNumber + " cannot be drawn: " + pdf, e);
            }
            try {
                return encodePng(image);
            } finally {
                image.flush();
            }
        } finally {
            permits.release();
        }
    }

    /** {@code scale}, reduced when needed so the image stays within the pixel and side limits. */
    float fitScale(float widthPt, float heightPt, float scale) {
        double width = Math.max(1, widthPt) * (double) scale;
        double height = Math.max(1, heightPt) * (double) scale;
        double factor = 1;
        if (width * height > maxPixels) {
            factor = Math.sqrt(maxPixels / (width * height));
        }
        double longest = Math.max(width, height) * factor;
        if (longest > MAX_SIDE) {
            factor *= MAX_SIDE / longest;
        }
        return (float) (scale * factor);
    }

    private static byte[] encodePng(BufferedImage image) throws IOException {
        Iterator<ImageWriter> writers = ImageIO.getImageWritersByFormatName("png");
        if (!writers.hasNext()) {
            throw new IOException("No PNG encoder available");
        }
        ImageWriter writer = writers.next();
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(image.getWidth() * image.getHeight() / 4 + 1024);
        try (ImageOutputStream out = ImageIO.createImageOutputStream(bytes)) {
            writer.setOutput(out);
            ImageWriteParam param = writer.getDefaultWriteParam();
            if (param.canWriteCompressed()) {
                param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
                param.setCompressionQuality(PNG_COMPRESSION_QUALITY);
            }
            writer.write(null, new IIOImage(image, null, null), param);
        } finally {
            writer.dispose();
        }
        return bytes.toByteArray();
    }
}
