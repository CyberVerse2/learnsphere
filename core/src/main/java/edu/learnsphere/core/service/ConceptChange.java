package edu.learnsphere.core.service;

import java.util.Objects;

/**
 * How a concept's mastery moved during a session.
 *
 * @param before mastery before the session, or null when it had none
 * @param after  mastery after, 0–1
 */
public record ConceptChange(String conceptId, String name, Double before, double after) {

    public ConceptChange {
        Objects.requireNonNull(conceptId, "conceptId");
        Objects.requireNonNull(name, "name");
    }

    /** The change in mastery (from 0 when there was none). */
    public double delta() {
        return after - (before == null ? 0 : before);
    }
}
