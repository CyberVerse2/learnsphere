package edu.learnsphere.core.ask;

import edu.learnsphere.core.model.Citation;
import edu.learnsphere.core.spi.Passage;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Checks the {@code [n]} citation markers in an answer against the passages it was written from.
 *
 * <ul>
 *   <li>Markers whose number is not a passage are removed; groups such as {@code [1, 3]} or ranges such as
 *       {@code [2-4]} are normalized to adjacent single markers {@code [1][3]}.</li>
 *   <li>Each cited passage becomes a {@link Citation} whose snippet is the passage sentence that best matches
 *       the answer sentences citing it (verbatim, so it can be highlighted on the page).</li>
 *   <li>An answer with no valid marker has no citations; the ask pipeline treats it as insufficient evidence.</li>
 * </ul>
 *
 * Pure and thread-safe.
 */
public final class CitationValidator {

    /** Longest snippet kept, in characters. */
    public static final int MAX_SNIPPET = 300;

    private static final Pattern MARKER = Pattern.compile(
            "\\[\\s*(\\d{1,3}(?:\\s*(?:,|;|-|–|—|and)\\s*\\d{1,3})*)\\s*]");
    private static final Pattern SENTENCE_END = Pattern.compile("(?<=[.!?])\\s+(?=[\\p{Lu}\\d\"'(\\[])");
    private static final Pattern WORD = Pattern.compile("[\\p{L}\\p{N}]+");
    private static final Set<String> STOPWORDS = Set.of(
            "the", "and", "for", "are", "but", "not", "you", "all", "any", "can", "had", "her", "was", "one", "our",
            "out", "has", "have", "this", "that", "with", "from", "they", "will", "would", "there", "their", "what",
            "about", "which", "when", "make", "like", "into", "than", "then", "them", "these", "some", "its", "also",
            "been", "were", "such", "only", "more", "most", "other", "each", "how", "who", "does", "did", "is", "it",
            "of", "to", "in", "on", "as", "by", "be", "or", "an", "at", "a");

    private CitationValidator() {
    }

    /**
     * The validated answer.
     *
     * @param text           the answer with invalid markers removed and groups normalized
     * @param citations      one per distinct valid number, in order of first appearance
     * @param invalidNumbers numbers that were cited but are not passages, in order of appearance
     */
    public record Result(String text, List<Citation> citations, List<Integer> invalidNumbers) {

        public Result {
            citations = List.copyOf(citations);
            invalidNumbers = List.copyOf(invalidNumbers);
        }

        /** True when at least one marker refers to a passage. */
        public boolean hasCitations() {
            return !citations.isEmpty();
        }
    }

    /** Validates {@code answer} against {@code passages} (numbered by {@link Passage#number()}). */
    public static Result validate(String answer, List<Passage> passages) {
        String source = answer == null ? "" : answer;
        Map<Integer, Passage> byNumber = new HashMap<>();
        for (Passage passage : passages) {
            byNumber.put(passage.number(), passage);
        }

        StringBuilder text = new StringBuilder();
        Set<Integer> cited = new LinkedHashSet<>();
        List<Integer> invalid = new ArrayList<>();
        Matcher matcher = MARKER.matcher(source);
        int last = 0;
        while (matcher.find()) {
            text.append(source, last, matcher.start());
            List<Integer> valid = new ArrayList<>();
            for (int number : numbers(matcher.group(1))) {
                if (byNumber.containsKey(number)) {
                    if (!valid.contains(number)) {
                        valid.add(number);
                    }
                } else {
                    invalid.add(number);
                }
            }
            if (valid.isEmpty()) {
                trimTrailingSpace(text);
            } else {
                for (int number : valid) {
                    text.append('[').append(number).append(']');
                    cited.add(number);
                }
            }
            last = matcher.end();
        }
        text.append(source.substring(last));
        String cleaned = tidy(text.toString());

        Map<Integer, Set<String>> claimWords = claimWordsByNumber(cleaned);
        List<Citation> citations = new ArrayList<>();
        for (int number : cited) {
            Passage passage = byNumber.get(number);
            String snippet = bestSnippet(passage.text(), claimWords.getOrDefault(number, Set.of()));
            citations.add(new Citation(number, passage.chunkId(), passage.documentId(), passage.documentTitle(),
                    passage.pageStart(), passage.pageEnd(), snippet));
        }
        return new Result(cleaned, citations, invalid);
    }

    /** Numbers in a marker body such as {@code "1, 3"} or {@code "2-4"} (ranges of at most 20). */
    static List<Integer> numbers(String body) {
        List<Integer> result = new ArrayList<>();
        Matcher m = Pattern.compile("(\\d{1,3})(?:\\s*[-–—]\\s*(\\d{1,3}))?").matcher(body);
        while (m.find()) {
            int from = Integer.parseInt(m.group(1));
            int to = m.group(2) == null ? from : Integer.parseInt(m.group(2));
            if (to < from || to - from > 20) {
                to = from;
            }
            for (int n = from; n <= to; n++) {
                result.add(n);
            }
        }
        return result;
    }

    private static void trimTrailingSpace(StringBuilder text) {
        int end = text.length();
        while (end > 0 && (text.charAt(end - 1) == ' ' || text.charAt(end - 1) == '\t')) {
            end--;
        }
        text.setLength(end);
    }

    private static String tidy(String text) {
        return text.replaceAll("[ \\t]{2,}", " ")
                .replaceAll("[ \\t]+([.,;:!?])", "$1")
                .strip();
    }

    /** Content words of the answer sentences that cite each number. */
    private static Map<Integer, Set<String>> claimWordsByNumber(String answer) {
        Map<Integer, Set<String>> result = new LinkedHashMap<>();
        Pattern single = Pattern.compile("\\[(\\d{1,3})]");
        for (String sentence : SENTENCE_END.split(answer)) {
            Matcher m = single.matcher(sentence);
            Set<String> words = null;
            while (m.find()) {
                if (words == null) {
                    words = contentWords(single.matcher(sentence).replaceAll(" "));
                }
                result.computeIfAbsent(Integer.parseInt(m.group(1)), k -> new HashSet<>()).addAll(words);
            }
        }
        return result;
    }

    /** The passage sentence sharing the most content words with the claim (the first sentence on a tie or no overlap). */
    static String bestSnippet(String passageText, Set<String> claimWords) {
        String[] sentences = SENTENCE_END.split(passageText.strip().replaceAll("\\s+", " "));
        int bestIndex = 0;
        int bestScore = 0;
        for (int i = 0; i < sentences.length; i++) {
            Set<String> words = contentWords(sentences[i]);
            words.retainAll(claimWords);
            if (words.size() > bestScore) {
                bestScore = words.size();
                bestIndex = i;
            }
        }
        String snippet = sentences.length == 0 ? "" : sentences[bestIndex].strip();
        if (snippet.length() < 60 && bestIndex + 1 < sentences.length) {
            snippet = snippet + " " + sentences[bestIndex + 1].strip();
        }
        if (snippet.length() > MAX_SNIPPET) {
            int cut = snippet.lastIndexOf(' ', MAX_SNIPPET - 1);
            snippet = snippet.substring(0, cut > MAX_SNIPPET / 2 ? cut : MAX_SNIPPET - 1).strip() + "…";
        }
        return snippet;
    }

    static Set<String> contentWords(String text) {
        Set<String> words = new HashSet<>();
        Matcher m = WORD.matcher(text.toLowerCase(Locale.ROOT));
        while (m.find()) {
            String word = m.group();
            if (word.length() > 2 && !STOPWORDS.contains(word)) {
                words.add(word);
            }
        }
        return words;
    }
}
