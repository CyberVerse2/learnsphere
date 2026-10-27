package edu.learnsphere.core.service;

import java.util.List;
import java.util.Objects;

/**
 * A nudge for the current item. Using one caps the rating at Hard.
 *
 * @param text           the hint, e.g. the section it comes from or the first letter
 * @param removedChoices multiple-choice options ruled out by the hint (hide them)
 */
public record Hint(String text, List<String> removedChoices) {

    public Hint {
        Objects.requireNonNull(text, "text");
        removedChoices = removedChoices == null ? List.of() : List.copyOf(removedChoices);
    }
}
