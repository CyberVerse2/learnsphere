package edu.learnsphere.core.model;

/** Outcome of a chat message. Stored as its lowercase {@link #code() code}. */
public enum MessageStatus implements CodedEnum {

    /** A finished message (every question, and answers with valid citations). */
    COMPLETE,

    /** The material does not cover the question. */
    INSUFFICIENT,

    /** The answer could not be produced. */
    ERROR,

    /** The learner stopped the answer. */
    CANCELLED;

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
    public static MessageStatus fromCode(String code) {
        return CodedEnum.fromCode(MessageStatus.class, code);
    }
}
