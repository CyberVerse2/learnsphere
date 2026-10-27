package edu.learnsphere.core.service;

import edu.learnsphere.core.model.Difficulty;
import edu.learnsphere.core.model.ItemType;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * The editable content of an item (manual creation and the edit form), following the {@link ItemType}
 * conventions.
 *
 * @param distractors wrong options for multiple choice; ignored for other types
 * @param conceptIds  concepts to tag it with
 */
public record ItemContent(ItemType type, String prompt, String answer, String explanation, List<String> distractors,
                          Difficulty difficulty, Set<String> conceptIds) {

    public ItemContent {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(prompt, "prompt");
        Objects.requireNonNull(answer, "answer");
        explanation = Objects.requireNonNullElse(explanation, "");
        distractors = distractors == null ? List.of() : List.copyOf(distractors);
        difficulty = Objects.requireNonNullElse(difficulty, Difficulty.MEDIUM);
        conceptIds = conceptIds == null ? Set.of() : Set.copyOf(conceptIds);
    }

    /** A flashcard. */
    public static ItemContent flashcard(String front, String back) {
        return new ItemContent(ItemType.FLASHCARD, front, back, "", List.of(), Difficulty.MEDIUM, Set.of());
    }
}
