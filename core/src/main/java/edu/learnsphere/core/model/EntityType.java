package edu.learnsphere.core.model;

/** Kinds of record named in the audit log. Stored as its lowercase {@link #code() code}. */
public enum EntityType implements CodedEnum {

    /** A profile. */
    PROFILE,

    /** A course. */
    COURSE,

    /** A published course version. */
    COURSE_VERSION,

    /** A module. */
    MODULE,

    /** A PDF document. */
    DOCUMENT,

    /** A concept. */
    CONCEPT,

    /** A learning item. */
    ITEM,

    /** A flag. */
    FLAG,

    /** A setting. */
    SETTING,

    /** A shared course, progress or feedback file. */
    PACKAGE;

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
    public static EntityType fromCode(String code) {
        return CodedEnum.fromCode(EntityType.class, code);
    }
}
