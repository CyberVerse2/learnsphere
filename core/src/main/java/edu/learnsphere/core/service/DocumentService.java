package edu.learnsphere.core.service;

import edu.learnsphere.core.error.ConflictException;
import edu.learnsphere.core.error.InvalidInputException;
import edu.learnsphere.core.error.NotFoundException;
import edu.learnsphere.core.model.DocumentPage;
import edu.learnsphere.core.spi.NormalizedRect;
import java.nio.file.Path;
import java.util.Collection;
import java.util.List;

/**
 * PDFs in a course. Importing copies the file into the profile and runs the import pipeline as a
 * background job, reporting through {@link edu.learnsphere.core.events.DocumentChanged}. Methods taking
 * an id throw {@link NotFoundException} when it does not exist.
 */
public interface DocumentService {

    /**
     * Adds a PDF to a course (and optionally a module) and starts importing it. Returns at once with the
     * document queued; problems found while importing (encrypted, too large, damaged) end in status
     * failed with an error message.
     *
     * @param moduleId the module, or null for the whole course
     * @throws InvalidInputException when the file is not a readable PDF
     * @throws ConflictException     when this PDF is already in the course
     */
    DocumentSummary importPdf(String courseId, String moduleId, Path pdf);

    /** PDFs of a course, oldest first. */
    List<DocumentSummary> list(String courseId);

    DocumentSummary get(String documentId);

    /** @throws InvalidInputException when the title is blank */
    void rename(String documentId, String title);

    /** Moves a PDF to another module, or to the whole course with {@code null}. */
    void moveToModule(String documentId, String moduleId);

    /** Removes a PDF with its pages and passages; items made from it keep their text but lose their source. */
    void remove(String documentId);

    /** Restarts a failed import where it stopped. */
    void retry(String documentId);

    /** Pages read from scanned images that still need a creator's check, in page order. */
    List<DocumentPage> pagesNeedingReview(String documentId);

    /** One page's text. */
    DocumentPage page(String documentId, int pageNumber);

    /**
     * Replaces a page's text and marks it reviewed. When the last page needing review is done, the import
     * continues.
     */
    void editPageText(String documentId, int pageNumber, String text);

    /** Marks pages as reviewed without changing their text; continues the import when none are left. */
    void approvePages(String documentId, Collection<Integer> pageNumbers);

    /**
     * A page rendered as PNG at {@code scale} (1 = 72 DPI; use 2 for crisp display on high-density
     * screens). Cached on disk.
     */
    RenderedPage renderPage(String documentId, int pageNumber, double scale);

    /** Where {@code passage} appears on a page, for highlighting a citation. Empty when not found. */
    List<NormalizedRect> locatePassage(String documentId, int pageNumber, String passage);

    /**
     * The passages with these ids that still exist, each with its PDF's title, its pages and its section, in no
     * particular order. Shows where cited text comes from ("Lecture 1, p. 17, Threads"); ids of removed passages
     * are left out. The default knows no passages.
     */
    default List<SourcePassage> passages(Collection<String> chunkIds) {
        return List.of();
    }
}
