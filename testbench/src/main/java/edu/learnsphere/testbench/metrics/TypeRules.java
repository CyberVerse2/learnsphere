package edu.learnsphere.testbench.metrics;

import edu.learnsphere.core.model.ItemType;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * The content rules every learning item must follow, checked independently of the generator that wrote it:
 *
 * <ul>
 *   <li>every item: a non-blank prompt and answer, and a prompt that stands on its own (it does not mention "the
 *       passage", "the text" or a citation marker such as {@code [2]});</li>
 *   <li>multiple choice: two to four wrong options, all different (ignoring case) and none equal to the answer;</li>
 *   <li>true/false: the answer is {@code true} or {@code false} and the prompt is a statement, not a question;</li>
 *   <li>cloze: exactly one blank ({@value ItemType#CLOZE_BLANK}) and a prompt that does not give the answer away.</li>
 * </ul>
 */
public final class TypeRules {

    private static final Pattern SOURCE_REFERENCE = Pattern.compile(
            "(?i)\\b(the passage|the excerpt|the text above|according to the (text|passage|material))\\b|\\[\\d+]");

    private TypeRules() {
    }

    /** The first rule the item breaks, or empty when it follows all of them. */
    public static Optional<String> violation(ItemType type, String prompt, String answer, List<String> distractors) {
        String p = prompt == null ? "" : prompt.strip();
        String a = answer == null ? "" : answer.strip();
        if (p.isEmpty()) {
            return Optional.of("blank prompt");
        }
        if (a.isEmpty()) {
            return Optional.of("blank answer");
        }
        if (SOURCE_REFERENCE.matcher(p).find()) {
            return Optional.of("prompt refers to its source");
        }
        switch (type) {
            case MCQ -> {
                List<String> options = distractors == null ? List.of() : distractors;
                if (options.size() < 2 || options.size() > 4) {
                    return Optional.of(options.size() + " wrong options");
                }
                Set<String> seen = new HashSet<>();
                seen.add(key(a));
                for (String option : options) {
                    if (option == null || option.isBlank()) {
                        return Optional.of("blank wrong option");
                    }
                    if (!seen.add(key(option))) {
                        return Optional.of("repeated option \"" + option.strip() + "\"");
                    }
                }
            }
            case TRUE_FALSE -> {
                String value = a.toLowerCase(Locale.ROOT);
                if (!value.equals("true") && !value.equals("false")) {
                    return Optional.of("answer \"" + a + "\" is not true or false");
                }
                if (p.endsWith("?")) {
                    return Optional.of("statement is a question");
                }
            }
            case CLOZE -> {
                int blanks = p.split(Pattern.quote(ItemType.CLOZE_BLANK), -1).length - 1;
                if (blanks != 1) {
                    return Optional.of(blanks + " blanks");
                }
                if (key(p).contains(key(a))) {
                    return Optional.of("prompt contains the answer");
                }
            }
            case SHORT_ANSWER, FLASHCARD -> {
                // Any non-blank prompt and answer.
            }
        }
        return Optional.empty();
    }

    private static String key(String text) {
        return text.strip().toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
    }
}
