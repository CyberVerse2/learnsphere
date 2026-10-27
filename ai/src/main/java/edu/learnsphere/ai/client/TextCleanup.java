package edu.learnsphere.ai.client;

import java.util.Locale;

/** Small text helpers for tidying model output. */
final class TextCleanup {

    private TextCleanup() {
    }

    /** Trims and collapses runs of whitespace into single spaces. */
    static String oneLine(String text) {
        return text == null ? "" : text.replaceAll("\\s+", " ").strip();
    }

    /** One line starting with a capital letter and ending with a full stop when it has no closing punctuation. */
    static String sentence(String text) {
        String s = capitalize(oneLine(text));
        if (s.isEmpty()) {
            return s;
        }
        char last = s.charAt(s.length() - 1);
        return ".!?…\"')".indexOf(last) >= 0 ? s : s + ".";
    }

    /** Lowercase letters and digits only, for comparing texts loosely. */
    static String key(String text) {
        return text == null ? "" : text.toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N}]+", " ").strip();
    }

    /** Capitalizes the first letter. */
    static String capitalize(String text) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        return text.substring(0, 1).toUpperCase(Locale.ROOT) + text.substring(1);
    }
}
