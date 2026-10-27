package edu.learnsphere.core.model;

/**
 * Kinds of learning item, with the content conventions every writer and reader follows.
 * Stored as its lowercase {@link #code() code}.
 */
public enum ItemType implements CodedEnum {

    /**
     * Multiple choice. {@code answer} is the correct option's text; {@code distractors} holds two to
     * four wrong options. Options are shuffled when shown.
     */
    MCQ,

    /** True or false. {@code prompt} is a statement; {@code answer} is {@code "true"} or {@code "false"}. */
    TRUE_FALSE,

    /** Free text answer graded by the study assistant against {@code answer} as the reference. */
    SHORT_ANSWER,

    /** Front ({@code prompt}) and back ({@code answer}); the learner rates their own recall. */
    FLASHCARD,

    /**
     * Fill in the blank. {@code prompt} contains exactly one blank written as {@value #CLOZE_BLANK};
     * {@code answer} is the missing text.
     */
    CLOZE;

    /** The blank marker inside a cloze prompt. */
    public static final String CLOZE_BLANK = "____";

    private final String code = CodedEnum.codeOf(this);

    @Override
    public String code() {
        return code;
    }

    /** True for types graded by comparing with the answer key (multiple choice, true/false, cloze). */
    public boolean isRuleGraded() {
        return this == MCQ || this == TRUE_FALSE || this == CLOZE;
    }

    /** The grader used for this type. */
    public GradedBy grader() {
        return switch (this) {
            case MCQ, TRUE_FALSE, CLOZE -> GradedBy.RULE;
            case SHORT_ANSWER -> GradedBy.AI;
            case FLASHCARD -> GradedBy.SELF;
        };
    }

    /**
     * The constant stored as {@code code}.
     *
     * @throws IllegalArgumentException for an unknown code
     */
    public static ItemType fromCode(String code) {
        return CodedEnum.fromCode(ItemType.class, code);
    }
}
