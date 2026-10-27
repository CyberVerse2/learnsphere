package edu.learnsphere.pdf;

import java.util.ArrayList;
import java.util.List;

/**
 * The laid-out text of one page.
 *
 * @param pageNumber  1-based
 * @param geometry    the page's visible area, or null for a page that could not be read
 * @param direction   direction of most of the text (0, 90, 180 or 270)
 * @param mainBlocks  blocks of text in {@code direction}, in reading order
 * @param otherBlocks blocks of text running in other directions (for example a rotated margin note), after
 *                    the main text in reading order
 */
record PageLayout(int pageNumber, PageGeometry geometry, int direction, List<LayoutBlock> mainBlocks,
                  List<LayoutBlock> otherBlocks) {

    PageLayout {
        mainBlocks = List.copyOf(mainBlocks);
        otherBlocks = List.copyOf(otherBlocks);
    }

    /** A page without text. */
    static PageLayout empty(int pageNumber, PageGeometry geometry) {
        return new PageLayout(pageNumber, geometry, 0, List.of(), List.of());
    }

    /** Every block in reading order. */
    List<LayoutBlock> blocks() {
        if (otherBlocks.isEmpty()) {
            return mainBlocks;
        }
        List<LayoutBlock> all = new ArrayList<>(mainBlocks.size() + otherBlocks.size());
        all.addAll(mainBlocks);
        all.addAll(otherBlocks);
        return all;
    }

    /** Height of the frame the main blocks are laid out in. */
    float frameHeight() {
        return geometry == null ? 0 : geometry.frameHeight(direction);
    }

    /** A copy with other main blocks. */
    PageLayout withMainBlocks(List<LayoutBlock> blocks) {
        return new PageLayout(pageNumber, geometry, direction, blocks, otherBlocks);
    }
}
