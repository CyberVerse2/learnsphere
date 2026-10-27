package edu.learnsphere.core.model;

/** Whether the learner is studying a course. Stored as its lowercase {@link #code() code}. */
public enum EnrolmentStatus implements CodedEnum {

    /** Studying the course. */
    ACTIVE,

    /** Stopped studying; progress is kept. */
    LEFT;

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
    public static EnrolmentStatus fromCode(String code) {
        return CodedEnum.fromCode(EnrolmentStatus.class, code);
    }
}
