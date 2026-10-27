package edu.learnsphere.pdf;

import edu.learnsphere.core.util.CancellationToken;
import java.io.IOException;
import java.io.Writer;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CancellationException;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.font.PDFontDescriptor;
import org.apache.pdfbox.pdmodel.graphics.state.RenderingMode;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.pdfbox.text.TextPosition;
import org.apache.pdfbox.util.Matrix;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Runs PDFBox's text engine over a page range and hands each page, laid out by {@link LayoutAnalyzer}, to a
 * {@link PageSink} in page order. Every page in the range is delivered exactly once: pages without content,
 * and pages whose content cannot be read, arrive empty.
 *
 * <p>Uses PDFBox's glyph positions (with its duplicate-glyph suppression and diacritic merging) but none of
 * its text layout: lines, blocks and reading order come from geometry. Not thread-safe; use one per call.
 */
final class GlyphCollector extends PDFTextStripper {

    private static final Logger LOG = LoggerFactory.getLogger(GlyphCollector.class);

    /** Receives laid-out pages. */
    @FunctionalInterface
    interface PageSink {
        void accept(PageLayout page);
    }

    private final CancellationToken token;
    private final PageSink sink;
    private final Map<PDFont, Boolean> boldFonts = new IdentityHashMap<>();
    private final Set<TextPosition> strokedGlyphs = Collections.newSetFromMap(new IdentityHashMap<>());
    private PDDocument document;
    private PageGeometry geometry;
    private int delivered;

    GlyphCollector(CancellationToken token, PageSink sink) {
        this.token = Objects.requireNonNull(token, "token");
        this.sink = Objects.requireNonNull(sink, "sink");
        setSortByPosition(false);
        setSuppressDuplicateOverlappingText(true);
        setShouldSeparateByBeads(false);
    }

    /**
     * Lays out pages {@code first} to {@code last} (1-based, inclusive) and delivers them in order. Exceptions
     * thrown by the sink reach the caller unchanged.
     *
     * @throws CancellationException when the token is cancelled
     * @throws PdfReadException      ({@link PdfReadException.Reason#DAMAGED}) when the pages cannot be read at all
     */
    void collect(PDDocument pdf, Path file, int first, int last) throws IOException {
        this.document = pdf;
        this.delivered = first - 1;
        setStartPage(first);
        setEndPage(last);
        try {
            writeText(pdf, Writer.nullWriter());
            deliverEmptyPagesUpTo(last);
        } catch (SinkFailure failure) {
            throw failure.getCause();
        } catch (CancellationException | PdfReadException e) {
            throw e;
        } catch (IOException | RuntimeException e) {
            throw new PdfReadException(PdfReadException.Reason.DAMAGED, "PDF pages cannot be read: " + file, e);
        }
    }

    @Override
    public void processPage(PDPage page) throws IOException {
        int number = getCurrentPageNo();
        if (number < getStartPage() || number > getEndPage()) {
            return;
        }
        token.throwIfCancelled();
        deliverEmptyPagesUpTo(number - 1);
        strokedGlyphs.clear();
        try {
            geometry = PageGeometry.of(page);
            super.processPage(page);
        } catch (SinkFailure | CancellationException e) {
            throw e;
        } catch (IOException | RuntimeException e) {
            LOG.warn("Page {} has content that cannot be read; it is treated as empty", number, e);
        }
        deliverEmptyPagesUpTo(number);
    }

    @Override
    protected void processTextPosition(TextPosition text) {
        if (getGraphicsState().getTextState().getRenderingMode() == RenderingMode.FILL_STROKE) {
            strokedGlyphs.add(text); // fill and stroke is how many PDFs fake bold type
        }
        super.processTextPosition(text);
    }

    @Override
    protected void writePage() {
        int number = getCurrentPageNo();
        List<Glyph> glyphs = new ArrayList<>();
        for (List<TextPosition> article : getCharactersByArticle()) {
            for (TextPosition position : article) {
                Glyph glyph = toGlyph(position);
                if (glyph != null) {
                    glyphs.add(glyph);
                }
            }
        }
        deliver(LayoutAnalyzer.analyze(number, geometry, glyphs));
    }

