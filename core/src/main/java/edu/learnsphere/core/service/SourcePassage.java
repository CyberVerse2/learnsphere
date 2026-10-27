package edu.learnsphere.core.service;

import java.util.Objects;

/** The passage an item was drafted from, shown beside it in the review queue. */
public record SourcePassage(SourceRef ref, String sectionTitle, String text) {

    public SourcePassage {
        Objects.requireNonNull(ref, "ref");
        Objects.requireNonNull(text, "text");
    }
}
