package edu.learnsphere.core.model;

/** Where the text of a document page came from. Stored as its lowercase {@link #code() code}. */
public enum PageSource implements CodedEnum {

    /** The PDF's own text layer. */
    TEXT_LAYER,

    /** Transcribed from the page image by the study assistant; must be reviewed before use as evidence. */
    AI_READ;

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
    public static PageSource fromCode(String code) {
        return CodedEnum.fromCode(PageSource.class, code);
    }
}
