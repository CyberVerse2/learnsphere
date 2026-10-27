package edu.learnsphere.core.model;

import edu.learnsphere.core.util.Ids;
import java.util.List;
import java.util.Objects;

/**
 * A row of {@code learning_items}: a quiz question or flashcard. See {@link ItemType} for how
 * {@code prompt}, {@code answer} and {@code distractors} are used per type.
 *
 * @param moduleId      module it belongs to, or null
 * @param explanation   why the answer is right, may be empty
 * @param distractors   wrong options for multiple choice ({@code options_json}); empty for other types
 * @param sourceChunkId passage the item was generated from, or null
 * @param sourcePage    page of that passage, or null
 * @param supportNote   short note from the support check, or null
 */
public record LearningItem(
        String id,
        String courseId,
        String moduleId,
        ItemType type,
        String prompt,
        String answer,
        String explanation,
        List<String> distractors,
        Difficulty difficulty,
        String sourceChunkId,
        Integer sourcePage,
        ItemOrigin origin,
        ItemStatus status,
        SupportStatus support,
        String supportNote,
        long createdAt,
        long updatedAt) {

    public LearningItem {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(courseId, "courseId");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(prompt, "prompt");
        Objects.requireNonNull(answer, "answer");
        explanation = Objects.requireNonNullElse(explanation, "");
        distractors = distractors == null ? List.of() : List.copyOf(distractors);
        Objects.requireNonNull(difficulty, "difficulty");
        Objects.requireNonNull(origin, "origin");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(support, "support");
    }

    /** A new draft item with a fresh id. */
    public static LearningItem draft(String courseId, String moduleId, ItemType type, String prompt, String answer,
                                     String explanation, List<String> distractors, Difficulty difficulty,
                                     String sourceChunkId, Integer sourcePage, ItemOrigin origin, long now) {
        return new LearningItem(Ids.newId(), courseId, moduleId, type, prompt, answer, explanation, distractors, difficulty,
                sourceChunkId, sourcePage, origin, ItemStatus.DRAFT, SupportStatus.UNCHECKED, null, now, now);
    }

    /** A copy with another status. */
    public LearningItem withStatus(ItemStatus newStatus, long now) {
        return new LearningItem(id, courseId, moduleId, type, prompt, answer, explanation, distractors, difficulty,
                sourceChunkId, sourcePage, origin, newStatus, support, supportNote, createdAt, now);
    }

    /** A copy with another support verdict. */
    public LearningItem withSupport(SupportStatus newSupport, String note, long now) {
        return new LearningItem(id, courseId, moduleId, type, prompt, answer, explanation, distractors, difficulty,
                sourceChunkId, sourcePage, origin, status, newSupport, note, createdAt, now);
    }

    /** True when learners can study it. */
    public boolean isApproved() {
        return status == ItemStatus.APPROVED;
    }
}
