package edu.learnsphere.core.model;

/** Who raised a flag on an item. Stored as its lowercase {@link #code() code}. */
public enum FlagSource implements CodedEnum {

    /** Raised in this profile. */
    SELF,

    /** Imported from a learner's feedback file. */
    LEARNER_FEEDBACK;

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
    public static FlagSource fromCode(String code) {
        return CodedEnum.fromCode(FlagSource.class, code);
    }
}
