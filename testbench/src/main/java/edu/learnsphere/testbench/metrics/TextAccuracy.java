package edu.learnsphere.testbench.metrics;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * How closely a transcription of a scanned page matches the page's source text.
 *
 * <p>Both texts are first {@link #normalize normalized}: Markdown structure is removed (heading marks, list
 * markers, table pipes and separator rows, emphasis asterisks, code ticks), typographic quotes and dashes become
 * their ASCII forms, and all runs of whitespace become one space. What remains is compared with the Levenshtein
 * edit distance, so every substituted, missing or extra character counts once:
 *
 * <ul>
 *   <li>character accuracy = max(0, 1 - character edits / reference characters), that is 1 - CER;</li>
 *   <li>word accuracy = max(0, 1 - word edits / reference words), that is 1 - WER.</li>
 * </ul>
 */
public final class TextAccuracy {

    private static final Pattern HEADING_MARK = Pattern.compile("(?m)^\\s{0,3}#{1,6}\\s+");
    private static final Pattern LIST_MARKER = Pattern.compile("(?m)^\\s*[-*+•]\\s+");
    private static final Pattern TABLE_SEPARATOR = Pattern.compile("(?m)^\\s*\\|?\\s*:?-{2,}:?\\s*(\\|\\s*:?-{2,}:?\\s*)*\\|?\\s*$");
    private static final Pattern WHITESPACE = Pattern.compile("\\s+");

    private TextAccuracy() {
    }

    /** The text with Markdown structure and typographic variants removed and whitespace collapsed. */
    public static String normalize(String text) {
        if (text == null) {
            return "";
        }
        String result = text.replace(' ', ' ');
        result = TABLE_SEPARATOR.matcher(result).replaceAll("");
        result = HEADING_MARK.matcher(result).replaceAll("");
        result = LIST_MARKER.matcher(result).replaceAll("");
        result = result.replace("|", " ").replace("**", "").replace("*", "").replace("`", "");
        result = result.replace('‘', '\'').replace('’', '\'').replace('“', '"').replace('”', '"')
                .replace('–', '-').replace('—', '-').replace('−', '-').replace("…", "...");
        return WHITESPACE.matcher(result).replaceAll(" ").strip();
    }

    /** 1 - character error rate of {@code hypothesis} against {@code reference}, after normalizing both; in [0, 1]. */
    public static double characterAccuracy(String reference, String hypothesis) {
        String ref = normalize(reference);
        String hyp = normalize(hypothesis);
        if (ref.isEmpty()) {
            return hyp.isEmpty() ? 1 : 0;
        }
        return Math.max(0, 1 - levenshtein(ref, hyp) / (double) ref.length());
    }

    /** 1 - word error rate of {@code hypothesis} against {@code reference}, after normalizing both; in [0, 1]. */
    public static double wordAccuracy(String reference, String hypothesis) {
        List<String> ref = words(normalize(reference));
        List<String> hyp = words(normalize(hypothesis));
        if (ref.isEmpty()) {
            return hyp.isEmpty() ? 1 : 0;
        }
        return Math.max(0, 1 - levenshtein(ref, hyp) / (double) ref.size());
    }

    /** Edit distance between two strings: insertions, deletions and substitutions of single characters. */
    public static int levenshtein(String a, String b) {
        int[] previous = new int[b.length() + 1];
        int[] current = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) {
            previous[j] = j;
        }
        for (int i = 1; i <= a.length(); i++) {
            current[0] = i;
            char ca = a.charAt(i - 1);
            for (int j = 1; j <= b.length(); j++) {
                int cost = ca == b.charAt(j - 1) ? 0 : 1;
                current[j] = Math.min(Math.min(current[j - 1] + 1, previous[j] + 1), previous[j - 1] + cost);
            }
            int[] swap = previous;
            previous = current;
            current = swap;
        }
        return previous[b.length()];
    }

    /** Edit distance between two token sequences. */
    public static <T> int levenshtein(List<T> a, List<T> b) {
        int[] previous = new int[b.size() + 1];
        int[] current = new int[b.size() + 1];
        for (int j = 0; j <= b.size(); j++) {
            previous[j] = j;
        }
        for (int i = 1; i <= a.size(); i++) {
            current[0] = i;
            T ta = a.get(i - 1);
            for (int j = 1; j <= b.size(); j++) {
                int cost = ta.equals(b.get(j - 1)) ? 0 : 1;
                current[j] = Math.min(Math.min(current[j - 1] + 1, previous[j] + 1), previous[j - 1] + cost);
            }
            int[] swap = previous;
            previous = current;
            current = swap;
        }
        return previous[b.size()];
    }

    private static List<String> words(String normalized) {
        List<String> words = new ArrayList<>();
        for (String word : normalized.split(" ")) {
            if (!word.isEmpty()) {
                words.add(word);
            }
        }
        return words;
    }
}