    private void deliverEmptyPagesUpTo(int number) {
        while (delivered < number) {
            int next = delivered + 1;
            PageGeometry pageGeometry = null;
            try {
                pageGeometry = PageGeometry.of(document.getPage(next - 1));
            } catch (RuntimeException e) {
                LOG.debug("Page {} has no readable size", next, e);
            }
            deliver(PageLayout.empty(next, pageGeometry));
        }
    }

    private void deliver(PageLayout page) {
        delivered = page.pageNumber();
        try {
            sink.accept(page);
        } catch (RuntimeException e) {
            throw new SinkFailure(e);
        }
    }

    private Glyph toGlyph(TextPosition position) {
        String text = TextNormalizer.glyphText(position.getUnicode());
        if (text.isEmpty()) {
            return null;
        }
        float size = position.getYScale();
        if (!(size > 0.1f) || Float.isInfinite(size)) {
            return null;
        }
        float x = position.getXDirAdj();
        float baseline = position.getYDirAdj();
        float width = position.getWidthDirAdj();
        if (!Float.isFinite(x) || !Float.isFinite(baseline) || !Float.isFinite(width)) {
            return null;
        }
        int direction = Math.floorMod(Math.round(position.getDir() / 90f) * 90, 360);
        // Drop text placed outside the visible page: readers never see it.
        float frameWidth = geometry.frameWidth(direction);
        float frameHeight = geometry.frameHeight(direction);
        float centreX = x + width / 2;
        if (centreX < 0 || centreX > frameWidth || baseline < 0 || baseline - 0.3f * size > frameHeight) {
            return null;
        }
        float spaceWidth = position.getWidthOfSpace();
        if (!Float.isFinite(spaceWidth) || spaceWidth < 0) {
            spaceWidth = 0;
        }
        boolean bold = strokedGlyphs.contains(position) || isBold(position.getFont());

        // The glyph's box on the displayed page: the em box [0, advance] x [-descent, ascent] mapped through the
        // text rendering matrix (relative to the crop box), then turned by the page rotation.
        Matrix m = position.getTextMatrix();
        float xScale = position.getXScale();
        float advance = xScale > 0 ? width / xScale : 0;
        float[] us = {0, advance, 0, advance};
        float[] vs = {-Glyph.DESCENT, -Glyph.DESCENT, Glyph.ASCENT, Glyph.ASCENT};
        float left = Float.MAX_VALUE;
        float top = Float.MAX_VALUE;
        float right = -Float.MAX_VALUE;
        float bottom = -Float.MAX_VALUE;
        for (int i = 0; i < 4; i++) {
            float ux = us[i] * m.getScaleX() + vs[i] * m.getShearX() + m.getTranslateX();
            float uy = us[i] * m.getShearY() + vs[i] * m.getScaleY() + m.getTranslateY();
            float dx = geometry.displayX(ux, uy);
            float dy = geometry.displayY(ux, uy);
            left = Math.min(left, dx);
            right = Math.max(right, dx);
            top = Math.min(top, dy);
            bottom = Math.max(bottom, dy);
        }
        return new Glyph(text, x, baseline, width, size, spaceWidth, bold, direction, left, top, right, bottom);
    }

    private boolean isBold(PDFont font) {
        if (font == null) {
            return false;
        }
        return boldFonts.computeIfAbsent(font, GlyphCollector::looksBold);
    }

    static boolean looksBold(PDFont font) {
        PDFontDescriptor descriptor = font.getFontDescriptor();
        if (descriptor != null && (descriptor.isForceBold() || descriptor.getFontWeight() >= 600)) {
            return true;
        }
        String name = font.getName();
        if (name == null) {
            return false;
        }
        String lower = name.toLowerCase(Locale.ROOT);
        return lower.contains("bold") || lower.contains("black") || lower.contains("heavy") || lower.contains("semibold")
                || lower.contains("demi") || lower.endsWith(",b") || lower.endsWith("-b");
    }

    /** Carries an exception thrown by the sink past the per-page error handling. */
    private static final class SinkFailure extends RuntimeException {
        private static final long serialVersionUID = 1L;

        SinkFailure(RuntimeException cause) {
            super(cause);
        }

        @Override
        public synchronized RuntimeException getCause() {
            return (RuntimeException) super.getCause();
        }
    }
}
