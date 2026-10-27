package edu.learnsphere.core.service;

import edu.learnsphere.core.model.ItemOrigin;
import edu.learnsphere.core.model.ItemStatus;
import edu.learnsphere.core.model.ItemType;
import edu.learnsphere.core.model.SupportStatus;
import java.util.Objects;
import java.util.Set;

/**
 * Which items the Studio lists. Empty sets and nulls do not filter.
 *
 * @param moduleId    only this module
 * @param statuses    any of these statuses
 * @param types       any of these types
 * @param support     any of these support verdicts
 * @param origin      only AI drafts or only manual items
 * @param conceptId   only items tagged with this concept
 * @param search      text in prompt, answer or explanation
 * @param flaggedOnly only items with an open flag
 * @param limit       page size (0 = all)
 * @param offset      items to skip
 */
public record ItemFilter(String courseId, String moduleId, Set<ItemStatus> statuses, Set<ItemType> types,
                         Set<SupportStatus> support, ItemOrigin origin, String conceptId, String search,
                         boolean flaggedOnly, int limit, int offset) {

    public ItemFilter {
        Objects.requireNonNull(courseId, "courseId");
        statuses = statuses == null ? Set.of() : Set.copyOf(statuses);
        types = types == null ? Set.of() : Set.copyOf(types);
        support = support == null ? Set.of() : Set.copyOf(support);
    }

    /** Every item of a course. */
    public static ItemFilter forCourse(String courseId) {
        return new ItemFilter(courseId, null, Set.of(), Set.of(), Set.of(), null, null, null, false, 0, 0);
    }

    /** The review queue: drafts of a course. */
    public static ItemFilter reviewQueue(String courseId) {
        return forCourse(courseId).withStatuses(ItemStatus.DRAFT);
    }

    public ItemFilter withModule(String id) {
        return new ItemFilter(courseId, id, statuses, types, support, origin, conceptId, search, flaggedOnly, limit, offset);
    }

    public ItemFilter withStatuses(ItemStatus... values) {
        return new ItemFilter(courseId, moduleId, Set.of(values), types, support, origin, conceptId, search, flaggedOnly, limit, offset);
    }

    public ItemFilter withTypes(ItemType... values) {
        return new ItemFilter(courseId, moduleId, statuses, Set.of(values), support, origin, conceptId, search, flaggedOnly, limit, offset);
    }

    public ItemFilter withSupport(SupportStatus... values) {
        return new ItemFilter(courseId, moduleId, statuses, types, Set.of(values), origin, conceptId, search, flaggedOnly, limit, offset);
    }

    public ItemFilter withOrigin(ItemOrigin value) {
        return new ItemFilter(courseId, moduleId, statuses, types, support, value, conceptId, search, flaggedOnly, limit, offset);
    }

    public ItemFilter withConcept(String id) {
        return new ItemFilter(courseId, moduleId, statuses, types, support, origin, id, search, flaggedOnly, limit, offset);
    }

    public ItemFilter withSearch(String text) {
        return new ItemFilter(courseId, moduleId, statuses, types, support, origin, conceptId, text, flaggedOnly, limit, offset);
    }

    public ItemFilter withFlaggedOnly(boolean value) {
        return new ItemFilter(courseId, moduleId, statuses, types, support, origin, conceptId, search, value, limit, offset);
    }

    public ItemFilter withPage(int newLimit, int newOffset) {
        return new ItemFilter(courseId, moduleId, statuses, types, support, origin, conceptId, search, flaggedOnly, newLimit, newOffset);
    }
}
