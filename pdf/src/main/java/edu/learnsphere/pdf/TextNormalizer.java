package edu.learnsphere.pdf;

import java.text.Normalizer;
import java.util.Locale;

/** Character clean-up shared by extraction and passage matching. */
final class TextNormalizer {

    static final char SOFT_HYPHEN = '\u00AD';

    private TextNormalizer() {
    }

    /**
     * The text of one glyph as it should appear in extracted text: ligatures and presentation forms are
     * expanded ("ﬁ" becomes "fi"), control and zero-width characters dropped, and every kind of space becomes
     * a plain space. Soft hyphens are kept so line joining can see them. Returns "" for nothing visible.
     */
    static String glyphText(String unicode) {
        if (unicode == null || unicode.isEmpty()) {
            return "";
        }
        if (isPlainAscii(unicode)) {
            return unicode;
        }
        String text = unicode;
        if (hasPresentationForms(text)) {
            text = Normalizer.normalize(text, Normalizer.Form.NFKC);
        }
        StringBuilder out = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '\t' || c == '\n' || c == '\r' || Character.isSpaceChar(c)) {
                out.append(' ');
            } else if (c == SOFT_HYPHEN) {
                out.append(c);
            } else if (Character.isISOControl(c) || isInvisible(c)) {
                continue;
            } else {
                out.append(c);
            }
        }
        return out.toString();
    }

    /**
     * Appends the matching form of {@code text} to {@code out}: letters and digits only, lower case, accents
     * removed, so "Café", "cafe" and "ca-\nfé" all become "cafe". Callers map matches back to their source by
     * noting {@code out.length()} before and after each call.
     */
    static void appendMatchForm(String text, StringBuilder out) {
        if (isPlainAscii(text)) {
            for (int i = 0; i < text.length(); i++) {
                char c = text.charAt(i);
                if (c >= 'A' && c <= 'Z') {
                    out.append((char) (c + ('a' - 'A')));
                } else if ((c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')) {
                    out.append(c);
                }
            }
            return;
        }
        String decomposed = Normalizer.normalize(text, Normalizer.Form.NFKD).toLowerCase(Locale.ROOT);
        for (int i = 0; i < decomposed.length(); i++) {
            int codePoint = decomposed.codePointAt(i);
            if (Character.isLetterOrDigit(codePoint)) {
                out.appendCodePoint(codePoint);
            }
            if (Character.isSupplementaryCodePoint(codePoint)) {
                i++;
            }
        }
    }

    /** The matching form of {@code text} (see {@link #appendMatchForm}). */
    static String matchForm(String text) {
        StringBuilder out = new StringBuilder(text.length());
        appendMatchForm(text, out);
        return out.toString();
    }

    /** True for '-', '‐' (hyphen) and the soft hyphen, the characters that end a hyphenated line. */
    static boolean isHyphen(char c) {
        return c == '-' || c == '\u2010' || c == SOFT_HYPHEN;
    }

    private static boolean isPlainAscii(String text) {
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c < 0x20 || c > 0x7E) {
                return false;
            }
        }
        return true;
    }

    private static boolean hasPresentationForms(String text) {
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if ((c >= '\uFB00' && c <= '\uFDFF') || (c >= '\uFE70' && c <= '\uFEFF')) {
                return true;
            }
        }
        return false;
    }

    private static boolean isInvisible(char c) {
        return (c >= '\u200B' && c <= '\u200F') || c == '\u2060' || c == '\uFEFF' || c == '\uFFFD' || c == '\uFFFE'
                || (c >= '\u202A' && c <= '\u202E');
    }
}
