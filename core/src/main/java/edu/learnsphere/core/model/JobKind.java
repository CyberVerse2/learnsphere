package edu.learnsphere.core.model;

/** Kinds of background job. Each kind has one handler registered with the job queue. Stored as its lowercase {@link #code() code}. */
public enum JobKind implements CodedEnum {

    /**
     * Import a PDF (check, extract, read, figures, chunk, embed, index). Subject: document id.
     *
     * <p>The job is queued when a PDF is added and queued again, with the document back at
     * {@link DocumentStatus#QUEUED} and progress 0, when the creator finishes checking scanned pages, retries
     * an import, or changes the text of a page of a ready document, and when the app stopped mid-import. The
     * handler never starts a document over; each run works out what is left from what is stored:
     * <ul>
     *   <li>Pages already in {@code document_pages} are never extracted or read again. A creator's corrections
     *       and approvals of pages read from scanned images are never overwritten, and a document whose
     *       scanned pages are all reviewed does not go back to {@link DocumentStatus#NEEDS_REVIEW}.</li>
     *   <li>Existing chunks are kept unless {@code ChunkRepository.isOutdated} reports a page changed after
     *       they were made. Outdated chunks are made again from the stored pages and swapped in with
     *       {@code ChunkRepository.replaceForDocument}, never deleted and inserted separately, so learning
     *       items keep a source passage.</li>
     *   <li>Chunks without an embedding from the current embedder are embedded, and the document's chunks are
     *       written to the search index (replacing its old entries) before it is marked ready.</li>
     * </ul>
     */
    INGEST_DOCUMENT,

    /** Extract key concepts for a module. Subject: module id. */
    EXTRACT_CONCEPTS,

    /** Generate learning items. Subject: course id. */
    GENERATE_ITEMS,

    /** Replace one draft item. Subject: item id. */
    REGENERATE_ITEM,

    /** Rebuild the search index from the database. Subject: none. */
    REBUILD_INDEX,

    /**
     * Import a shared course package (copy PDFs, store passages, index them). Subject: the course id when
     * updating, otherwise none. Result: an {@code ImportResult}.
     */
    IMPORT_PACKAGE;

    private final String code = CodedEnum.codeOf(this);

    @Override
    public String code() {
        return code;
    }

    /**
     * The constant stored as {@code code}.
     *
     * @throws IllegalArgumentException for an unknown code
     */
    public static JobKind fromCode(String code) {
        return CodedEnum.fromCode(JobKind.class, code);
    }
}
