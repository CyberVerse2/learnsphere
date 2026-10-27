package edu.learnsphere.core.service.impl;

import edu.learnsphere.core.error.InvalidInputException;
import edu.learnsphere.core.error.UserMessage;
import edu.learnsphere.core.model.ItemType;
import edu.learnsphere.core.model.LearningItem;
import edu.learnsphere.core.service.ItemContent;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Checks item content against the {@link ItemType} conventions and returns it cleaned up (text stripped,
 * true/false answers in lowercase, wrong options only for multiple choice). Every problem is an
 * {@link InvalidInputException} whose message tells the creator what to fix.
 */
final class ItemValidator {

    static final int MAX_PROMPT = 2_000;
    static final int MAX_ANSWER = 4_000;
    static final int MAX_EXPLANATION = 4_000;
    static final int MAX_OPTION = 500;
    static final int MIN_DISTRACTORS = 2;
    static final int MAX_DISTRACTORS = 4;

    static final UserMessage TOO_LONG = UserMessage.of("This text is too long. Shorten it and try again.");
    static final UserMessage TRUE_OR_FALSE = UserMessage.of("Choose whether the statement is true or false.");
    static final UserMessage OPTION_COUNT = UserMessage.of("Add two to four wrong options.");
    static final UserMessage OPTION_BLANK = UserMessage.of("Fill in every wrong option, or remove the empty one.");
    static final UserMessage OPTIONS_DIFFERENT = UserMessage.of("Each option needs to be different from the others.");
    static final UserMessage ONE_BLANK =
            UserMessage.of("Mark exactly one blank in the sentence with " + ItemType.CLOZE_BLANK + ".");

    private ItemValidator() {
    }

    /** The cleaned-up content, or an {@link InvalidInputException} saying what to fix. */
    static ItemContent validate(ItemContent content) {
        ItemType type = content.type();
        String prompt = ServiceSupport.requireText(content.prompt(), MAX_PROMPT, promptRequired(type), TOO_LONG, "prompt");
        String answer = ServiceSupport.requireText(content.answer(), MAX_ANSWER, answerRequired(type), TOO_LONG, "answer");
        String explanation = ServiceSupport.clean(content.explanation());
        if (explanation.length() > MAX_EXPLANATION) {
            throw new InvalidInputException(TOO_LONG, "explanation longer than " + MAX_EXPLANATION + " characters");
        }
        List<String> distractors = List.of();
        switch (type) {
            case MCQ -> distractors = distractors(content.distractors(), answer);
            case TRUE_FALSE -> answer = trueOrFalse(answer);
            case CLOZE -> requireOneBlank(prompt);
            case SHORT_ANSWER, FLASHCARD -> {
                // Prompt and answer are all these need.
            }
        }
        return new ItemContent(type, prompt, answer, explanation, distractors, content.difficulty(), content.conceptIds());
    }

    /** Validates a stored item's content (before approving it). */
    static void validate(LearningItem item) {
        validate(new ItemContent(item.type(), item.prompt(), item.answer(), item.explanation(), item.distractors(),
                item.difficulty(), Set.of()));
    }

    /** True when the stored item's content is valid. */
    static boolean isValid(LearningItem item) {
        try {
            validate(item);
            return true;
        } catch (InvalidInputException e) {
            return false;
        }
    }

    private static List<String> distractors(List<String> raw, String answer) {
        List<String> options = new ArrayList<>();
        for (String option : raw) {
            String clean = ServiceSupport.clean(option);
            if (clean.isEmpty()) {
                throw new InvalidInputException(OPTION_BLANK, "Blank wrong option");
            }
            if (clean.length() > MAX_OPTION) {
                throw new InvalidInputException(TOO_LONG, "wrong option longer than " + MAX_OPTION + " characters");
            }
            options.add(clean);
        }
        if (options.size() < MIN_DISTRACTORS || options.size() > MAX_DISTRACTORS) {
            throw new InvalidInputException(OPTION_COUNT, options.size() + " wrong options");
        }
        Set<String> seen = new HashSet<>();
        seen.add(answer.toLowerCase(Locale.ROOT));
        for (String option : options) {
            if (!seen.add(option.toLowerCase(Locale.ROOT))) {
                throw new InvalidInputException(OPTIONS_DIFFERENT, "Repeated option");
            }
        }
        return options;
    }

    private static String trueOrFalse(String answer) {
        String value = answer.toLowerCase(Locale.ROOT);
        if (!value.equals("true") && !value.equals("false")) {
            throw new InvalidInputException(TRUE_OR_FALSE, "True/false answer is neither");
        }
        return value;
    }

    private static void requireOneBlank(String prompt) {
        int blanks = 0;
        for (int at = prompt.indexOf(ItemType.CLOZE_BLANK); at >= 0;
             at = prompt.indexOf(ItemType.CLOZE_BLANK, at + ItemType.CLOZE_BLANK.length())) {
            blanks++;
        }
        if (blanks != 1) {
            throw new InvalidInputException(ONE_BLANK, blanks + " blanks in a cloze prompt");
        }
    }

    private static UserMessage promptRequired(ItemType type) {
        return UserMessage.of(switch (type) {
            case MCQ, SHORT_ANSWER -> "Write the question.";
            case TRUE_FALSE -> "Write the statement to judge as true or false.";
            case FLASHCARD -> "Write the front of the card.";
            case CLOZE -> "Write the sentence with a blank.";
        });
    }

    private static UserMessage answerRequired(ItemType type) {
        return switch (type) {
            case MCQ -> UserMessage.of("Write the correct answer.");
            case TRUE_FALSE -> TRUE_OR_FALSE;
            case SHORT_ANSWER -> UserMessage.of("Write a model answer.");
            case FLASHCARD -> UserMessage.of("Write the back of the card.");
            case CLOZE -> UserMessage.of("Write the missing words.");
        };
    }
}
