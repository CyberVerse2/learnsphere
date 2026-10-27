package edu.learnsphere.app.ui.studio.review;

import edu.learnsphere.core.model.ItemType;
import edu.learnsphere.core.model.LearningItem;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Finds the sentences of a source passage that carry an item's answer, so the review card can mark them like a
 * highlighter pen, and cuts long passages down to an excerpt around them. Matching is by shared content words
 * (lower case, plurals folded), which is what a reader checking the passage looks for too.
 */
final class SupportingText {

    /** A range of characters, {@code start} inclusive and {@code end} exclusive. */
    record Range(int start, int end) {

        Range {
            if (start < 0 || end < start) {
                throw new IllegalArgumentException("Bad range " + start + "–" + end);
            }
        }

        int length() {
            return end - start;
        }
    }

    /** Share of the evidence words a sentence must contain to count as support. */
    static final double MIN_COVERAGE = 0.4;

    private static final Pattern WORD = Pattern.compile("[\\p{L}\\p{N}]+");
    private static final Set<String> STOPWORDS = Set.of(
            "the", "and", "for", "are", "but", "not", "you", "all", "any", "can", "had", "her", "was", "one", "our",
            "out", "has", "have", "this", "that", "with", "from", "they", "will", "would", "there", "their", "what",
            "about", "which", "when", "make", "like", "into", "than", "then", "them", "these", "some", "its", "also",
            "been", "were", "such", "only", "more", "most", "other", "each", "how", "who", "does", "did", "why",
            "where", "your", "very", "just", "much", "many", "use", "used", "using", "because", "while", "both",
            "between", "through", "during", "after", "before", "over", "under", "again", "same", "own", "should",
            "could", "may", "might", "must", "true", "false", "statement", "matches", "material", "words");

    private SupportingText() {
    }

    /** The passage's sentences that back {@code item}'s answer, or empty when none clearly do. */
    static Optional<Range> find(String passage, LearningItem item) {
        return find(passage, evidence(item.type(), item.prompt(), item.answer()));
    }

    /** The passage's sentences (one, or two neighbours) that share most content words with {@code evidence}. */
    static Optional<Range> find(String passage, String evidence) {
        if (passage == null || passage.isBlank()) {
            return Optional.empty();
        }
        Set<String> terms = terms(evidence);
        if (terms.isEmpty()) {
            return Optional.empty();
        }
        List<Range> sentences = sentences(passage);
        int best = -1;
        int bestMatched = 0;
        for (int i = 0; i < sentences.size(); i++) {
            int matched = matched(terms, terms(slice(passage, sentences.get(i))));
            if (matched > bestMatched) {
                best = i;
                bestMatched = matched;
            }
        }
        if (best < 0 || !enough(bestMatched, terms.size())) {
            return Optional.empty();
        }
        Range range = sentences.get(best);
        Set<String> covered = new LinkedHashSet<>(terms(slice(passage, range)));
        covered.retainAll(terms);
        // A neighbouring sentence joins when it carries at least two more of the answer's words.
        int extraBefore = best > 0 ? added(terms, covered, terms(slice(passage, sentences.get(best - 1)))) : 0;
        int extraAfter = best + 1 < sentences.size() ? added(terms, covered, terms(slice(passage, sentences.get(best + 1)))) : 0;
        if (extraAfter >= 2 && extraAfter >= extraBefore) {
            range = new Range(range.start(), sentences.get(best + 1).end());
        } else if (extraBefore >= 2) {
            range = new Range(sentences.get(best - 1).start(), range.end());
        }
        return Optional.of(range);
    }

    /** The text to look for in the passage: the answer, or the statement for true/false. */
    static String evidence(ItemType type, String prompt, String answer) {
        String p = prompt == null ? "" : prompt;
        String a = answer == null ? "" : answer;
        return switch (type) {
            case TRUE_FALSE -> p;
            case CLOZE -> p.replace(ItemType.CLOZE_BLANK, " " + a + " ");
            case SHORT_ANSWER -> {
                ShortAnswerText parsed = ShortAnswerText.parse(a);
                yield parsed.modelAnswer() + " " + String.join(" ", parsed.keyPoints());
            }
            case MCQ, FLASHCARD -> terms(a).size() < 2 ? a + " " + p : a;
        };
    }

