package edu.learnsphere.testbench.material;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * The sample course material, written to disk: an operating-systems chapter and a research-methods chapter
 * with a text layer, and a scanned copy of some research-methods pages with none.
 *
 * @param chapters     the authored chapters by id ({@code os}, {@code rm})
 * @param files        the PDF of each chapter by id
 * @param scanned      the image-only PDF
 * @param scanSource   the chapter the scanned pages come from
 * @param scannedPages for each page of {@code scanned} (in order), the page of {@code scanSource} it shows
 */
public record Material(Map<String, Chapter> chapters, Map<String, Path> files, Path scanned, String scanSource,
                       List<Integer> scannedPages) {

    /** Id of the operating-systems chapter. */
    public static final String OS = "os";
    /** Id of the research-methods chapter. */
    public static final String RESEARCH_METHODS = "rm";
    /** Id used for the scanned copy in the evaluation set. */
    public static final String SCANNED = "scan";

    public Material {
        chapters = Map.copyOf(chapters);
        files = Map.copyOf(files);
        Objects.requireNonNull(scanned, "scanned");
        scannedPages = List.copyOf(scannedPages);
    }

    /**
     * Writes the material into {@code directory}: {@code os-chapter.pdf}, {@code research-methods.pdf} and
     * {@code research-methods-scanned.pdf} (pages {@code scanPages} of the research-methods chapter).
     */
    public static Material generate(Path directory, List<Integer> scanPages) throws IOException {
        Chapter os = ChapterParser.parseResource(OS, "os-chapter.txt");
        Chapter rm = ChapterParser.parseResource(RESEARCH_METHODS, "research-methods.txt");
        Path osFile = directory.resolve("os-chapter.pdf");
        Path rmFile = directory.resolve("research-methods.pdf");
        Path scanFile = directory.resolve("research-methods-scanned.pdf");
        ChapterPdf.write(os, osFile);
        ChapterPdf.write(rm, rmFile);
        for (int page : scanPages) {
            rm.page(page);
        }
        ScanSimulator.scan(rmFile, scanPages, scanFile, 20261027L);
        return new Material(Map.of(OS, os, RESEARCH_METHODS, rm), Map.of(OS, osFile, RESEARCH_METHODS, rmFile), scanFile,
                RESEARCH_METHODS, scanPages);
    }

    /** The chapter with this id. */
    public Chapter chapter(String id) {
        Chapter chapter = chapters.get(id);
        if (chapter == null) {
            throw new IllegalArgumentException("No chapter " + id);
        }
        return chapter;
    }

    /** The authored text shown on page {@code scannedPage} (1-based) of the scanned copy. */
    public String scannedReference(int scannedPage) {
        return chapter(scanSource).page(scannedPages.get(scannedPage - 1)).referenceText();
    }
}
