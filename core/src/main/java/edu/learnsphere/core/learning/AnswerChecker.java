package edu.learnsphere.core.learning;

import edu.learnsphere.core.model.LearningItem;
import java.math.BigDecimal;
import java.text.Normalizer;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Compares a learner's answer with the answer key of a rule-graded item (multiple choice, true/false, cloze).
 * Stateless and thread-safe.
 *
 * <p>Multiple choice compares the chosen option with the correct one, ignoring case and spacing. True/false
 * accepts {@code true}/{@code false} (and {@code t}/{@code f}, {@code yes}/{@code no}). Cloze answers are
 * matched tolerantly, because the learner types them:
 * <ul>
 *   <li>case, accents, punctuation, extra spaces and a leading "the", "a" or "an" are ignored;</li>
 *   <li>numbers match by value ("3.50" = "3.5", "1,000" = "1000");</li>
 *   <li>a plural or singular of the expected word counts ("alleles" for "allele");</li>
 *   <li>in answers of {@value #TYPO_MIN_LETTERS} letters or more, one swapped pair of neighbouring letters, or
 *       one missing or extra letter, counts as a typo. A different letter does not, since that often makes
 *       another term ("afferent" and "efferent", "absorption" and "adsorption").</li>
 * </ul>
 */
public final class AnswerChecker {

    /** Shortest cloze answer, in letters, in which a typo is forgiven. */
    public static final int TYPO_MIN_LETTERS = 8;

    private static final Pattern COMBINING_MARKS = Pattern.compile("\\p{M}+");
    private static final Pattern NOT_WORD = Pattern.compile("[^\\p{L}\\p{N}]+");
    private static final Pattern SPACES = Pattern.compile("\\s+");
    private static final Pattern NUMBER = Pattern.compile("[+-]?(\\d{1,3}(,\\d{3})+|\\d+)(\\.\\d+)?|[+-]?\\.\\d+");

    private AnswerChecker() {
    }

    /**
     * True when {@code answer} is correct for a rule-graded item.
     *
     * @throws IllegalArgumentException for a type that is not rule-graded
     */
    public static boolean isCorrect(LearningItem item, String answer) {
        Objects.requireNonNull(item, "item");
        String given = Objects.requireNonNullElse(answer, "");
        return switch (item.type()) {
            case MCQ -> sameChoice(item.answer(), given);
            case TRUE_FALSE -> sameTruth(item.answer(), given);
            case CLOZE -> clozeMatches(item.answer(), given);
            case SHORT_ANSWER, FLASHCARD -> throw new IllegalArgumentException(item.type() + " is not rule-graded");
        };
    }

    /** True when {@code given} is the option {@code expected}, ignoring case and spacing. */
    public static boolean sameChoice(String expected, String given) {
        String e = squash(expected);
        return !e.isEmpty() && e.equals(squash(given));
    }

    /** True when {@code given} says the same as the {@code "true"}/{@code "false"} key {@code expected}. */
    public static boolean sameTruth(String expected, String given) {
        Optional<Boolean> e = parseTruth(expected);
        return e.isPresent() && e.equals(parseTruth(given));
    }

    /** {@code "true"}/{@code "false"} (and short forms) as a boolean; empty for anything else. */
    public static Optional<Boolean> parseTruth(String text) {
        return switch (squash(text)) {
            case "true", "t", "yes", "y" -> Optional.of(true);
            case "false", "f", "no", "n" -> Optional.of(false);
            default -> Optional.empty();
        };
    }

    /** True when a typed cloze answer matches the missing text (see the class description). */
    public static boolean clozeMatches(String expected, String given) {
        Optional<BigDecimal> expectedNumber = number(expected);
        if (expectedNumber.isPresent()) {
            Optional<BigDecimal> givenNumber = number(given);
            if (givenNumber.isPresent()) {
                return expectedNumber.get().compareTo(givenNumber.get()) == 0;
            }
        }
        String e = normalize(expected);
        String g = normalize(given);
        if (e.isEmpty() || g.isEmpty()) {
            return false;
        }
        if (e.equals(g) || pluralOf(e, g) || pluralOf(g, e)) {
            return true;
        }
        return isTypo(e, g);
    }

    /**
     * Folds text for tolerant comparison: accents removed, lower case, punctuation to spaces, single spaces,
     * no leading article.
     */
    public static String normalize(String text) {
        if (text == null) {
            return "";
        }
        String folded = Normalizer.normalize(text, Normalizer.Form.NFKD);
        folded = COMBINING_MARKS.matcher(folded).replaceAll("");
        folded = folded.toLowerCase(Locale.ROOT);
        folded = NOT_WORD.matcher(folded).replaceAll(" ").strip();
        folded = SPACES.matcher(folded).replaceAll(" ");
        for (String article : new String[] {"the ", "a ", "an "}) {
            if (folded.startsWith(article) && folded.length() > article.length()) {
                folded = folded.substring(article.length());
                break;
            }
        }
        return folded;
    }

    /** Lower case with single spaces, for comparing chosen options. */
    private static String squash(String text) {
        if (text == null) {
            return "";
        }
        String nfkc = Normalizer.normalize(text, Normalizer.Form.NFKC);
        return SPACES.matcher(nfkc.strip()).replaceAll(" ").toLowerCase(Locale.ROOT);
    }

    private static Optional<BigDecimal> number(String text) {
        if (text == null) {
            return Optional.empty();
        }
        String t = text.strip();
        if (!NUMBER.matcher(t).matches()) {
            return Optional.empty();
        }
        try {
            return Optional.of(new BigDecimal(t.replace(",", "")));
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
    }

    /** True when {@code plural} is {@code singular} with "s" or "es" added to its last word. */
    private static boolean pluralOf(String singular, String plural) {
        return plural.equals(singular + "s") || plural.equals(singular + "es");
    }

    /**
     * True when the strings differ by one swap of neighbouring characters, or one inserted or deleted
     * character, the expected answer has enough letters and neither contains digits.
     */
    private static boolean isTypo(String expected, String given) {
        long letters = expected.chars().filter(Character::isLetter).count();
        if (letters < TYPO_MIN_LETTERS || hasDigit(expected) || hasDigit(given)) {
            return false;
        }
        int el = expected.length();
        int gl = given.length();
        if (el == gl) {
            return isAdjacentSwap(expected, given);
        }
        if (Math.abs(el - gl) == 1) {
            String longer = el > gl ? expected : given;
            String shorter = el > gl ? given : expected;
            return isOneDeletion(longer, shorter);
        }
        return false;
    }

    private static boolean isAdjacentSwap(String a, String b) {
        int first = -1;
        for (int i = 0; i < a.length(); i++) {
            if (a.charAt(i) != b.charAt(i)) {
                first = i;
                break;
            }
        }
        if (first < 0 || first + 1 >= a.length()) {
            return false;
        }
        return a.charAt(first) == b.charAt(first + 1) && a.charAt(first + 1) == b.charAt(first)
                && a.substring(first + 2).equals(b.substring(first + 2));
    }

    private static boolean isOneDeletion(String longer, String shorter) {
        int i = 0;
        while (i < shorter.length() && longer.charAt(i) == shorter.charAt(i)) {
            i++;
        }
        return longer.substring(i + 1).equals(shorter.substring(i));
    }

    private static boolean hasDigit(String s) {
        return s.chars().anyMatch(Character::isDigit);
    }
}
