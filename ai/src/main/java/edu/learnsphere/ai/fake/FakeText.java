package edu.learnsphere.ai.fake;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Small text helpers shared by the fakes. */
final class FakeText {

    private static final Pattern WORD = Pattern.compile("[\\p{L}\\p{N}]+(?:'[\\p{L}]+)?");
    private static final Pattern SENTENCE_END = Pattern.compile("(?<=[.!?])\\s+");
    private static final Set<String> STOPWORDS = Set.of(
            "the", "and", "for", "are", "but", "not", "you", "all", "any", "can", "had", "her", "was", "one", "our",
            "out", "has", "have", "this", "that", "with", "from", "they", "will", "would", "there", "their", "what",
            "about", "which", "when", "make", "like", "into", "than", "then", "them", "these", "some", "its", "also",
            "been", "were", "such", "only", "more", "most", "other", "each", "how", "who", "does", "did", "why",
            "where", "your", "it's", "is", "it", "of", "to", "in", "on", "as", "by", "be", "or", "an", "at", "a",
            "do", "so", "if", "we", "he", "she", "his", "him", "my", "me", "no", "yes", "very", "just", "much",
            "many", "use", "used", "using", "because", "while", "both", "between", "through", "during", "after",
            "before", "over", "under", "again", "same", "own", "should", "could", "may", "might", "must", "shall");

    private FakeText() {
    }

    /** Lowercase words in order of appearance. */
    static List<String> words(String text) {
        List<String> words = new ArrayList<>();
        if (text == null) {
            return words;
        }
        Matcher m = WORD.matcher(text.toLowerCase(Locale.ROOT));
        while (m.find()) {
            words.add(m.group());
        }
        return words;
    }

    /** Lowercase words of three or more letters that are not stopwords, in order of first appearance. */
    static Set<String> contentWords(String text) {
        Set<String> result = new LinkedHashSet<>();
        for (String word : words(text)) {
            if (isContentWord(word)) {
                result.add(word);
            }
        }
        return result;
    }

    static boolean isContentWord(String word) {
        return word.length() >= 3 && !STOPWORDS.contains(word) && !word.chars().allMatch(Character::isDigit);
    }

    /** Sentences of a text, whitespace collapsed; never empty for non-blank text. */
    static List<String> sentences(String text) {
        List<String> result = new ArrayList<>();
        if (text == null || text.isBlank()) {
            return result;
        }
        for (String sentence : SENTENCE_END.split(text.strip().replaceAll("\\s+", " "))) {
            if (!sentence.isBlank()) {
                result.add(sentence.strip());
            }
        }
        return result;
    }

    /** The first sentence of a text, ending with punctuation. */
    static String firstSentence(String text) {
        List<String> sentences = sentences(text);
        String sentence = sentences.isEmpty() ? "" : sentences.getFirst();
        return endWithPeriod(sentence);
    }

    static String endWithPeriod(String sentence) {
        String s = sentence.strip();
        if (s.isEmpty() || ".!?".indexOf(s.charAt(s.length() - 1)) >= 0) {
            return s;
        }
        return s + ".";
    }

    /** Fraction of {@code expected}'s content words that appear in {@code actual} (1 when none are expected). */
    static double coverage(String expected, String actual) {
        Set<String> wanted = contentWords(expected);
        if (wanted.isEmpty()) {
            return 1;
        }
        Set<String> present = contentWords(actual);
        long hits = wanted.stream().filter(present::contains).count();
        return (double) hits / wanted.size();
    }

    /** Capitalizes the first letter. */
    static String capitalize(String text) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        return Character.toUpperCase(text.charAt(0)) + text.substring(1);
    }

    /** FNV-1a 32-bit hash of a string. */
    static int fnv1a(String text) {
        int hash = 0x811c9dc5;
        for (int i = 0; i < text.length(); i++) {
            hash ^= text.charAt(i);
            hash *= 0x01000193;
        }
        return hash;
    }
}
