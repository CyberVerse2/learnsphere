package edu.learnsphere.core.spi;

import java.util.List;
import java.util.stream.Collectors;

/**
 * The text of one page as blocks in reading order.
 *
 * @param pageNumber   1-based
 * @param bodyFontSize the most common font size on the page (0 when unknown), so headings can be found
 * @param blocks       blocks in reading order
 */
public record PageText(int pageNumber, float bodyFontSize, List<TextBlock> blocks) {

    public PageText {
        blocks = blocks == null ? List.of() : List.copyOf(blocks);
    }

    /** A page made of plain paragraphs separated by blank lines (for example text read from an image). */
    public static PageText ofPlainText(int pageNumber, String text) {
        List<TextBlock> blocks = text == null || text.isBlank() ? List.of()
                : java.util.Arrays.stream(text.split("\\n\\s*\\n")).map(String::strip).filter(s -> !s.isEmpty())
                        .map(TextBlock::plain).toList();
        return new PageText(pageNumber, 0, blocks);
    }

    /** All block texts joined by blank lines. */
    public String text() {
        return blocks.stream().map(TextBlock::text).collect(Collectors.joining("\n\n"));
    }

    /** Number of non-whitespace characters. */
    public int charCount() {
        int count = 0;
        for (TextBlock block : blocks) {
            for (int i = 0; i < block.text().length(); i++) {
                if (!Character.isWhitespace(block.text().charAt(i))) {
                    count++;
                }
            }
        }
        return count;
    }
}
