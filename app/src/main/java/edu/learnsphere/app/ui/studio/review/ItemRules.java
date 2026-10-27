package edu.learnsphere.app.ui.studio.review;

import edu.learnsphere.core.model.ItemType;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * The rules a question must meet before it can be saved, checked field by field while the creator types so
 * the editor can point at what to fix. They match the checks the item service makes when saving, with the
 * same wording, and list problems in the same order, so the first problem here is the one saving would
 * report.
 */
final class ItemRules {

    static final int MAX_PROMPT = 2_000;
    static final int MAX_ANSWER = 4_000;
    static final int MAX_EXPLANATION = 4_000;
    static final int MAX_OPTION = 500;
    static final int MIN_WRONG_OPTIONS = 2;
    static final int MAX_WRONG_OPTIONS = 4;

    static final String TOO_LONG = "This text is too long. Shorten it and try again.";
    static final String TRUE_OR_FALSE = "Choose whether the statement is true or false.";
    static final String OPTION_COUNT = "Add two to four wrong options.";
    static final String OPTION_BLANK = "Fill in every wrong option, or remove the empty one.";
    static final String OPTIONS_DIFFERENT = "Each option needs to be different from the others.";
    static final String ONE_BLANK = "Mark exactly one blank in the sentence with " + ItemType.CLOZE_BLANK + ".";
    static final String MODEL_ANSWER = "Write a model answer.";

    /** The part of the editor a problem is about. */
    enum Field { PROMPT, ANSWER, OPTIONS, EXPLANATION }

    /**
     * One thing to fix.
     *
     * @param option index of the wrong option it is about, or -1
     */
    record Problem(Field field, int option, String message) {

        static Problem of(Field field, String message) {
            return new Problem(field, -1, message);
        }
    }

    private ItemRules() {
    }

    /**
     * Problems with this content, in the order saving would find them; empty when it can be saved. For short
     * answers pass the composed answer ({@link ShortAnswerText#compose()}).
     */
    static List<Problem> check(ItemType type, String prompt, String answer, String explanation, List<String> wrongOptions) {
        List<Problem> problems = new ArrayList<>();
        String p = clean(prompt);
        String a = clean(answer);
        if (p.isEmpty()) {
            problems.add(Problem.of(Field.PROMPT, promptRequired(type)));
        } else if (p.length() > MAX_PROMPT) {
            problems.add(Problem.of(Field.PROMPT, TOO_LONG));
        }
        if (type == ItemType.SHORT_ANSWER && ShortAnswerText.parse(a).modelAnswer().isEmpty()) {
            problems.add(Problem.of(Field.ANSWER, MODEL_ANSWER));
        } else if (a.isEmpty()) {
            problems.add(Problem.of(Field.ANSWER, answerRequired(type)));
        } else if (a.length() > MAX_ANSWER) {
            problems.add(Problem.of(Field.ANSWER, TOO_LONG));
        }
        if (clean(explanation).length() > MAX_EXPLANATION) {
            problems.add(Problem.of(Field.EXPLANATION, TOO_LONG));
        }
        switch (type) {
            case MCQ -> checkOptions(wrongOptions, a, problems);
            case TRUE_FALSE -> {
                if (!a.isEmpty() && !a.equalsIgnoreCase("true") && !a.equalsIgnoreCase("false")) {
                    problems.add(Problem.of(Field.ANSWER, TRUE_OR_FALSE));
                }
            }
            case CLOZE -> {
                if (!p.isEmpty() && blanks(p) != 1) {
                    problems.add(Problem.of(Field.PROMPT, ONE_BLANK));
                }
            }
            case SHORT_ANSWER, FLASHCARD -> {
                // The question and the answer are all these need.
            }
        }
        return List.copyOf(problems);
    }

    /** The first problem with {@code field}, or null. */
    static Problem first(List<Problem> problems, Field field) {
        for (Problem problem : problems) {
            if (problem.field() == field) {
                return problem;
            }
        }
        return null;
    }

    /** How many {@value ItemType#CLOZE_BLANK} blanks the sentence has. */
    static int blanks(String sentence) {
        int blanks = 0;
        for (int at = sentence.indexOf(ItemType.CLOZE_BLANK); at >= 0;
             at = sentence.indexOf(ItemType.CLOZE_BLANK, at + ItemType.CLOZE_BLANK.length())) {
            blanks++;
        }
        return blanks;
    }

    static String promptRequired(ItemType type) {
        return switch (type) {
            case MCQ, SHORT_ANSWER -> "Write the question.";
            case TRUE_FALSE -> "Write the statement to judge as true or false.";
            case FLASHCARD -> "Write the front of the card.";
            case CLOZE -> "Write the sentence with a blank.";
        };
    }

    static String answerRequired(ItemType type) {
        return switch (type) {
            case MCQ -> "Write the correct answer.";
            case TRUE_FALSE -> TRUE_OR_FALSE;
            case SHORT_ANSWER -> MODEL_ANSWER;
            case FLASHCARD -> "Write the back of the card.";
            case CLOZE -> "Write the missing words.";
        };
    }

    private static void checkOptions(List<String> raw, String answer, List<Problem> problems) {
        List<String> options = raw == null ? List.of() : raw;
        for (int i = 0; i < options.size(); i++) {
            String option = clean(options.get(i));
            if (option.isEmpty()) {
                problems.add(new Problem(Field.OPTIONS, i, OPTION_BLANK));
            } else if (option.length() > MAX_OPTION) {
                problems.add(new Problem(Field.OPTIONS, i, TOO_LONG));
            }
        }
        if (options.size() < MIN_WRONG_OPTIONS || options.size() > MAX_WRONG_OPTIONS) {
            problems.add(Problem.of(Field.OPTIONS, OPTION_COUNT));
        }
        Set<String> seen = new HashSet<>();
        if (!answer.isEmpty()) {
            seen.add(answer.toLowerCase(Locale.ROOT));
        }
        for (int i = 0; i < options.size(); i++) {
            String option = clean(options.get(i));
            if (!option.isEmpty() && !seen.add(option.toLowerCase(Locale.ROOT))) {
                problems.add(new Problem(Field.OPTIONS, i, OPTIONS_DIFFERENT));
                break;
            }
        }
    }

    private static String clean(String value) {
        return value == null ? "" : value.strip();
    }
}
