package edu.learnsphere.core.db;

import edu.learnsphere.core.model.ItemOrigin;
import edu.learnsphere.core.model.ItemStatus;
import edu.learnsphere.core.model.ItemType;
import edu.learnsphere.core.model.SupportStatus;
import java.util.Set;

/**
 * Filters for {@link LearningItemRepository#list(ItemQuery)}. Null or empty fields do not filter.
 * Build with {@link #all()} or {@link #forCourse(String)} and the {@code with…} methods.
 *
 * @param courseId    only this course
 * @param moduleId    only this module
 * @param statuses    any of these statuses
 * @param types       any of these types
 * @param support     any of these support verdicts
 * @param origin      only AI or only manual
 * @param conceptId   only items tagged with this concept
 * @param search      case-insensitive text in prompt, answer or explanation
 * @param flaggedOnly only items with an open flag
 * @param limit       at most this many (0 = no limit)
 * @param offset      skip this many
 */
public record ItemQuery(String courseId, String moduleId, Set<ItemStatus> statuses, Set<ItemType> types,
                        Set<SupportStatus> support, ItemOrigin origin, String conceptId, String search,
                        boolean flaggedOnly, int limit, int offset) {

    public ItemQuery {
        statuses = statuses == null ? Set.of() : Set.copyOf(statuses);
        types = types == null ? Set.of() : Set.copyOf(types);
        support = support == null ? Set.of() : Set.copyOf(support);
    }

    /** No filters. */
    public static ItemQuery all() {
        return new ItemQuery(null, null, Set.of(), Set.of(), Set.of(), null, null, null, false, 0, 0);
    }

    /** Every item of a course. */
    public static ItemQuery forCourse(String courseId) {
        return all().withCourse(courseId);
    }

    public ItemQuery withCourse(String id) {
        return new ItemQuery(id, moduleId, statuses, types, support, origin, conceptId, search, flaggedOnly, limit, offset);
    }

    public ItemQuery withModule(String id) {
        return new ItemQuery(courseId, id, statuses, types, support, origin, conceptId, search, flaggedOnly, limit, offset);
    }

    public ItemQuery withStatuses(ItemStatus... values) {
        return new ItemQuery(courseId, moduleId, Set.of(values), types, support, origin, conceptId, search, flaggedOnly, limit, offset);
    }

    public ItemQuery withTypes(ItemType... values) {
        return new ItemQuery(courseId, moduleId, statuses, Set.of(values), support, origin, conceptId, search, flaggedOnly, limit, offset);
    }

    public ItemQuery withSupport(SupportStatus... values) {
        return new ItemQuery(courseId, moduleId, statuses, types, Set.of(values), origin, conceptId, search, flaggedOnly, limit, offset);
    }

    public ItemQuery withOrigin(ItemOrigin value) {
        return new ItemQuery(courseId, moduleId, statuses, types, support, value, conceptId, search, flaggedOnly, limit, offset);
    }

    public ItemQuery withConcept(String id) {
        return new ItemQuery(courseId, moduleId, statuses, types, support, origin, id, search, flaggedOnly, limit, offset);
    }

    public ItemQuery withSearch(String text) {
        return new ItemQuery(courseId, moduleId, statuses, types, support, origin, conceptId, text, flaggedOnly, limit, offset);
    }

    public ItemQuery withFlaggedOnly(boolean value) {
        return new ItemQuery(courseId, moduleId, statuses, types, support, origin, conceptId, search, value, limit, offset);
    }

    public ItemQuery withPage(int newLimit, int newOffset) {
        return new ItemQuery(courseId, moduleId, statuses, types, support, origin, conceptId, search, flaggedOnly, newLimit, newOffset);
    }
}
