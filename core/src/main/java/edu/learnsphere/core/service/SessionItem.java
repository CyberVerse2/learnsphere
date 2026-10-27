package edu.learnsphere.core.service;

import edu.learnsphere.core.model.Difficulty;
import edu.learnsphere.core.model.ItemType;
import java.util.List;
import java.util.Objects;

/**
 * One item as the study screen presents it.
 *
 * @param prompt        the question, statement, cloze sentence (with {@code ____}) or flashcard front
 * @param choices       options in display order for multiple choice ({@code "True"}/{@code "False"} values
 *                      {@code "true"}/{@code "false"} for true/false); empty for other types
 * @param flashcardBack the back of a flashcard; null for other types (their answer comes with {@link Feedback})
 * @param conceptNames  concepts it tests
 * @param moduleTitle   its module, or null
 * @param isNew         true the first time the learner sees it
 * @param hintAvailable true when {@link PracticeService#hint} can help
 */
public record SessionItem(String itemId, ItemType type, String prompt, List<String> choices, String flashcardBack,
                          List<String> conceptNames, String moduleTitle, Difficulty difficulty, boolean isNew,
                          boolean hintAvailable) {

    public SessionItem {
        Objects.requireNonNull(itemId, "itemId");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(prompt, "prompt");
        choices = choices == null ? List.of() : List.copyOf(choices);
        conceptNames = conceptNames == null ? List.of() : List.copyOf(conceptNames);
    }
}
