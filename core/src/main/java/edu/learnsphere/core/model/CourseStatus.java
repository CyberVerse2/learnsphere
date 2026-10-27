package edu.learnsphere.core.model;

/** Lifecycle of a course. Stored as its lowercase {@link #code() code}. */
public enum CourseStatus implements CodedEnum {

    /** Being written; never published. */
    DRAFT,

    /** At least one version has been published. */
    PUBLISHED,

    /** Hidden from the library by default. */
    ARCHIVED;

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
    public static CourseStatus fromCode(String code) {
        return CodedEnum.fromCode(CourseStatus.class, code);
    }
}
