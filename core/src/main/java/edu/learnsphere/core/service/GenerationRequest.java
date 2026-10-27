package edu.learnsphere.core.service;

import edu.learnsphere.core.model.Difficulty;
import edu.learnsphere.core.model.ItemType;
import java.util.Objects;
import java.util.Set;

/**
 * What the "Generate items" dialog collects.
 *
 * @param moduleId   the module to draw from, or null for the whole course
 * @param types      item types to create (empty means all types)
 * @param count      how many items to aim for (1–100)
 * @param difficulty wanted difficulty, or null for a mix
 * @param conceptIds concepts to focus on (empty means all concepts of the scope)
 */
public record GenerationRequest(String courseId, String moduleId, Set<ItemType> types, int count, Difficulty difficulty,
                                Set<String> conceptIds) {

    /** Most items one request may ask for. */
    public static final int MAX_COUNT = 100;

    public GenerationRequest {
        Objects.requireNonNull(courseId, "courseId");
        types = types == null ? Set.of() : Set.copyOf(types);
        conceptIds = conceptIds == null ? Set.of() : Set.copyOf(conceptIds);
        if (count < 1 || count > MAX_COUNT) {
            throw new IllegalArgumentException("count must be 1-" + MAX_COUNT + ": " + count);
        }
    }

    /** {@code count} items of every type for a module, mixed difficulty. */
    public static GenerationRequest forModule(String courseId, String moduleId, int count) {
        return new GenerationRequest(courseId, moduleId, Set.of(), count, null, Set.of());
    }
}
