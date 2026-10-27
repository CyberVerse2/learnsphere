package edu.learnsphere.core.service;

import edu.learnsphere.core.model.LearningItem;
import java.util.List;
import java.util.Objects;

/**
 * An item row in the Studio.
 *
 * @param conceptNames        names of its concepts
 * @param moduleTitle         its module's title, or null
 * @param sourceDocumentTitle the PDF it came from, or null
 * @param openFlagCount       unresolved flags
 */
public record ItemSummary(LearningItem item, List<String> conceptNames, String moduleTitle, String sourceDocumentTitle,
                          int openFlagCount) {

    public ItemSummary {
        Objects.requireNonNull(item, "item");
        conceptNames = conceptNames == null ? List.of() : List.copyOf(conceptNames);
    }

    public String id() {
        return item.id();
    }
}
