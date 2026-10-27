package edu.learnsphere.testbench.metrics;

import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Checks that an answer mentions the facts a question asks for. Each key term may list alternatives separated
 * by {@code |} ({@code "zero|0"}); a term is present when any alternative appears in the answer, ignoring case,
 * typographic variants and runs of whitespace. Alternatives made only of digits and punctuation must appear as a
 * whole number, so {@code "12"} is not found in {@code "120"}.
 */
public final class KeyTerms {

    private KeyTerms() {
    }

    /** The share of {@code terms} present in {@code answer}; 1 when there are no terms. */
    public static double coverage(List<String> terms, String answer) {
        if (terms.isEmpty()) {
            return 1;
        }
        long found = terms.stream().filter(term -> present(term, answer)).count();
        return found / (double) terms.size();
    }

    /** Whether any alternative of {@code term} appears in {@code text}. */
    public static boolean present(String term, String text) {
        String haystack = simplify(text);
        for (String alternative : term.split("\\|")) {
            String needle = simplify(alternative);
            if (needle.isEmpty()) {
                continue;
            }
            if (needle.matches("[0-9.,]+")) {
                if (Pattern.compile("(?<![0-9.,])" + Pattern.quote(needle) + "(?![0-9]|[.,][0-9])")
                        .matcher(haystack).find()) {
                    return true;
                }
            } else if (haystack.contains(needle)) {
                return true;
            }
        }
        return false;
    }

    private static String simplify(String text) {
        return TextAccuracy.normalize(text == null ? "" : text).toLowerCase(Locale.ROOT);
    }
}
