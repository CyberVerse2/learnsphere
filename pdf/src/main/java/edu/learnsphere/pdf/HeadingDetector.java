package edu.learnsphere.pdf;

import edu.learnsphere.core.spi.TextBlock;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Decides whether a block is a heading, from its type size and weight relative to the document's body text
 * and from numbering such as "3.2", "Chapter 4" or "IV.". Blocks without type information (text read from a
 * scanned page) count as headings when they are Markdown headings ("## Cell membranes") or short numbered
 * lines. Captions ("Figure 3: …", "Table 2. …"), figure descriptions, table-of-contents lines ending in a page
 * number and anything that reads like a sentence are never headings.
 */
final class HeadingDetector {

    private static final int MAX_WORDS = 16;
    private static final int MAX_CHARS = 140;
    private static final Pattern MARKDOWN = Pattern.compile("^#{1,6}\\s+(.+?)\\s*#*$");
    private static final Pattern NUMBERED = Pattern.compile(
            "^((chapter|section|part|unit|lecture|module|lesson|appendix|topic|week)\\s+[\\dIVXLC]+[A-Z]?[.:]?"
                    + "|\\d{1,2}(\\.\\d{1,2}){1,3}\\.?|\\d{1,2}\\.?|[IVXLC]{1,6}\\.|[A-Z]\\.)\\s+\\S",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern MULTI_LEVEL = Pattern.compile("^\\d{1,2}(\\.\\d{1,2}){1,3}\\.?\\s");
    private static final Pattern CHAPTER_WORD = Pattern.compile(
            "^(chapter|section|part|unit|lecture|module|lesson|appendix|topic|week)\\s", Pattern.CASE_INSENSITIVE);
    private static final Pattern CAPTION = Pattern.compile(
            "^(fig(ure)?|table|tab|chart|graph|listing|algorithm|eq(uation)?|exhibit|plate|source|note)s?\\.?\\s*[\\dIVX]",
            Pattern.CASE_INSENSITIVE);
    /** "Membranes ........ 47": a contents line with dot leaders. */
    private static final Pattern DOT_LEADER_ENTRY = Pattern.compile("((\\.\\s?){2,}|\\x{2026})\\s*\\d{1,4}$");
    /**
     * "3.2 Membranes 47": a numbered contents line ending in a page number. In plain body type it is a contents
     * entry; in larger or bold type it is a heading that happens to end in a number ("2 Phase 1").
     */
    private static final Pattern NUMBERED_ENTRY = Pattern.compile("^\\d{1,2}(\\.\\d{1,2}){0,3}\\.?\\s+\\S.*\\s\\d{1,4}$");

    private HeadingDetector() {
    }

    /**
     * The heading text of {@code block} (Markdown markers removed), or null when it is not a heading.
     *
     * @param bodySize the document's body font size, or 0 when unknown
     */
    static String headingText(TextBlock block, float bodySize) {
        String text = block.text().strip();
        if (text.isEmpty()) {
            return null;
        }
        Matcher markdown = MARKDOWN.matcher(text);
        if (markdown.matches() && text.indexOf('\n') < 0) {
            String title = markdown.group(1).strip();
            return title.isEmpty() ? null : title;
        }
        if (text.length() > MAX_CHARS || text.indexOf('\n') >= 0 || words(text) > MAX_WORDS || block.lineCount() > 3) {
            return null;
        }
        if (!hasLetter(text) || CAPTION.matcher(text).find() || text.startsWith("Figure:") || DOT_LEADER_ENTRY.matcher(text).find()) {
            return null;
        }
        boolean emphasised = bodySize > 0 && (block.bold() || block.fontSize() >= bodySize * 1.15f);
        if (!emphasised && NUMBERED_ENTRY.matcher(text).find()) {
            return null;
        }
        char last = text.charAt(text.length() - 1);
        if (last == '.' || last == ',' || last == ';' || last == '!' || last == '?') {
            return null;
        }
        boolean numbered = NUMBERED.matcher(text).find();
        float size = block.fontSize();
        if (bodySize > 0 && size > 0) {
            if (size >= bodySize * 1.15f) {
                return text;
            }
            if (size < bodySize * 0.95f) {
                return null;
            }
            if (block.bold()) {
                return words(text) <= 12 && last != ':' ? text : null;
            }
            // Same size and weight as the body: only a clearly numbered section heading on one line.
            boolean sectionNumber = MULTI_LEVEL.matcher(text).find() || CHAPTER_WORD.matcher(text).find();
            return sectionNumber && block.lineCount() == 1 && words(text) <= 12 && startsTitle(text) ? text : null;
        }
        // No type information: numbered lines that look like titles.
        return numbered && block.lineCount() <= 1 && words(text) <= 12 && last != ':' && startsTitle(text) ? text : null;
    }

    /** True when the first word after the numbering starts with a capital letter. */
    private static boolean startsTitle(String text) {
        int i = 0;
        // skip the numbering token(s)
        Matcher chapter = CHAPTER_WORD.matcher(text);
        if (chapter.find()) {
            i = chapter.end();
            while (i < text.length() && !Character.isWhitespace(text.charAt(i))) {
                i++;
            }
        } else {
            while (i < text.length() && !Character.isWhitespace(text.charAt(i))) {
                i++;
            }
        }
        while (i < text.length() && !Character.isLetter(text.charAt(i))) {
            i++;
        }
        return i < text.length() && !Character.isLowerCase(text.charAt(i));
    }

    private static boolean hasLetter(String text) {
        for (int i = 0; i < text.length(); i++) {
            if (Character.isLetter(text.charAt(i))) {
                return true;
            }
        }
        return false;
    }

    static int words(String text) {
        int count = 0;
        boolean inWord = false;
        for (int i = 0; i < text.length(); i++) {
            boolean letter = !Character.isWhitespace(text.charAt(i));
            if (letter && !inWord) {
                count++;
            }
            inWord = letter;
        }
        return count;
    }
}
