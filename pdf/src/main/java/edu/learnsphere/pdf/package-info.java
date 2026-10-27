/**
 * PDF handling with Apache PDFBox, implementing the PDF interfaces in {@code edu.learnsphere.core.spi}:
 * {@link edu.learnsphere.core.spi.PdfInspector} (page count, encryption, damage, text-layer and image
 * coverage per page), {@link edu.learnsphere.core.spi.PdfTextExtractor} (text blocks with font size, weight
 * and position, streamed page by page), {@link edu.learnsphere.core.spi.Chunker} (section-aware passages of
 * about 350 tokens with overlap), {@link edu.learnsphere.core.spi.PageRenderer} (page images) and
 * {@link edu.learnsphere.core.spi.PassageLocator} (where a cited passage sits on its page, for highlighting).
 */
package edu.learnsphere.pdf;
