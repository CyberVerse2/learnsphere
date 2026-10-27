package edu.learnsphere.core.spi;

import java.util.Objects;

/** A key concept proposed by {@link ConceptExtractor}. */
public record ConceptDraft(String name, String description) {

    public ConceptDraft {
        Objects.requireNonNull(name, "name");
        description = Objects.requireNonNullElse(description, "");
    }
}
