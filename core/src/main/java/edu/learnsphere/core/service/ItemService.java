package edu.learnsphere.core.service;

import edu.learnsphere.core.error.InvalidInputException;
import edu.learnsphere.core.error.NotFoundException;
import edu.learnsphere.core.model.Flag;
import edu.learnsphere.core.model.FlagReason;
import edu.learnsphere.core.model.LearningItem;
import java.util.Collection;
import java.util.List;

/**
 * Reviewing and editing learning items, and flags on them. Changes publish
 * {@link edu.learnsphere.core.events.ItemsChanged}. Methods taking an id throw {@link NotFoundException}
 * when it does not exist.
 */
public interface ItemService {

    /** Items matching {@code filter}, in module order. */
    List<ItemSummary> list(ItemFilter filter);

    /**
     * The review queue: drafts of a course (or one module when {@code moduleId} is not null), each with its
     * source passage, concepts and flags, in module order then oldest first, so every card can show the
     * draft beside the passage it came from without another call.
     *
     * @param limit  page size (at least 1)
     * @param offset drafts to skip
     */
    List<ItemDetail> reviewQueue(String courseId, String moduleId, int limit, int offset);

    /**
     * Items matching {@code filter} (any status), each with its source passage, concepts and flags, in the
     * order of {@link #list}: what the Studio's review queue shows when it is filtered.
     */
    default List<ItemDetail> listDetails(ItemFilter filter) {
        return list(filter).stream().map(summary -> get(summary.id())).toList();
    }

    /** Counts per status, plus unsupported drafts and flagged items. */
    ItemCounts counts(String courseId);

    ItemDetail get(String itemId);

    /**
     * Creates a manual item as a draft.
     *
     * @param moduleId the module, or null
     * @throws InvalidInputException when the content is not valid for its type
     */
    LearningItem create(String courseId, String moduleId, ItemContent content);

    /** Replaces an item's content and concept tags. @throws InvalidInputException when not valid for its type */
    LearningItem edit(String itemId, ItemContent content);

    /** Moves an item to another module, or none with {@code null}. */
    void moveToModule(String itemId, String moduleId);

    /** Approves an item so learners can study it. */
    void approve(String itemId);

    /** Approves several items; returns how many changed. */
    int approveAll(Collection<String> itemIds);

    /** Rejects a draft. */
    void reject(String itemId);

    /** Takes an approved item out of study; progress is kept. */
    void retire(String itemId);

    /** Puts a rejected or retired item back in the review queue. */
    void returnToDraft(String itemId);

    /** Deletes an item and its progress. */
    void delete(String itemId);

    /** Reports a problem with an item. */
    Flag addFlag(String itemId, FlagReason reason, String note);

    /** Flags on a course's items, newest first. */
    List<FlagSummary> flags(String courseId, boolean includeResolved);

    void resolveFlag(String flagId);
}
