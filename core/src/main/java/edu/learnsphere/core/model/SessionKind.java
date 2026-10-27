package edu.learnsphere.core.model;

/** What a study session is made of. Stored as its lowercase {@link #code() code}. */
public enum SessionKind implements CodedEnum {

    /** Due reviews, then weak concepts, then new items. */
    STUDY_NEXT,

    /** A quiz over one module. */
    MODULE_QUIZ,

    /** Flashcards only, self-rated. */
    FLASHCARDS,

    /** Items from concepts with low mastery. */
    WEAK_CONCEPTS;

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
    public static SessionKind fromCode(String code) {
        return CodedEnum.fromCode(SessionKind.class, code);
    }
}
