package edu.learnsphere.core.spi;

import java.util.Objects;

/**
 * A block of text on a page (a heading, paragraph, list item or caption), in reading order.
 *
 * @param text      the block's text with line breaks joined by spaces (hyphenated line ends rejoined)
 * @param lineCount number of lines the block spans
 * @param fontSize  dominant font size in points
 * @param bold      true when most of the block is bold
 * @param bounds    where the block is on the page, or null when unknown (for example image-read text)
 */
public record TextBlock(String text, int lineCount, float fontSize, boolean bold, NormalizedRect bounds) {

    public TextBlock {
        Objects.requireNonNull(text, "text");
    }

    /** A plain paragraph without layout information. */
    public static TextBlock plain(String text) {
        return new TextBlock(text, 1, 0, false, null);
    }
}
