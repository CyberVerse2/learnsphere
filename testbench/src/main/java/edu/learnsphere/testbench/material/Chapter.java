package edu.learnsphere.testbench.material;

import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * A course chapter as authored in a material file ({@link ChapterParser}): fixed pages of blocks. Pages are
 * fixed by the author, so the ground truth can name the page that answers each question.
 *
 * @param id            short name used for files and in the evaluation set ({@code os}, {@code rm})
 * @param title         the chapter title, used as the document title
 * @param runningHeader the line printed at the top of every page
 * @param pages         the pages in order, numbered from 1
 */
public record Chapter(String id, String title, String runningHeader, List<Page> pages) {

    public Chapter {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(title, "title");
        Objects.requireNonNull(runningHeader, "runningHeader");
        pages = List.copyOf(pages);
        for (int i = 0; i < pages.size(); i++) {
            if (pages.get(i).number() != i + 1) {
                throw new IllegalArgumentException("Page " + (i + 1) + " is numbered " + pages.get(i).number());
            }
        }
    }

    /** Page {@code number} (1-based). */
    public Page page(int number) {
        if (number < 1 || number > pages.size()) {
            throw new IllegalArgumentException(id + " has no page " + number);
        }
        return pages.get(number - 1);
    }

    /** Words over every page. */
    public int wordCount() {
        return pages.stream().mapToInt(Page::wordCount).sum();
    }

    /** Blocks of a type over every page. */
    public long count(Class<? extends Block> type) {
        return pages.stream().flatMap(p -> p.blocks().stream()).filter(type::isInstance).count();
    }

    /** One page of blocks. */
    public record Page(int number, List<Block> blocks) {

        public Page {
            blocks = List.copyOf(blocks);
        }

        /**
         * The page's text as a reader would copy it: every block in order, separated by blank lines, without
         * the running header, footer or page number. Tables give one line per row with cells separated by
         * spaces; figures give their caption (the drawing itself has no text layer).
         */
        public String referenceText() {
            return blocks.stream().map(Block::text).collect(Collectors.joining("\n\n"));
        }

        /** Words in {@link #referenceText()}. */
        public int wordCount() {
            String text = referenceText().strip();
            return text.isEmpty() ? 0 : text.split("\\s+").length;
        }
    }

    /** A piece of a page. */
    public sealed interface Block permits Title, Heading, Paragraph, Bullets, Table, Figure {

        /** The block's plain text. */
        String text();
    }

    /** The chapter title at the top of its first page. */
    public record Title(String text) implements Block {
    }

    /** A section heading; level 1 for "3.4 CPU scheduling", level 2 for "3.4.1 First-come, first-served". */
    public record Heading(int level, String text) implements Block {

        public Heading {
            if (level < 1 || level > 2) {
                throw new IllegalArgumentException("Heading level must be 1 or 2: " + level);
            }
        }
    }

    /** A paragraph of running text. */
    public record Paragraph(String text) implements Block {
    }

    /** A bulleted list. */
    public record Bullets(List<String> items) implements Block {

        public Bullets {
            items = List.copyOf(items);
        }

        @Override
        public String text() {
            return String.join("\n", items);
        }
    }

    /**
     * A ruled table with a caption above it.
     *
     * @param rows the header row first, then the body rows; every row has the same number of cells
     */
    public record Table(String caption, List<List<String>> rows) implements Block {

        public Table {
            rows = rows.stream().map(List::copyOf).toList();
            if (rows.size() < 2) {
                throw new IllegalArgumentException("A table needs a header row and at least one body row");
            }
            int columns = rows.getFirst().size();
            if (rows.stream().anyMatch(row -> row.size() != columns)) {
                throw new IllegalArgumentException("Every row of table \"" + caption + "\" needs " + columns + " cells");
            }
        }

        @Override
        public String text() {
            return caption + "\n" + rows.stream().map(row -> String.join(" ", row)).collect(Collectors.joining("\n"));
        }
    }

    /**
     * A drawing placed as an image, with a caption below it.
     *
     * @param art which drawing ({@link FigureArt#draw})
     */
    public record Figure(String art, String caption) implements Block {

        @Override
        public String text() {
            return caption;
        }
    }
}
