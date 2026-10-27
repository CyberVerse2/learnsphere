package edu.learnsphere.core.model;

/** Review status of a learning item. Learners only ever see approved items. Stored as its lowercase {@link #code() code}. */
public enum ItemStatus implements CodedEnum {

    /** Waiting for the creator's review. */
    DRAFT,

    /** Usable in study sessions and published versions. */
    APPROVED,

    /** Discarded by the creator. */
    REJECTED,

    /** Removed from study (for example missing from an updated package); progress is kept. */
    RETIRED;

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
    public static ItemStatus fromCode(String code) {
        return CodedEnum.fromCode(ItemStatus.class, code);
    }
}
