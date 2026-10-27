package edu.learnsphere.core.learning;

import edu.learnsphere.core.model.LearningItem;
import edu.learnsphere.core.model.ReviewState;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;

/**
 * An item the {@link StudyPlanner} put in a session.
 *
 * @param state          its review state, or null when it was never studied
 * @param isNew          true when the learner sees it for the first time
 * @param conceptIds     concepts it is tagged with
 * @param retrievability probability of recall when planned (0 for new items)
 * @param reason         why it was chosen
 */
public record PlannedItem(LearningItem item, ReviewState state, boolean isNew, Set<String> conceptIds,
                          double retrievability, Reason reason) {

    /** Why an item is in a session. */
    public enum Reason {
        /** Due for review. */
        DUE,
        /** Tagged with a weak concept. */
        WEAK_CONCEPT,
        /** Never studied. */
        NEW,
        /** Reviewed before and not due, practised by choice (quizzes and flashcards). */
        PRACTICE
    }

    public PlannedItem {
        Objects.requireNonNull(item, "item");
        Objects.requireNonNull(reason, "reason");
        conceptIds = conceptIds == null ? Set.of() : Collections.unmodifiableSet(new LinkedHashSet<>(conceptIds));
    }

    public String itemId() {
        return item.id();
    }

    /** True when both items test the same thing: a shared concept, or (both untagged) the same module. */
    public boolean clashesWith(PlannedItem other) {
        if (!conceptIds.isEmpty() && !other.conceptIds.isEmpty()) {
            for (String id : conceptIds) {
                if (other.conceptIds.contains(id)) {
                    return true;
                }
            }
            return false;
        }
        if (conceptIds.isEmpty() && other.conceptIds.isEmpty()) {
            return item.moduleId() != null && item.moduleId().equals(other.item.moduleId());
        }
        return false;
    }
}
