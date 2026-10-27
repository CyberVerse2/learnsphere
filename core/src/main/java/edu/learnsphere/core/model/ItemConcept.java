package edu.learnsphere.core.model;

/** A row of {@code item_concepts}: an item tagged with a concept. */
public record ItemConcept(String itemId, String conceptId, long createdAt) {
}
