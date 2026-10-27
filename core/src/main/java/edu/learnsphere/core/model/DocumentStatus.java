package edu.learnsphere.core.model;

/** Stage of a PDF in the import pipeline. Every stage is resumable. Stored as its lowercase {@link #code() code}. */
public enum DocumentStatus implements CodedEnum {

    /** Waiting for its turn. */
    QUEUED,

    /** Checking size, pages and encryption, and copying the file. */
    CHECKING,

    /** Reading the text layer page by page. */
    EXTRACTING,

    /** Transcribing scanned pages from their images. */
    READING,

    /** Splitting into passages and making them searchable. */
    INDEXING,

    /** Waiting for the creator to check pages read from scanned images. */
    NEEDS_REVIEW,

    /** Usable as evidence. */
    READY,

    /** Stopped; see the error code. */
    FAILED;

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
    public static DocumentStatus fromCode(String code) {
        return CodedEnum.fromCode(DocumentStatus.class, code);
    }
}
