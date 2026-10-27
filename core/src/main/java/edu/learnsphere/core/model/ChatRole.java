package edu.learnsphere.core.model;

/** Author of a chat message. Stored as its lowercase {@link #code() code}. */
public enum ChatRole implements CodedEnum {

    /** The learner's question. */
    USER,

    /** The answer. */
    ASSISTANT;

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
    public static ChatRole fromCode(String code) {
        return CodedEnum.fromCode(ChatRole.class, code);
    }
}
