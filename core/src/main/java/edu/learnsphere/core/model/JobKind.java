package edu.learnsphere.core.model;

/** Kinds of background job. Each kind has one handler registered with the job queue. Stored as its lowercase {@link #code() code}. */
public enum JobKind implements CodedEnum {

    /** Import a PDF (check, extract, read, figures, chunk, embed, index). Subject: document id. */
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
