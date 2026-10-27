package edu.learnsphere.core.model;

/** Why an item was flagged. Stored as its lowercase {@link #code() code}. */
public enum FlagReason implements CodedEnum {

    /** The answer looks wrong. */
    WRONG_ANSWER,

    /** The question is unclear. */
    UNCLEAR,

    /** The material does not cover it. */
    NOT_IN_MATERIAL,

    /** A spelling or formatting mistake. */
    TYPO,

    /** Anything else; see the note. */
    OTHER;

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
    public static FlagReason fromCode(String code) {
        return CodedEnum.fromCode(FlagReason.class, code);
    }
}
