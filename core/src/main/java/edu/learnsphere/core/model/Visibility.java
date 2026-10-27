package edu.learnsphere.core.model;

/** Whether and how a course can be shared. Stored as its lowercase {@link #code() code}. */
public enum Visibility implements CodedEnum {

    /** Cannot be exported. */
    PRIVATE,

    /** Exported as a plain package. */
    SHAREABLE,

    /** Exported encrypted; importers need the code the creator chose. */
    PROTECTED;

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
    public static Visibility fromCode(String code) {
        return CodedEnum.fromCode(Visibility.class, code);
    }
}
