package edu.learnsphere.core.service;

import java.util.Objects;

/**
 * How the class does on a concept.
 *
 * @param accuracy           share of correct attempts across learners, or null
 * @param learnersAttempted  learners with attempts on it
 * @param learnersStruggling learners whose accuracy on it is below 60%
 */
public record ConceptInsight(String conceptId, String name, Double accuracy, int learnersAttempted, int learnersStruggling) {

    public ConceptInsight {
        Objects.requireNonNull(conceptId, "conceptId");
        Objects.requireNonNull(name, "name");
    }
}
