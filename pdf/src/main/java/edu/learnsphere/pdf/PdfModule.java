package edu.learnsphere.pdf;

import edu.learnsphere.core.spi.Capabilities;
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
 */
public final class PdfModule {

    private PdfModule() {
    }

    /** Puts the PDFBox-based inspector, extractor, chunker, renderer and locator into {@code builder}. */
    public static Capabilities.Builder install(Capabilities.Builder builder) {
        Objects.requireNonNull(builder, "builder");
        return builder.pdf(new PdfBoxInspector(), new PdfBoxTextExtractor(), new SectionChunker(), new PdfBoxPageRenderer(),
                new PdfBoxPassageLocator());
    }
}
