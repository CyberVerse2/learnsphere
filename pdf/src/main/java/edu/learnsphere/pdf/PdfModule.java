package edu.learnsphere.pdf;

import edu.learnsphere.core.spi.Capabilities;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;

/**
 * Fills the PDF slots of {@link Capabilities}:
 *
 * <pre>{@code
 * Capabilities capabilities = PdfModule.install(Capabilities.builder())
 *         .searchIndexFactory(...)
 *         .ai(...)
 *         .build();
 * }</pre>
 *
 * <p>The implementations are stateless (the renderer only bounds how many pages render at once), so one set
 * serves every workspace.
 *
 * <p>PDFBox keeps a list of the computer's fonts, built the first time a PDF with fonts that are not embedded
 * is read (a second or two) and reused afterwards. Call {@link #useFontCacheDirectory(Path)} at start-up to
 * keep that list in the app's own folder rather than the user's home folder.
 */
public final class PdfModule {

    /** The system property PDFBox reads for the folder of its font list. */
    public static final String FONT_CACHE_PROPERTY = "pdfbox.fontcache";

    private PdfModule() {
    }

    /**
     * Keeps PDFBox's font list in {@code directory}, creating it when needed. Call once at start-up, before any
     * PDF is opened. Does nothing when {@value #FONT_CACHE_PROPERTY} is already set (for example on the command
     * line) or the folder cannot be created, in which case PDFBox uses the user's home folder.
     */
    public static void useFontCacheDirectory(Path directory) {
        Objects.requireNonNull(directory, "directory");
        if (System.getProperty(FONT_CACHE_PROPERTY) != null) {
            return;
        }
        try {
            Files.createDirectories(directory);
        } catch (IOException e) {
            return;
        }
        System.setProperty(FONT_CACHE_PROPERTY, directory.toAbsolutePath().toString());
    }

    /** Puts the PDFBox-based inspector, extractor, chunker, renderer and locator into {@code builder}. */
    public static Capabilities.Builder install(Capabilities.Builder builder) {
        Objects.requireNonNull(builder, "builder");
        return builder.pdf(new PdfBoxInspector(), new PdfBoxTextExtractor(), new SectionChunker(), new PdfBoxPageRenderer(),
                new PdfBoxPassageLocator());
    }
}
