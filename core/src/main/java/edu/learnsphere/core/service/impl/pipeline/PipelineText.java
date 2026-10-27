package edu.learnsphere.core.service.impl.pipeline;

import edu.learnsphere.core.spi.PageText;
import edu.learnsphere.core.spi.TextBlock;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Small text helpers shared by the pipelines. Pure and thread-safe. */
final class PipelineText {

    /** Font size given to body text of pages without layout information (text read from images). */
    static final float BODY_FONT_SIZE = 10f;

    /** Prefix of a figure description line inside a passage. */
    static final String FIGURE_PREFIX = "Figure: ";

    private static final Pattern HEADING = Pattern.compile("^(#{1,6})\\s+(.+?)\\s*#*\\s*$");
    private static final Pattern LIST_ITEM = Pattern.compile("^\\s*(?:[-*+]|\\d{1,3}[.)])\\s+.*");
    private static final Pattern CITATION_MARKER = Pattern.compile("\\s*\\[\\d{1,3}]");
    private static final Pattern SPACES = Pattern.compile("\\s+");

    private PipelineText() {
    }

    /** Number of non-whitespace characters in {@code text} (0 for null). */
    static int visibleChars(String text) {
        if (text == null) {
            return 0;
        }
        int count = 0;
        for (int i = 0; i < text.length(); i++) {
            if (!Character.isWhitespace(text.charAt(i))) {
                count++;
            }
        }
        return count;
    }

    /**
     * A page transcribed as Markdown, as blocks a heading-aware chunker understands: {@code #} headings become
     * bold blocks larger than the body text, list items their own blocks, other lines are joined into
     * paragraphs separated by blank lines.
     */
    static PageText fromMarkdown(int pageNumber, String markdown) {
        List<TextBlock> blocks = new ArrayList<>();
        StringBuilder paragraph = new StringBuilder();
        int paragraphLines = 0;
        for (String rawLine : (markdown == null ? "" : markdown).split("\\R")) {
            String line = rawLine.strip();
            Matcher heading = HEADING.matcher(line);
            if (line.isEmpty() || heading.matches() || LIST_ITEM.matcher(line).matches()) {
                paragraphLines = flush(blocks, paragraph, paragraphLines);
            }
            if (line.isEmpty()) {
                continue;
            }
            if (heading.matches()) {
                int level = heading.group(1).length();
                float size = BODY_FONT_SIZE * (1.9f - 0.15f * level);
                blocks.add(new TextBlock(heading.group(2), 1, size, true, null));
            } else if (LIST_ITEM.matcher(line).matches()) {
                blocks.add(new TextBlock(line, 1, BODY_FONT_SIZE, false, null));
            } else {
                if (paragraph.length() > 0) {
                    paragraph.append(' ');
                }
                paragraph.append(line);
                paragraphLines++;
            }
        }
        flush(blocks, paragraph, paragraphLines);
        return new PageText(pageNumber, BODY_FONT_SIZE, blocks);
    }

    private static int flush(List<TextBlock> blocks, StringBuilder paragraph, int lines) {
        if (paragraph.length() > 0) {
            blocks.add(new TextBlock(paragraph.toString(), Math.max(1, lines), BODY_FONT_SIZE, false, null));
            paragraph.setLength(0);
        }
        return 0;
    }

    /**
     * {@code page} with one {@code "Figure: …"} block per non-blank line of {@code figureNotes} appended at the
     * end, so the description ends up in the passage covering the page.
     */
    static PageText withFigures(PageText page, String figureNotes) {
        if (figureNotes == null || figureNotes.isBlank()) {
            return page;
        }
        List<TextBlock> blocks = new ArrayList<>(page.blocks());
        float size = page.bodyFontSize() > 0 ? page.bodyFontSize() : BODY_FONT_SIZE;
        for (String note : figureNotes.split("\\R")) {
            String line = note.strip();
            if (!line.isEmpty()) {
                blocks.add(new TextBlock(line.startsWith(FIGURE_PREFIX.strip()) ? line : FIGURE_PREFIX + line, 1, size,
                        false, null));
            }
        }
        return new PageText(page.pageNumber(), page.bodyFontSize(), blocks);
    }

    /** The first non-blank line of {@code text} without citation markers and with spaces collapsed. */
    static String preview(String text, int maxLength) {
        if (text == null) {
            return "";
        }
        String first = "";
        for (String line : text.split("\\R")) {
            if (!line.isBlank()) {
                first = line;
                break;
            }
        }
        String cleaned = SPACES.matcher(withoutCitations(first)).replaceAll(" ").strip();
        return shorten(cleaned, maxLength);
    }

    /** {@code text} without {@code [n]} citation markers. */
    static String withoutCitations(String text) {
        return CITATION_MARKER.matcher(text).replaceAll("");
    }

    /** {@code text} with spaces collapsed, cut at a word boundary to at most {@code maxLength} characters. */
    static String shorten(String text, int maxLength) {
        String collapsed = SPACES.matcher(text == null ? "" : text).replaceAll(" ").strip();
        if (collapsed.length() <= maxLength) {
            return collapsed;
        }
        int cut = collapsed.lastIndexOf(' ', maxLength - 1);
        if (cut < maxLength / 2) {
            cut = maxLength - 1;
        }
        if (Character.isLowSurrogate(collapsed.charAt(cut))) {
            cut--;
        }
        return collapsed.substring(0, cut).stripTrailing() + "…";
    }

    /** Cosine similarity of two vectors of the same length (0 when either is all zeros). */
    static double cosine(float[] a, float[] b) {
        if (a.length != b.length) {
            return 0;
        }
        double dot = 0;
        double normA = 0;
        double normB = 0;
        for (int i = 0; i < a.length; i++) {
            dot += a[i] * b[i];
            normA += a[i] * a[i];
            normB += b[i] * b[i];
        }
        if (normA == 0 || normB == 0) {
            return 0;
        }
        return dot / (Math.sqrt(normA) * Math.sqrt(normB));
    }

    /** Up to {@code limit} elements of {@code items}, evenly spread from first to last, in order. */
    static <T> List<T> evenlySpaced(List<T> items, int limit) {
        if (items.size() <= limit) {
            return List.copyOf(items);
        }
        List<T> picked = new ArrayList<>(limit);
        if (limit == 1) {
            picked.add(items.get(items.size() / 2));
            return picked;
        }
        double step = (items.size() - 1) / (double) (limit - 1);
        int last = -1;
        for (int i = 0; i < limit; i++) {
            int index = (int) Math.round(i * step);
            if (index != last) {
                picked.add(items.get(index));
                last = index;
            }
        }
        return picked;
    }
}
