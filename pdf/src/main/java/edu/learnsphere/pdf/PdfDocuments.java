package edu.learnsphere.pdf;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.file.AccessDeniedException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.Objects;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.io.RandomAccessRead;
import org.apache.pdfbox.io.RandomAccessReadBufferedFile;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.encryption.InvalidPasswordException;

/** Opens PDFs with PDFBox and turns every way that can fail into a {@link PdfReadException}. */
final class PdfDocuments {

    private PdfDocuments() {
    }

    /**
     * Opens {@code pdf} without a password. The caller closes the document.
     *
     * @throws PdfReadException {@link PdfReadException.Reason#MISSING MISSING} when the file is absent or
     *                          unreadable, {@link PdfReadException.Reason#ENCRYPTED ENCRYPTED} when it needs a
     *                          password, {@link PdfReadException.Reason#DAMAGED DAMAGED} when it cannot be parsed
     */
    static PDDocument open(Path pdf) throws PdfReadException {
        Objects.requireNonNull(pdf, "pdf");
        checkReadable(pdf);
        try {
            return load(new RandomAccessReadBufferedFile(pdf));
        } catch (InvalidPasswordException e) {
            throw new PdfReadException(PdfReadException.Reason.ENCRYPTED, "PDF needs a password: " + pdf, e);
        } catch (NoSuchFileException | FileNotFoundException | AccessDeniedException e) {
            throw new PdfReadException(PdfReadException.Reason.MISSING, "PDF cannot be read: " + pdf, e);
        } catch (IOException | RuntimeException e) {
            // PDFBox reports malformed files with IOExceptions and, for some structures, runtime exceptions.
            throw new PdfReadException(PdfReadException.Reason.DAMAGED, "PDF cannot be parsed: " + pdf, e);
        }
    }

    /**
     * Parses the file behind {@code reader}. The document owns the reader once loaded; when loading fails in
     * any way the reader is closed here, so a damaged file is never left open (an open file cannot be deleted
     * or replaced on Windows).
     */
    static PDDocument load(RandomAccessRead reader) throws IOException {
        try {
            return Loader.loadPDF(reader);
        } catch (Throwable e) {
            try {
                reader.close();
            } catch (IOException | RuntimeException closing) {
                e.addSuppressed(closing);
            }
            throw e;
        }
    }

    /** Fails with {@link PdfReadException.Reason#MISSING} unless {@code pdf} is a readable regular file. */
    static void checkReadable(Path pdf) throws PdfReadException {
        if (!Files.exists(pdf)) {
            throw new PdfReadException(PdfReadException.Reason.MISSING, "PDF not found: " + pdf, new NoSuchFileException(pdf.toString()));
        }
        if (!Files.isRegularFile(pdf) || !Files.isReadable(pdf)) {
            throw new PdfReadException(PdfReadException.Reason.MISSING, "PDF is not a readable file: " + pdf,
                    new AccessDeniedException(pdf.toString()));
        }
    }

    /**
     * The page with 1-based number {@code pageNumber}.
     *
     * @throws PdfReadException {@link PdfReadException.Reason#NO_SUCH_PAGE} when out of range,
     *                          {@link PdfReadException.Reason#DAMAGED} when the page tree is broken
     */
    static PDPage page(PDDocument document, int pageNumber, Path pdf) throws PdfReadException {
        checkPageNumber(document, pageNumber, pdf);
        try {
            return document.getPage(pageNumber - 1);
        } catch (RuntimeException e) {
            throw new PdfReadException(PdfReadException.Reason.DAMAGED, "Page " + pageNumber + " cannot be read: " + pdf, e);
        }
    }

    /** Fails with {@link PdfReadException.Reason#NO_SUCH_PAGE} unless {@code pageNumber} is a page of the document. */
    static void checkPageNumber(PDDocument document, int pageNumber, Path pdf) throws PdfReadException {
        int count = document.getNumberOfPages();
        if (pageNumber < 1 || pageNumber > count) {
            throw new PdfReadException(PdfReadException.Reason.NO_SUCH_PAGE,
                    "Page " + pageNumber + " is outside 1-" + count + ": " + pdf);
        }
    }
}
