package edu.learnsphere.core.sharing;

import java.util.List;

/**
 * The JSON entries of a course package. Enum values are stored as their lowercase database codes and
 * times as epoch milliseconds, so the files stay stable when Java names change. Ids are the creator's
 * ids: an imported course keeps them, which is how updates, progress files and feedback find the same
 * items again.
 */
public final class PackageData {

    private PackageData() {
    }

    private static <T> List<T> copy(List<T> list) {
        return list == null ? List.of() : List.copyOf(list);
    }

    /** {@code course.json}. */
    public record CourseFile(String packageId, int versionNumber, CourseInfo course, VersionInfo version,
                             List<ModuleData> modules, List<ConceptData> concepts, List<ItemData> items,
                             List<ItemConceptData> itemConcepts, List<DocumentData> documents) {

        public CourseFile {
            modules = copy(modules);
            concepts = copy(concepts);
            items = copy(items);
            itemConcepts = copy(itemConcepts);
            documents = copy(documents);
        }
    }

    /**
     * The course's details.
     *
     * @param visibility {@code shareable} or {@code protected}
     */
    public record CourseInfo(String title, String subtitle, String description, String authorName, String visibility,
                             int coverHue) {
    }

    /** The published version the package holds. */
    public record VersionInfo(int number, String notes, long publishedAt) {
    }

    /** A module; {@code position} orders modules within the course. */
    public record ModuleData(String id, String title, String summary, int position) {
    }

    /** A concept; {@code moduleId} is null for a course-wide concept. */
    public record ConceptData(String id, String moduleId, String name, String description) {
    }

    /**
     * An approved item of the version.
     *
     * @param type          {@code ItemType} code
     * @param difficulty    1–3
     * @param sourceChunkId a passage in {@code chunks.json}, or null
     * @param origin        {@code ItemOrigin} code
     * @param support       {@code SupportStatus} code
     * @param createdAt     the creator's creation time, which orders new items within a module
     */
    public record ItemData(String id, String moduleId, String type, String prompt, String answer, String explanation,
                           List<String> distractors, int difficulty, String sourceChunkId, Integer sourcePage,
                           String origin, String support, String supportNote, long createdAt) {

        public ItemData {
            distractors = copy(distractors);
        }
    }

    /** An item→concept link. */
    public record ItemConceptData(String itemId, String conceptId) {
    }

    /**
     * A document.
     *
     * @param sha256       SHA-256 of the PDF
     * @param fileIncluded true when {@code documents/<sha256>.pdf} is in the package
     * @param createdAt    the creator's import time, which orders documents within the course
     */
    public record DocumentData(String id, String moduleId, String title, String fileName, String sha256, long byteSize,
                               int pageCount, boolean fileIncluded, long createdAt) {
    }

    /** {@code pages.json}. */
    public record PagesFile(List<PageData> pages) {

        public PagesFile {
            pages = copy(pages);
        }
    }

    /**
     * The usable text of one page.
     *
     * @param source {@code PageSource} code
     */
    public record PageData(String documentId, int pageNumber, String text, String source, String figureNotes,
                           boolean hasImages) {
    }

    /** {@code chunks.json}. */
    public record ChunksFile(List<ChunkData> chunks) {

        public ChunksFile {
            chunks = copy(chunks);
        }
    }

    /**
     * A passage.
     *
     * @param embeddingModel the embedder that made {@code embedding}, or null
     * @param embedding      base64 float32 little-endian vector, or null when the passage has none
     */
    public record ChunkData(String id, String documentId, int ordinal, int pageStart, int pageEnd, String sectionTitle,
                            String text, int tokenEstimate, String embeddingModel, String embedding) {
    }
}
