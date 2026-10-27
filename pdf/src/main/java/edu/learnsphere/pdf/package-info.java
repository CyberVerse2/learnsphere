/**
 * PDF handling with Apache PDFBox, implementing the PDF interfaces in {@code edu.learnsphere.core.spi}.
 * {@link edu.learnsphere.pdf.PdfModule#install} puts all five into a {@code Capabilities} builder.
 *
 * <ul>
 *   <li>{@link edu.learnsphere.pdf.PdfBoxInspector}: page count, password protection, damage, and per page
 *       the text layer and how much of it images cover.</li>
 *   <li>{@link edu.learnsphere.pdf.PdfBoxTextExtractor}: text blocks with font size, weight and position,
 *       streamed page by page. Pages are laid out from glyph positions ({@code LayoutAnalyzer}: lines, blocks,
 *       column gutters), put in reading order ({@code ReadingOrder}: two-column pages column by column), freed
 *       of running headers, footers and page numbers ({@code MarginFilter}) and joined into block text with
 *       line-end hyphens mended ({@code PageTextBuilder}).</li>
 *   <li>{@link edu.learnsphere.pdf.SectionChunker}: section-aware passages of about 350 tokens with overlap,
 *       cut at sentence ends ({@code HeadingDetector}, {@code SentenceSplitter},
 *       {@link edu.learnsphere.pdf.TokenEstimator}).</li>
 *   <li>{@link edu.learnsphere.pdf.PdfBoxPageRenderer}: PNG page images with a bounded number of concurrent
 *       renders and a pixel cap.</li>
 *   <li>{@link edu.learnsphere.pdf.PdfBoxPassageLocator}: where a cited passage sits on its page, as one
 *       rectangle per line, found with {@code PassageMatcher}'s fuzzy matching.</li>
 * </ul>
 *
 * <p>Files that cannot be read fail with {@link edu.learnsphere.pdf.PdfReadException}, which carries the
 * {@code DocumentError} and {@code UserMessage} to show. The test fixtures ({@code edu.learnsphere.pdf.testing.TestPdf})
 * write realistic PDFs for tests in any module.
 */
package edu.learnsphere.pdf;