    /**
     * A part of {@code passage} of about {@code maxChars} characters, made of whole sentences, around
     * {@code focus} (or from the start when there is none). The whole passage when it is short enough.
     */
    static Range excerpt(String passage, Range focus, int maxChars) {
        if (passage.length() <= maxChars) {
            return new Range(0, passage.length());
        }
        List<Range> sentences = sentences(passage);
        if (sentences.isEmpty()) {
            return new Range(0, Math.min(passage.length(), maxChars));
        }
        int first = 0;
        if (focus != null) {
            for (int i = 0; i < sentences.size(); i++) {
                if (sentences.get(i).end() > focus.start()) {
                    first = i;
                    break;
                }
            }
        }
        int last = first;
        while (last + 1 < sentences.size() && (focus != null && sentences.get(last).end() < focus.end())) {
            last++;
        }
        // Grow after, then before, while the excerpt stays within the budget.
        boolean grew = true;
        while (grew) {
            grew = false;
            if (last + 1 < sentences.size() && sentences.get(last + 1).end() - sentences.get(first).start() <= maxChars) {
                last++;
                grew = true;
            }
            if (first > 0 && sentences.get(last).end() - sentences.get(first - 1).start() <= maxChars) {
                first--;
                grew = true;
            }
        }
        return new Range(sentences.get(first).start(), sentences.get(last).end());
    }

    /** Sentence ranges, trimmed: a sentence ends after . ! or ? followed by a space, or at a line break. */
    static List<Range> sentences(String text) {
        List<Range> result = new ArrayList<>();
        int start = 0;
        int n = text.length();
        for (int i = 0; i < n; i++) {
            char c = text.charAt(i);
            boolean lineBreak = c == '\n';
            boolean stop = (c == '.' || c == '!' || c == '?') && (i + 1 == n || Character.isWhitespace(text.charAt(i + 1)));
            if (lineBreak || stop) {
                add(result, text, start, lineBreak ? i : i + 1);
                start = i + 1;
            }
        }
        add(result, text, start, n);
        return result;
    }

    private static void add(List<Range> ranges, String text, int from, int to) {
        while (from < to && Character.isWhitespace(text.charAt(from))) {
            from++;
        }
        while (to > from && Character.isWhitespace(text.charAt(to - 1))) {
            to--;
        }
        if (to > from) {
            ranges.add(new Range(from, to));
        }
    }

    /** Content words of {@code text}, lower case with plurals folded. */
    static Set<String> terms(String text) {
        Set<String> terms = new LinkedHashSet<>();
        if (text == null) {
            return terms;
        }
        Matcher m = WORD.matcher(text.toLowerCase(Locale.ROOT));
        while (m.find()) {
            String word = m.group();
            if (word.length() >= 3 && !STOPWORDS.contains(word) && !word.chars().allMatch(Character::isDigit)) {
                terms.add(fold(word));
            }
        }
        return terms;
    }

    private static String fold(String word) {
        if (word.length() > 4 && word.endsWith("ies")) {
            return word.substring(0, word.length() - 3) + "y";
        }
        if (word.length() > 4 && word.endsWith("s") && !word.endsWith("ss")) {
            return word.substring(0, word.length() - 1);
        }
        return word;
    }

    private static boolean enough(int matched, int total) {
        if (total <= 2) {
            return matched == total;
        }
        return matched >= 2 && matched >= MIN_COVERAGE * total;
    }

    private static int matched(Set<String> terms, Set<String> words) {
        int n = 0;
        for (String term : terms) {
            if (words.contains(term)) {
                n++;
            }
        }
        return n;
    }

    private static int added(Set<String> terms, Set<String> covered, Set<String> words) {
        int n = 0;
        for (String word : words) {
            if (terms.contains(word) && !covered.contains(word)) {
                n++;
            }
        }
        return n;
    }

    private static String slice(String text, Range range) {
        return text.substring(range.start(), range.end());
    }
}
