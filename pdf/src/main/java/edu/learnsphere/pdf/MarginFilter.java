package edu.learnsphere.pdf;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Removes running headers, running footers and page numbers from a stream of pages.
 *
 * <p>A line near the top or bottom edge is a running header or footer when lines with the same text sit at
 * the same height on most of the pages within {@value #WINDOW} pages either side, or on most pages of the same
 * parity (books alternate left and right headers). How "the same text" is judged depends on how the line looks:
 *
 * <ul>
 *   <li>Lines in the light style of running headers (smaller than the body text, or the body size in regular
 *       weight without a section number) are compared with their digits left out, so "Chapter 3 · 41"
 *       matches "Chapter 3 · 42".</li>
 *   <li>Lines that look like headings (bold or larger than the body text, or starting with a section number
 *       such as "Question 3" or "2.1") must repeat with their numbers, so "Question 3" and "Question 4" at the
 *       top of consecutive pages of a worksheet stay. Only a page number at the start or end of such a line
 *       ("Chapter 3 Cells 41") is left out of the comparison.</li>
 * </ul>
 *
 * <p>The window keeps chapter-specific headers working and lets pages stream: a page is passed on as soon as
 * the {@value #WINDOW} pages after it have arrived. A lone page number ("12", "- 12 -", "Page 12 of 40",
 * "xii") at the very top or bottom is removed even when it does not repeat.
 *
 * <p>Pages must arrive in order without gaps. Not thread-safe.
 */
final class MarginFilter {

    /** Pages on each side of a page that are compared with it. */
    static final int WINDOW = 4;
    /** Share of the page height at the top and at the bottom where running lines are looked for. */
    private static final float ZONE = 0.15f;
    /** Running lines on two pages match when their heights differ by at most this share of the page height. */
    private static final float SAME_HEIGHT = 0.02f;
    private static final Pattern PAGE_NUMBER = Pattern.compile(
            "^(page|p\\.|pg\\.?|seite|p\u00E1gina|pagina)?\\s*[-\u2013\u2014(\\[]?\\s*(\\d{1,4}|[ivxlcdm]{1,7}|[IVXLCDM]{1,7})\\s*[-\u2013\u2014)\\]]?"
                    + "(\\s*(of|/|von|de)\\s*\\d{1,4})?$",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern ROMAN = Pattern.compile(
            "^m{0,3}(cm|cd|d?c{0,3})(xc|xl|l?x{0,3})(ix|iv|v?i{0,3})$", Pattern.CASE_INSENSITIVE);
    /** A page number after the rest of a line ("Chapter 3 Cells 41"). */
    private static final Pattern TRAILING_NUMBER = Pattern.compile("^(.*\\S)\\s+\\d{1,4}$");
    /** A page number before the rest of a line ("41 Chapter 3 Cells"). */
    private static final Pattern LEADING_NUMBER = Pattern.compile("^\\d{1,4}\\s+(\\S.*)$");

    private final Consumer<PageLayout> downstream;
    private final List<Entry> entries = new ArrayList<>();
    private int nextToEmit = -1;

    MarginFilter(Consumer<PageLayout> downstream) {
        this.downstream = Objects.requireNonNull(downstream, "downstream");
    }

    /** Adds the next page; passes on every page that has {@value #WINDOW} pages after it. */
    void accept(PageLayout page) {
        if (nextToEmit < 0) {
            nextToEmit = page.pageNumber();
        }
        entries.add(new Entry(page));
        while (!entries.isEmpty() && page.pageNumber() - nextToEmit >= WINDOW) {
            emit(nextToEmit);
        }
    }

    /** Passes on the remaining pages. */
    void finish() {
        while (nextToEmit >= 0 && entryFor(nextToEmit) != null) {
            emit(nextToEmit);
        }
    }

    private void emit(int pageNumber) {
        Entry entry = entryFor(pageNumber);
        PageLayout filtered = entry == null ? null : filter(entry);
        nextToEmit = pageNumber + 1;
        // The next page compares itself with pages back to WINDOW before it; older ones are no longer needed.
        entries.removeIf(e -> e.page.pageNumber() < nextToEmit - WINDOW);
        if (filtered != null) {
            downstream.accept(filtered);
        }
    }

    private Entry entryFor(int pageNumber) {
        for (Entry entry : entries) {
            if (entry.page.pageNumber() == pageNumber) {
                return entry;
            }
        }
        return null;
    }

    private PageLayout filter(Entry entry) {
        if (entry.candidates.isEmpty()) {
            return entry.page;
        }
        Set<TextLine> remove = Collections.newSetFromMap(new IdentityHashMap<>());
        for (Candidate candidate : entry.candidates) {
            if ((candidate.outermost && isPageNumber(candidate.line.text())) || (candidate.repeatable && repeats(entry, candidate))) {
                remove.add(candidate.line);
            }
        }
        if (remove.isEmpty()) {
            return entry.page;
        }
        List<LayoutBlock> kept = new ArrayList<>();
        for (LayoutBlock block : entry.page.mainBlocks()) {
            List<TextLine> lines = new ArrayList<>();
            for (TextLine line : block.lines()) {
                if (!remove.contains(line)) {
                    lines.add(line);
                }
            }
            if (lines.size() == block.lineCount()) {
                kept.add(block);
            } else if (!lines.isEmpty()) {
                kept.add(new LayoutBlock(lines));
            }
        }
        return entry.page.withMainBlocks(kept);
    }

    private boolean repeats(Entry entry, Candidate candidate) {
        int page = entry.page.pageNumber();
        int others = 0;
        int matches = 0;
        int sameParity = 0;
        int sameParityMatches = 0;
        for (Entry other : entries) {
            int distance = Math.abs(other.page.pageNumber() - page);
            if (distance == 0 || distance > WINDOW) {
                continue;
            }
            boolean parity = distance % 2 == 0;
            boolean match = other.matches(candidate);
            others++;
            if (match) {
                matches++;
            }
            if (parity) {
                sameParity++;
                if (match) {
                    sameParityMatches++;
                }
            }
        }
        if (matches < 2) {
            return false;
        }
        return matches * 2 >= others || (sameParity >= 2 && sameParityMatches * 5 >= sameParity * 3);
    }

    static boolean isPageNumber(String text) {
        String trimmed = text.strip();
        if (trimmed.isEmpty() || trimmed.length() > 24 || !PAGE_NUMBER.matcher(trimmed).matches()) {
            return false;
        }
        String letters = trimmed.replaceAll("(?i)^(page|p\\.|pg\\.?|seite|p\u00E1gina|pagina)", "").replaceAll("[^A-Za-z]", "");
        return letters.isEmpty() || ROMAN.matcher(letters.replaceAll("(?i)(of|von|de)$", "")).matches();
    }

    /**
     * The text that identifies a running line: lower case, single spaces, digits left out. Leaving digits out
     * (rather than masking them) makes "Notes 12" match "Notes" when the page number sits on the same line on
     * some pages and is laid out as a separate line on others, such as two-column pages.
     */
    static String key(String text) {
        StringBuilder out = new StringBuilder(text.length());
        boolean space = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (Character.isWhitespace(c) || Character.isDigit(c)) {
                space |= Character.isWhitespace(c) && out.length() > 0;
                continue;
            }
            if (space) {
                out.append(' ');
                space = false;
            }
            out.append(c);
        }
        return out.toString().toLowerCase(Locale.ROOT);
    }

    /**
     * The text that identifies a heading-like running line: lower case, single spaces, digits kept. A page
     * number at the start or end is left out when the rest still has a number of its own, so "Chapter 3 Cells
     * 41" matches "Chapter 3 Cells 42" while "Question 3" does not match "Question 4".
     */
    static String headingKey(String text) {
        String normalized = text.strip().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
        Matcher trailing = TRAILING_NUMBER.matcher(normalized);
        if (trailing.matches() && hasDigit(trailing.group(1))) {
            return trailing.group(1);
        }
        Matcher leading = LEADING_NUMBER.matcher(normalized);
        if (leading.matches() && hasDigit(leading.group(1))) {
            return leading.group(1);
        }
        return normalized;
    }

    private static boolean hasDigit(String text) {
        for (int i = 0; i < text.length(); i++) {
            if (Character.isDigit(text.charAt(i))) {
                return true;
            }
        }
        return false;
    }

    /**
     * True when a line of type {@code size} (bold or not) with {@code text} looks like a heading rather than a
     * running header: bold or larger than the body text, or at the body size with a section number. Headings
     * are never smaller than the body text, so small bold lines count as running headers.
     *
     * @param bodySize the page's body font size, or 0 when unknown
     */
    static boolean looksLikeHeading(String text, float size, boolean bold, float bodySize) {
        if (bodySize <= 0) {
            return bold || HeadingDetector.startsWithSectionNumber(text);
        }
        if (size < 0.95f * bodySize) {
            return false;
        }
        return bold || size > 1.05f * bodySize || HeadingDetector.startsWithSectionNumber(text);
    }

    /** One page with its candidate running lines. */
    private static final class Entry {
        final PageLayout page;
        final List<Candidate> candidates = new ArrayList<>();

        Entry(PageLayout page) {
            this.page = page;
            float height = page.frameHeight();
            if (height <= 0 || page.mainBlocks().isEmpty()) {
                return;
            }
            List<TextLine> lines = new ArrayList<>();
            SizeHistogram sizes = new SizeHistogram();
            for (LayoutBlock block : page.mainBlocks()) {
                for (TextLine line : block.lines()) {
                    lines.add(line);
                    sizes.add(line.size, line.charCount);
                }
            }
            float bodySize = sizes.mode();
            lines.sort((a, b) -> Float.compare(a.top, b.top));
            for (int i = 0; i < Math.min(3, lines.size()); i++) {
                TextLine line = lines.get(i);
                if (line.baseline > ZONE * height) {
                    break;
                }
                candidates.add(new Candidate(line, true, line.baseline / height, i < 2, bodySize));
            }
            for (int i = lines.size() - 1, n = 0; i >= 0 && n < 3; i--, n++) {
                TextLine line = lines.get(i);
                if (line.top < (1 - ZONE) * height) {
                    break;
                }
                if (!isCandidate(line)) {
                    candidates.add(new Candidate(line, false, line.baseline / height, n < 2, bodySize));
                }
            }
        }

        private boolean isCandidate(TextLine line) {
            for (Candidate candidate : candidates) {
                if (candidate.line == line) {
                    return true;
                }
            }
            return false;
        }

        boolean matches(Candidate wanted) {
            for (Candidate candidate : candidates) {
                if (candidate.top == wanted.top && candidate.repeatable
                        && Math.abs(candidate.position - wanted.position) <= SAME_HEIGHT && candidate.sameText(wanted)) {
                    return true;
                }
            }
            return false;
        }
    }

    /** A line near the top or bottom edge that may be a running header, footer or page number. */
    private static final class Candidate {
        final TextLine line;
        final boolean top;
        final float position;
        final boolean outermost;
        final boolean repeatable;
        final boolean headingLike;
        final String key;
        final String headingKey;

        Candidate(TextLine line, boolean top, float position, boolean outermost, float bodySize) {
            this.line = line;
            this.top = top;
            this.position = position;
            this.outermost = outermost;
            String text = line.text();
            this.key = key(text);
            this.headingKey = headingKey(text);
            this.headingLike = looksLikeHeading(text, line.size, line.bold, bodySize);
            // Large type at the top is a title, not a running header.
            this.repeatable = !key.isEmpty() && text.length() <= 200 && (bodySize <= 0 || line.size <= 1.15f * bodySize);
        }

        /** True when the two lines have the same text, judged as described for the class. */
        boolean sameText(Candidate other) {
            return headingLike || other.headingLike ? headingKey.equals(other.headingKey) : key.equals(other.key);
        }
    }
}
