package edu.learnsphere.pdf;

import edu.learnsphere.core.spi.NormalizedRect;
import edu.learnsphere.core.spi.PageText;
import edu.learnsphere.core.spi.TextBlock;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Turns a laid-out page into the {@link PageText} the rest of the app works with. */
final class PageTextBuilder {

    /** A word with a hyphen inside it, such as "well-known". */
    private static final Pattern HYPHENATED_WORD = Pattern.compile("\\p{L}+(?:[-\u2010]\\p{L}+)+");

    private PageTextBuilder() {
    }

    static PageText build(PageLayout page) {
        List<LayoutBlock> blocks = page.blocks();
        if (blocks.isEmpty()) {
            return new PageText(page.pageNumber(), 0, List.of());
        }
        Set<String> hyphenatedWords = hyphenatedWords(blocks);
        SizeHistogram sizes = new SizeHistogram();
        List<TextBlock> out = new ArrayList<>(blocks.size());
        for (LayoutBlock block : blocks) {
            String text = joinLines(block.lines(), hyphenatedWords);
            if (text.isBlank()) {
                continue;
            }
            for (TextLine line : block.lines()) {
                for (Glyph glyph : line.glyphs) {
                    if (!glyph.space) {
                        sizes.add(glyph.size, Math.max(1, glyph.charCount()));
                    }
                }
            }
            out.add(new TextBlock(text, block.lineCount(), block.size(), block.bold(), bounds(block, page.geometry())));
        }
        return new PageText(page.pageNumber(), sizes.mode(), out);
    }

    /**
     * Joins a block's lines with spaces, rejoining words hyphenated at a line end: "photo-" + "synthesis"
     * becomes "photosynthesis", unless the page spells the word with a hyphen elsewhere ("well-known"), or
     * the next line starts with a capital or digit ("COVID-" + "19"), in which case the hyphen stays.
     */
    static String joinLines(List<TextLine> lines, Set<String> hyphenatedWords) {
        StringBuilder out = new StringBuilder();
        for (TextLine line : lines) {
            String text = line.text();
            if (text.isEmpty()) {
                continue;
            }
            if (out.isEmpty()) {
                out.append(text);
                continue;
            }
            int end = out.length() - 1;
            char last = out.charAt(end);
            if (TextNormalizer.isHyphen(last) && end > 0 && Character.isLetter(out.charAt(end - 1))) {
                char first = text.charAt(0);
                if (last == TextNormalizer.SOFT_HYPHEN) {
                    out.setLength(end);
                } else if (Character.isLowerCase(first)) {
                    String joined = (trailingWord(out) + leadingWord(text)).toLowerCase(Locale.ROOT);
                    if (!hyphenatedWords.contains(joined.replace('\u2010', '-'))) {
                        out.setLength(end);
                    }
                } else if (!Character.isLetterOrDigit(first)) {
                    out.append(' ');
                }
                out.append(text);
                continue;
            }
            if (last == TextNormalizer.SOFT_HYPHEN) {
                out.setLength(end);
            }
            out.append(' ').append(text);
        }
        int soft = out.indexOf(String.valueOf(TextNormalizer.SOFT_HYPHEN));
        return soft < 0 ? out.toString() : out.toString().replace(String.valueOf(TextNormalizer.SOFT_HYPHEN), "");
    }

    /** Hyphenated words that appear inside lines anywhere on the page, in lower case. */
    static Set<String> hyphenatedWords(List<LayoutBlock> blocks) {
        Set<String> words = new HashSet<>();
        for (LayoutBlock block : blocks) {
            for (TextLine line : block.lines()) {
                String text = line.text();
                if (text.indexOf('-') < 0 && text.indexOf('\u2010') < 0) {
                    continue;
                }
                Matcher matcher = HYPHENATED_WORD.matcher(text);
                while (matcher.find()) {
                    words.add(matcher.group().toLowerCase(Locale.ROOT).replace('\u2010', '-'));
                }
            }
        }
        return words;
    }

    /** The letters and hyphens at the end of {@code text}, including its final hyphen. */
    private static String trailingWord(CharSequence text) {
        int start = text.length();
        while (start > 0) {
            char c = text.charAt(start - 1);
            if (!Character.isLetter(c) && !TextNormalizer.isHyphen(c)) {
                break;
            }
            start--;
        }
        return text.subSequence(start, text.length()).toString();
    }

    private static String leadingWord(String text) {
        int end = 0;
        while (end < text.length() && Character.isLetter(text.charAt(end))) {
            end++;
        }
        return text.substring(0, end);
    }

    private static NormalizedRect bounds(LayoutBlock block, PageGeometry geometry) {
        if (geometry == null) {
            return null;
        }
        float left = Float.MAX_VALUE;
        float top = Float.MAX_VALUE;
        float right = -Float.MAX_VALUE;
        float bottom = -Float.MAX_VALUE;
        for (TextLine line : block.lines()) {
            for (Glyph glyph : line.glyphs) {
                if (glyph.space) {
                    continue;
                }
                left = Math.min(left, glyph.boxLeft);
                top = Math.min(top, glyph.boxTop);
                right = Math.max(right, glyph.boxRight);
                bottom = Math.max(bottom, glyph.boxBottom);
            }
        }
        return left == Float.MAX_VALUE ? null : geometry.normalize(left, top, right, bottom);
    }
}
