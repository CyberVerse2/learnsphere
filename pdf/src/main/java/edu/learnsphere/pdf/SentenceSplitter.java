package edu.learnsphere.pdf;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Splits text into sentences. A sentence ends at '.', '!', '?' or '…' (with any closing quotes and brackets)
 * followed by whitespace and something that can start a sentence (a capital, a digit, an opening quote or
 * bracket, or a letter of a script without case); at '。', '！' and '？'; and at every line break. A full stop
 * does not end a sentence after a known abbreviation ("e.g.", "et al.", "St."), after an initial ("J. Watson"),
 * after the number or letter that opens a numbered paragraph ("1. General", "b. Water"), or after a short form
 * that is also an ordinary word when a number follows ("No. 5", "Fig. 3", "Art. 12"; but "The answer is no.").
 */
final class SentenceSplitter {

    /** A sentence and whether a line break came before it in the source. */
    record Sentence(String text, boolean afterLineBreak) {
    }

    /** Abbreviations that are never words on their own. */
    private static final Set<String> ABBREVIATIONS = Set.of(
            "e.g", "i.e", "vs", "cf", "al", "figs", "eq", "eqs", "nos", "vol", "vols", "pp", "ch", "chap",
            "refs", "approx", "ca", "dr", "mr", "mrs", "ms", "prof", "st", "jr", "sr", "inc", "ltd", "co",
            "corp", "dept", "univ", "jan", "feb", "apr", "jun", "jul", "aug", "sep", "sept", "oct", "nov", "dec",
            "resp", "viz", "ed", "eds", "cit", "ibid", "u.s", "u.k", "ph.d", "b.sc", "m.sc", "a.m", "p.m",
            "mt", "ft", "est", "incl", "excl", "nr");
    /** Short forms that are also words or letters, and abbreviations only before a number: "No. 5", "p. 12". */
    private static final Set<String> BEFORE_NUMBER = Set.of("no", "fig", "art", "sec", "ref", "tab", "mar", "p", "op");
    /** The number or letter that opens a numbered paragraph, without its full stop: "1", "b", "iv". */
    private static final Pattern ENUMERATOR = Pattern.compile("\\(?(\\d{1,3}|[a-zA-Z]|[ivxlcIVXLC]{1,5})");

    private SentenceSplitter() {
    }

    static List<Sentence> split(String text) {
        List<Sentence> sentences = new ArrayList<>();
        int start = 0;
        boolean afterLineBreak = false;
        int length = text.length();
        for (int i = 0; i < length; i++) {
            char c = text.charAt(i);
            if (c == '\n') {
                add(text, start, i, afterLineBreak, sentences);
                start = i + 1;
                afterLineBreak = true;
                continue;
            }
            if (c == '\u3002' || c == '\uFF01' || c == '\uFF1F') {
                add(text, start, i + 1, afterLineBreak, sentences);
                start = i + 1;
                afterLineBreak = false;
                continue;
            }
            if (c != '.' && c != '!' && c != '?' && c != '\u2026') {
                continue;
            }
            int end = i + 1;
            while (end < length && isClosing(text.charAt(end))) {
                end++;
            }
            int next = end;
            while (next < length && text.charAt(next) != '\n' && Character.isWhitespace(text.charAt(next))) {
                next++;
            }
            if (next == end && end < length) {
                continue; // "3.14", "e.g.," or "example.com": no space after the stop
            }
            if (next < length && text.charAt(next) != '\n' && !canStartSentence(text.charAt(next))) {
                continue;
            }
            if (c == '.' && (isAbbreviation(text, i, next) || opensNumberedParagraph(text, start, i))) {
                continue;
            }
            add(text, start, end, afterLineBreak, sentences);
            start = end;
            afterLineBreak = false;
            i = end - 1;
        }
        add(text, start, length, afterLineBreak, sentences);
        return sentences;
    }

    private static void add(String text, int from, int to, boolean afterLineBreak, List<Sentence> out) {
        String sentence = text.substring(from, Math.max(from, to)).strip();
        if (!sentence.isEmpty()) {
            out.add(new Sentence(sentence, afterLineBreak && !out.isEmpty()));
        }
    }

    private static boolean isClosing(char c) {
        return c == '"' || c == '\'' || c == ')' || c == ']' || c == '\u201D' || c == '\u2019' || c == '\u00BB';
    }

    private static boolean canStartSentence(char c) {
        if (Character.isUpperCase(c) || Character.isDigit(c) || Character.isTitleCase(c)) {
            return true;
        }
        if (c == '"' || c == '\'' || c == '(' || c == '[' || c == '\u201C' || c == '\u2018' || c == '\u00AB'
                || c == '\u2022' || c == '-' || c == '\u2013') {
            return true;
        }
        return Character.isLetter(c) && !Character.isLowerCase(c);
    }

    /**
     * True when the full stop at {@code dot} ends an abbreviation or an initial; {@code next} is the index of
     * the first character after the stop and the spaces that follow it.
     */
    private static boolean isAbbreviation(String text, int dot, int next) {
        int start = dot;
        while (start > 0 && (Character.isLetter(text.charAt(start - 1)) || text.charAt(start - 1) == '.')) {
            start--;
        }
        if (start == dot || (start > 0 && Character.isDigit(text.charAt(start - 1)))) {
            return false; // no word, or an ordinal such as "1st."
        }
        String word = text.substring(start, dot);
        if (word.length() == 1 && Character.isUpperCase(word.charAt(0))) {
            return true; // an initial, as in "J. Watson"
        }
        String lower = word.toLowerCase(Locale.ROOT);
        if (ABBREVIATIONS.contains(lower)) {
            return true;
        }
        return BEFORE_NUMBER.contains(lower) && next < text.length() && Character.isDigit(text.charAt(next));
    }

    /** True when the sentence so far, from {@code start} to the full stop at {@code dot}, is only "1", "b" or "(iv)". */
    private static boolean opensNumberedParagraph(String text, int start, int dot) {
        int from = start;
        while (from < dot && Character.isWhitespace(text.charAt(from))) {
            from++;
        }
        return dot - from <= 6 && ENUMERATOR.matcher(text).region(from, dot).matches();
    }
}
