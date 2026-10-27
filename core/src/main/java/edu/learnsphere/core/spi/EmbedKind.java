package edu.learnsphere.core.spi;

/** Whether a text is a search query or a passage; queries get an instruction prefix, passages do not. */
public enum EmbedKind {
    /** A learner's question. */
    QUERY,
    /** A passage from the material. */
    DOCUMENT
}
