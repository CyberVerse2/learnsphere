package edu.learnsphere.pdf;

/**
 * Estimates how many tokens a language model's tokenizer turns a text into, without loading a tokenizer.
 *
 * <p>Calibrated on English prose for byte-pair tokenizers such as Qwen's: a word costs one token per four
 * letters (rounded, at least one), every digit is a token, every punctuation mark is a token, and each
 * Chinese, Japanese or Korean character is a token. English prose comes out at about 1.3 tokens per word.
 * Stateless and thread-safe.
 */
public final class TokenEstimator {

    private TokenEstimator() {
    }

    /** Estimated tokens in {@code text} (0 for null or blank text). */
    public static int estimate(CharSequence text) {
        if (text == null) {
            return 0;
        }
        int tokens = 0;
        int letters = 0;
        for (int i = 0; i < text.length(); ) {
            int codePoint = Character.codePointAt(text, i);
            i += Character.charCount(codePoint);
            if (Character.isLetter(codePoint) && !isCjk(codePoint)) {
                letters++;
                continue;
            }
            tokens += wordTokens(letters);
            letters = 0;
            if (Character.isWhitespace(codePoint) || Character.isSpaceChar(codePoint) || Character.getType(codePoint) == Character.NON_SPACING_MARK) {
                continue;
            }
            tokens++; // digit, punctuation, symbol or CJK character
        }
        return tokens + wordTokens(letters);
    }

    private static int wordTokens(int letters) {
        return letters == 0 ? 0 : Math.max(1, (letters + 2) / 4);
    }

    private static boolean isCjk(int codePoint) {
        if (Character.isIdeographic(codePoint)) {
            return true;
        }
        Character.UnicodeScript script = Character.UnicodeScript.of(codePoint);
        return script == Character.UnicodeScript.HIRAGANA || script == Character.UnicodeScript.KATAKANA
                || script == Character.UnicodeScript.HANGUL;
    }
}
