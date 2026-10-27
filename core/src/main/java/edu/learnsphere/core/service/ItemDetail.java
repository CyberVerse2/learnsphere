package edu.learnsphere.core.service;

import edu.learnsphere.core.model.Concept;
import edu.learnsphere.core.model.Flag;
import edu.learnsphere.core.model.LearningItem;
import java.util.List;
import java.util.Objects;

/**
 * Everything the review card and item editor show.
 *
 * @param source the passage it was drafted from, or null
 * @param flags  flags on it, newest first
 * @param stats  how learners did on it
 */
public record ItemDetail(LearningItem item, List<Concept> concepts, SourcePassage source, List<Flag> flags, ItemStats stats) {

    public ItemDetail {
        Objects.requireNonNull(item, "item");
        concepts = concepts == null ? List.of() : List.copyOf(concepts);
        flags = flags == null ? List.of() : List.copyOf(flags);
    }
}
