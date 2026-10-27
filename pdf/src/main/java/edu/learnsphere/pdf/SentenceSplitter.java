package edu.learnsphere.pdf;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Splits text into sentences. A sentence ends at '.', '!', '?' or '…' (with any closing quotes and brackets)
 * followed by whitespace and something that can start a sentence (a capital, a digit, an opening quote or
 * bracket, or a letter of a script without case); at '。', '！' and '？'; and at every line break. A full stop
 * after a known abbreviation ("e.g.", "Fig.", "et al.") or an initial ("J. Watson") does not end a sentence.
 */
final class SentenceSplitter {

    /** A sentence and whether a line break came before it in the source. */
    record Sentence(String text, boolean afterLineBreak) {
    }

    private static final Set<String> ABBREVIATIONS = Set.of(
            "e.g", "i.e", "vs", "cf", "al", "fig", "figs", "eq", "eqs", "no", "nos", "vol", "vols", "pp", "p", "ch", "chap",
            "sec", "ref", "refs", "approx", "ca", "dr", "mr", "mrs", "ms", "prof", "st", "jr", "sr", "inc", "ltd", "co",
            "corp", "dept", "univ", "jan", "feb", "mar", "apr", "jun", "jul", "aug", "sep", "sept", "oct", "nov", "dec",
            "resp", "viz", "ed", "eds", "tab", "op", "cit", "ibid", "u.s", "u.k", "ph.d", "b.sc", "m.sc", "a.m", "p.m",
            "mt", "ft", "est", "incl", "excl", "nr", "art");

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
            if (c == '.' && isAbbreviation(text, i)) {
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

    /** True when the full stop at {@code dot} ends an abbreviation or an initial. */
    private static boolean isAbbreviation(String text, int dot) {
        int start = dot;
        while (start > 0 && (Character.isLetter(text.charAt(start - 1)) || text.charAt(start - 1) == '.')) {
            start--;
        }
        if (start == dot) {
            return false;
        }
        String word = text.substring(start, dot);
        if (word.length() == 1 && Character.isUpperCase(word.charAt(0))) {
            return true; // an initial, as in "J. Watson"
        }
        return ABBREVIATIONS.contains(word.toLowerCase(Locale.ROOT));
    }
}
