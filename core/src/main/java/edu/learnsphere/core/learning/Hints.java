package edu.learnsphere.core.learning;

import edu.learnsphere.core.model.LearningItem;
import edu.learnsphere.core.service.Hint;
import edu.learnsphere.core.service.SourceRef;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Hints for session items, and the order multiple-choice options are shown in. Stateless.
 *
 * <ul>
 *   <li>Multiple choice with at least two wrong options: half of the wrong options (at least one) are
 *       hidden.</li>
 *   <li>Cloze: the first letter and the length of the missing text.</li>
 *   <li>True/false and short answers: where in the material the answer is, when the item has a source.</li>
 *   <li>Flashcards: none (the learner turns the card over instead).</li>
 * </ul>
 */
public final class Hints {

    private static final String[] COUNT_WORDS = {"No", "One", "Two", "Three", "Four", "Five", "Six"};

    private Hints() {
    }

    /** True when {@link #hint} has something to offer for {@code item}. */
    public static boolean available(LearningItem item, SourceRef source) {
        return switch (item.type()) {
            case MCQ -> item.distractors().size() >= 2;
            case CLOZE -> item.answer().codePoints().anyMatch(Character::isLetterOrDigit);
            case TRUE_FALSE, SHORT_ANSWER -> source != null;
            case FLASHCARD -> false;
        };
    }

    /**
     * The hint for {@code item}.
     *
     * @param choices the options in the order shown (multiple choice), used to pick which to hide
     * @param source  where the item comes from, or null
     * @throws IllegalStateException when {@link #available} is false
     */
    public static Hint hint(LearningItem item, List<String> choices, SourceRef source) {
        if (!available(item, source)) {
            throw new IllegalStateException("No hint for " + item.type());
        }
        return switch (item.type()) {
            case MCQ -> multipleChoice(item, choices);
            case CLOZE -> new Hint(clozeText(item.answer().strip()), List.of());
            case TRUE_FALSE, SHORT_ANSWER -> new Hint(whereToLook(source), List.of());
            case FLASHCARD -> throw new IllegalStateException("No hint for flashcards");
        };
    }

    private static Hint multipleChoice(LearningItem item, List<String> choices) {
        int hide = Math.max(1, item.distractors().size() / 2);
        List<String> removed = new ArrayList<>(hide);
        List<String> order = choices.isEmpty() ? item.distractors() : choices;
        for (String choice : order) {
            if (removed.size() == hide) {
                break;
            }
            if (!AnswerChecker.sameChoice(item.answer(), choice) && item.distractors().contains(choice)) {
                removed.add(choice);
            }
        }
        String text = (removed.size() == 1 ? "One wrong answer is" : countWord(removed.size()) + " wrong answers are")
                + " hidden.";
        return new Hint(text, removed);
    }

    private static String clozeText(String answer) {
        String first = new String(Character.toChars(answer.codePointAt(0)));
        String[] words = answer.split("\\s+");
        if (words.length > 1) {
            return "It's " + countWord(words.length).toLowerCase(Locale.ROOT) + " words, starting with “"
                    + first + "”.";
        }
        long letters = answer.codePoints().filter(Character::isLetterOrDigit).count();
        return "It starts with “" + first + "” and has " + letters + (letters == 1 ? " letter." : " letters.");
    }

    private static String whereToLook(SourceRef source) {
        String pages = source.pageEnd() > source.pageStart()
                ? "pages " + source.pageStart() + "–" + source.pageEnd()
                : "page " + source.pageStart();
        if (source.documentTitle().isBlank()) {
            return "Look back at " + pages + " of your material.";
        }
        return "Look back at " + pages + " of “" + source.documentTitle() + "”.";
    }

    private static String countWord(int n) {
        return n >= 0 && n < COUNT_WORDS.length ? COUNT_WORDS[n] : String.valueOf(n);
    }
}
