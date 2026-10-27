package edu.learnsphere.core.model;

/** Whether a passage from the material supports an item's answer. Stored as its lowercase {@link #code() code}. */
public enum SupportStatus implements CodedEnum {

    /** The source passage supports the answer. */
    SUPPORTED,

    /** The source passage does not support the answer. */
    UNSUPPORTED,

    /** Not checked yet (manual items, or the check could not run). */
    UNCHECKED;

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
    public static SupportStatus fromCode(String code) {
        return CodedEnum.fromCode(SupportStatus.class, code);
    }
}
