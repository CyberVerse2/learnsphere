package edu.learnsphere.core.model;

/** Who wrote a learning item. Stored as its lowercase {@link #code() code}. */
public enum ItemOrigin implements CodedEnum {

    /** Drafted by the study assistant. */
    AI,

    /** Written by the creator. */
    MANUAL;

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
    public static ItemOrigin fromCode(String code) {
        return CodedEnum.fromCode(ItemOrigin.class, code);
    }
}
