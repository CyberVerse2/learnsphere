package edu.learnsphere.core.model;

/** Scheduling phase of an item (FSRS state). Stored as its lowercase {@link #code() code}. */
public enum ReviewPhase implements CodedEnum {

    /** Never reviewed. */
    NEW,

    /** Seen recently for the first time. */
    LEARNING,

    /** In regular spaced review. */
    REVIEW,

    /** Forgotten and being learned again. */
    RELEARNING;

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
    public static ReviewPhase fromCode(String code) {
        return CodedEnum.fromCode(ReviewPhase.class, code);
    }
}
