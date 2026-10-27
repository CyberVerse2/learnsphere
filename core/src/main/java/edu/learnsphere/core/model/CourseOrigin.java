package edu.learnsphere.core.model;

/** Where a course came from. Stored as its lowercase {@link #code() code}. */
public enum CourseOrigin implements CodedEnum {

    /** Created in this profile. */
    LOCAL,

    /** Imported from a shared course package. */
    IMPORTED;

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
    public static CourseOrigin fromCode(String code) {
        return CodedEnum.fromCode(CourseOrigin.class, code);
    }
}
