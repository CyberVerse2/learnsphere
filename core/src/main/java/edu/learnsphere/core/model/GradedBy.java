package edu.learnsphere.core.model;

/** How an attempt was graded. Stored as its lowercase {@link #code() code}. */
public enum GradedBy implements CodedEnum {

    /** Compared with the answer key (multiple choice, true/false, cloze). */
    RULE,

    /** Graded by the study assistant (short answers); can be disputed. */
    AI,

    /** Rated by the learner (flashcards). */
    SELF;

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
    public static GradedBy fromCode(String code) {
        return CodedEnum.fromCode(GradedBy.class, code);
    }
}
