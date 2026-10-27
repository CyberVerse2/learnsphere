package edu.learnsphere.core.spi;

import edu.learnsphere.core.model.Difficulty;
import edu.learnsphere.core.model.ItemType;
import java.util.List;
import java.util.Objects;

/**
 * A learning item proposed by {@link ItemGenerator}, following the {@link ItemType} content conventions.
 *
 * @param distractors    wrong options for multiple choice; empty otherwise
 * @param passageNumber  the {@link Passage#number()} the item is based on
 * @param conceptNames   names of the concepts it tests (matched to stored concepts case-insensitively)
 */
public record ItemDraft(ItemType type, String prompt, String answer, String explanation, List<String> distractors,
                        Difficulty difficulty, int passageNumber, List<String> conceptNames) {

    public ItemDraft {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(prompt, "prompt");
        Objects.requireNonNull(answer, "answer");
        explanation = Objects.requireNonNullElse(explanation, "");
        distractors = distractors == null ? List.of() : List.copyOf(distractors);
        difficulty = Objects.requireNonNullElse(difficulty, Difficulty.MEDIUM);
        conceptNames = conceptNames == null ? List.of() : List.copyOf(conceptNames);
    }
}
