package edu.learnsphere.testbench.material;

import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.GregorianCalendar;
import java.util.List;
import java.util.TimeZone;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDDocumentInformation;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;

/**
 * Lays a {@link Chapter} out as an A4 PDF the way a lecturer's word processor would: a running header with a
 * rule, the page number at the foot of each page, a title, bold sans-serif headings, serif body text, bulleted
 * lists with hanging indents, ruled tables with a shaded header row and figures placed as images with italic
 * captions. Every authored page becomes exactly one PDF page; a page whose content does not fit fails with
 * the page named, so the ground truth's page numbers always hold.
 */
public final class ChapterPdf {

    static final PDRectangle PAGE = PDRectangle.A4;
    static final float MARGIN_X = 72;
    static final float BODY_TOP = PAGE.getHeight() - 76;
    static final float BODY_BOTTOM = 70;
    static final float WIDTH = PAGE.getWidth() - 2 * MARGIN_X;

    private static final PDFont SERIF = new PDType1Font(Standard14Fonts.FontName.TIMES_ROMAN);
    private static final PDFont SERIF_ITALIC = new PDType1Font(Standard14Fonts.FontName.TIMES_ITALIC);
    private static final PDFont SANS = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
    private static final PDFont SANS_BOLD = new PDType1Font(Standard14Fonts.FontName.HELVETICA_BOLD);

    private static final float BODY_SIZE = 11;
    private static final float BODY_LEADING = 14.3f;
    private static final float PARAGRAPH_GAP = 7;
    private static final float CELL_SIZE = 9;
    private static final float CELL_LEADING = 11;
    private static final float CELL_PAD_X = 4;
    private static final float CELL_PAD_Y = 3.5f;
    private static final float CAPTION_SIZE = 10;
    private static final float FIGURE_WIDTH = 370;
    private static final Color HEADER_GREY = new Color(0x55, 0x55, 0x55);

    private ChapterPdf() {
    }

    /** Writes {@code chapter} to {@code file}, replacing it. */
    public static void write(Chapter chapter, Path file) throws IOException {
        Path parent = file.toAbsolutePath().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        try (PDDocument document = new PDDocument()) {
            PDDocumentInformation info = document.getDocumentInformation();
            info.setTitle(chapter.title());
            info.setAuthor("LearnSphere testbench");
            info.setSubject(chapter.runningHeader());
            // A fixed date keeps the files identical from run to run.
            Calendar date = new GregorianCalendar(TimeZone.getTimeZone("UTC"));
            date.clear();
            date.set(2026, Calendar.SEPTEMBER, 28, 9, 0, 0);
            info.setCreationDate(date);
            info.setModificationDate(date);
            for (Chapter.Page page : chapter.pages()) {
                PDPage pdfPage = new PDPage(PAGE);
                document.addPage(pdfPage);
                try (PDPageContentStream out = new PDPageContentStream(document, pdfPage)) {
                    new PageWriter(document, out, chapter, page).write();
                }
            }
            document.save(file.toFile());
        }
    }

    /** Lines of {@code text} that fit {@code width} at {@code size}, broken between words. */
    static List<String> wrap(String text, PDFont font, float size, float width) throws IOException {
        List<String> lines = new ArrayList<>();
        StringBuilder line = new StringBuilder();
        for (String word : text.strip().split("\\s+")) {
            String candidate = line.isEmpty() ? word : line + " " + word;
            if (!line.isEmpty() && textWidth(candidate, font, size) > width) {
                lines.add(line.toString());
                line.setLength(0);
                line.append(word);
            } else {
                line.setLength(0);
                line.append(candidate);
            }
        }
        if (!line.isEmpty()) {
            lines.add(line.toString());
        }
        return lines;
    }

    static float textWidth(String text, PDFont font, float size) throws IOException {
        return font.getStringWidth(text) / 1000f * size;
    }

    /** Writes one page; {@code y} is the top of the space still free. */
    private static final class PageWriter {

        private final PDDocument document;
        private final PDPageContentStream out;
        private final Chapter chapter;
        private final Chapter.Page page;
        private float y = BODY_TOP;
        private boolean first = true;

        PageWriter(PDDocument document, PDPageContentStream out, Chapter chapter, Chapter.Page page) {
            this.document = document;
            this.out = out;
            this.chapter = chapter;
            this.page = page;
        }

        void write() throws IOException {
            margins();
            for (Chapter.Block block : page.blocks()) {
                switch (block) {
                    case Chapter.Title title -> title(title);
                    case Chapter.Heading heading -> heading(heading);
                    case Chapter.Paragraph paragraph -> paragraph(paragraph);
                    case Chapter.Bullets bullets -> bullets(bullets);
                    case Chapter.Table table -> table(table);
                    case Chapter.Figure figure -> figure(figure);
                }
                first = false;
            }
        }

        private void margins() throws IOException {
            float top = PAGE.getHeight() - 44;
            out.setNonStrokingColor(HEADER_GREY);
            text(chapter.runningHeader(), SANS, 8.5f, MARGIN_X, top);
            out.setStrokingColor(HEADER_GREY);
            out.setLineWidth(0.4f);
            out.moveTo(MARGIN_X, top - 5);
            out.lineTo(PAGE.getWidth() - MARGIN_X, top - 5);
            out.stroke();
            String number = String.valueOf(page.number());
            text(number, SANS, 9, (PAGE.getWidth() - textWidth(number, SANS, 9)) / 2, 40);
            out.setNonStrokingColor(Color.BLACK);
            out.setStrokingColor(Color.BLACK);
        }

        private void title(Chapter.Title title) throws IOException {
            List<String> lines = wrap(title.text(), SANS_BOLD, 18, WIDTH);
            reserve(lines.size() * 23 + 12, "the title");
            for (String line : lines) {
                text(line, SANS_BOLD, 18, MARGIN_X, y - 18);
                y -= 23;
            }
            y -= 12;
        }

        private void heading(Chapter.Heading heading) throws IOException {
            float size = heading.level() == 1 ? 13.5f : 11f;
            float leading = heading.level() == 1 ? 17 : 14;
            float before = first ? 0 : heading.level() == 1 ? 9 : 6;
            float after = heading.level() == 1 ? 5 : 3;
            List<String> lines = wrap(heading.text(), SANS_BOLD, size, WIDTH);
            // Keep a heading with at least two lines of what follows it.
            reserve(before + lines.size() * leading + after + 2 * BODY_LEADING, "heading \"" + heading.text() + "\"");
            y -= before;
            for (String line : lines) {
                text(line, SANS_BOLD, size, MARGIN_X, y - size);
                y -= leading;
            }
            y -= after;
        }

        private void paragraph(Chapter.Paragraph paragraph) throws IOException {
            List<String> lines = wrap(paragraph.text(), SERIF, BODY_SIZE, WIDTH);
            reserve(lines.size() * BODY_LEADING, "a paragraph starting \"" + start(paragraph.text()) + "\"");
            for (String line : lines) {
                text(line, SERIF, BODY_SIZE, MARGIN_X, y - BODY_SIZE);
                y -= BODY_LEADING;
            }
            y -= PARAGRAPH_GAP;
        }

        private void bullets(Chapter.Bullets bullets) throws IOException {
            float indent = 24;
            for (String item : bullets.items()) {
                List<String> lines = wrap(item, SERIF, BODY_SIZE, WIDTH - indent);
                reserve(lines.size() * BODY_LEADING + 2.5f, "list item \"" + start(item) + "\"");
                text("•", SERIF, BODY_SIZE, MARGIN_X + 10, y - BODY_SIZE);
                for (String line : lines) {
                    text(line, SERIF, BODY_SIZE, MARGIN_X + indent, y - BODY_SIZE);
                    y -= BODY_LEADING;
                }
                y -= 2.5f;
            }
            y -= PARAGRAPH_GAP - 2.5f;
        }

        private void table(Chapter.Table table) throws IOException {
            List<String> caption = wrap(table.caption(), SERIF_ITALIC, CAPTION_SIZE, WIDTH);
            float[] widths = columnWidths(table);
            List<List<List<String>>> cells = new ArrayList<>();
            float height = caption.size() * 12.5f + 4;
            for (int r = 0; r < table.rows().size(); r++) {
                PDFont font = r == 0 ? SANS_BOLD : SANS;
                List<List<String>> row = new ArrayList<>();
                int lines = 1;
                for (int c = 0; c < widths.length; c++) {
                    List<String> wrapped = wrap(table.rows().get(r).get(c), font, CELL_SIZE, widths[c] - 2 * CELL_PAD_X);
                    row.add(wrapped);
                    lines = Math.max(lines, wrapped.size());
                }
                cells.add(row);
                height += lines * CELL_LEADING + 2 * CELL_PAD_Y;
            }
            reserve(height + PARAGRAPH_GAP + 4, "table \"" + table.caption() + "\"");
            out.setNonStrokingColor(Color.BLACK);
            for (String line : caption) {
                text(line, SERIF_ITALIC, CAPTION_SIZE, MARGIN_X, y - CAPTION_SIZE);
                y -= 12.5f;
            }
            y -= 4;
            out.setLineWidth(0.5f);
            for (int r = 0; r < cells.size(); r++) {
                List<List<String>> row = cells.get(r);
                int lines = row.stream().mapToInt(List::size).max().orElse(1);
                float rowHeight = lines * CELL_LEADING + 2 * CELL_PAD_Y;
                if (r == 0) {
                    out.setNonStrokingColor(new Color(0xE6, 0xE6, 0xE6));
                    out.addRect(MARGIN_X, y - rowHeight, WIDTH, rowHeight);
                    out.fill();
                    out.setNonStrokingColor(Color.BLACK);
                }
                float x = MARGIN_X;
                for (int c = 0; c < row.size(); c++) {
                    float lineY = y - CELL_PAD_Y - CELL_SIZE;
                    for (String line : row.get(c)) {
                        text(line, r == 0 ? SANS_BOLD : SANS, CELL_SIZE, x + CELL_PAD_X, lineY);
                        lineY -= CELL_LEADING;
                    }
                    x += widths[c];
                }
                rule(MARGIN_X, y, MARGIN_X + WIDTH, y);
                y -= rowHeight;
            }
            rule(MARGIN_X, y, MARGIN_X + WIDTH, y);
            float x = MARGIN_X;
            float top = y + tableBodyHeight(cells);
            rule(x, top, x, y);
            for (float width : widths) {
                x += width;
                rule(x, top, x, y);
            }
            y -= PARAGRAPH_GAP + 4;
        }

        private float tableBodyHeight(List<List<List<String>>> cells) {
            float height = 0;
            for (List<List<String>> row : cells) {
                int lines = row.stream().mapToInt(List::size).max().orElse(1);
                height += lines * CELL_LEADING + 2 * CELL_PAD_Y;
            }
            return height;
        }

        /**
         * Column widths filling the text width: every column gets at least its longest word, and the rest of
         * the width is shared in proportion to how much more each column's longest cell needs.
         */
        private float[] columnWidths(Chapter.Table table) throws IOException {
            int columns = table.rows().getFirst().size();
            float[] natural = new float[columns];
            float[] minimum = new float[columns];
            for (int r = 0; r < table.rows().size(); r++) {
                PDFont font = r == 0 ? SANS_BOLD : SANS;
                for (int c = 0; c < columns; c++) {
                    String cell = table.rows().get(r).get(c);
                    natural[c] = Math.max(natural[c], textWidth(cell, font, CELL_SIZE) + 2 * CELL_PAD_X + 2);
                    for (String word : cell.split("\\s+")) {
                        minimum[c] = Math.max(minimum[c], textWidth(word, font, CELL_SIZE) + 2 * CELL_PAD_X + 2);
                    }
                }
            }
            float fixed = 0;
            float extra = 0;
            for (int c = 0; c < columns; c++) {
                fixed += minimum[c];
                extra += natural[c] - minimum[c];
            }
            if (fixed > WIDTH) {
                throw new IllegalStateException("Table \"" + table.caption() + "\" is too wide for the page");
            }
            float[] widths = new float[columns];
            for (int c = 0; c < columns; c++) {
                float share = extra <= 0 ? 1f / columns : (natural[c] - minimum[c]) / extra;
                widths[c] = minimum[c] + (WIDTH - fixed) * share;
            }
            return widths;
        }

        private void figure(Chapter.Figure figure) throws IOException {
            BufferedImage art = FigureArt.draw(figure.art());
            float width = FIGURE_WIDTH;
            float height = width * art.getHeight() / art.getWidth();
            List<String> caption = wrap(figure.caption(), SERIF_ITALIC, CAPTION_SIZE, WIDTH - 40);
            reserve(4 + height + 8 + caption.size() * 12.5f + PARAGRAPH_GAP + 4, "figure \"" + figure.caption() + "\"");
            y -= 4;
            PDImageXObject image = LosslessFactory.createFromImage(document, art);
            out.drawImage(image, MARGIN_X + (WIDTH - width) / 2, y - height, width, height);
            y -= height + 8;
            for (String line : caption) {
                text(line, SERIF_ITALIC, CAPTION_SIZE, MARGIN_X + (WIDTH - textWidth(line, SERIF_ITALIC, CAPTION_SIZE)) / 2,
                        y - CAPTION_SIZE);
                y -= 12.5f;
            }
            y -= PARAGRAPH_GAP + 4;
        }

        private void reserve(float height, String what) {
            if (y - height < BODY_BOTTOM) {
                throw new IllegalStateException(String.format(java.util.Locale.ROOT,
                        "Page %d of %s overflows by %.0f pt at %s; move text to the next page", page.number(),
                        chapter.id(), BODY_BOTTOM - (y - height), what));
            }
        }

        private void rule(float x1, float y1, float x2, float y2) throws IOException {
            out.moveTo(x1, y1);
            out.lineTo(x2, y2);
            out.stroke();
        }

        private void text(String text, PDFont font, float size, float x, float baseline) throws IOException {
            out.beginText();
            out.setFont(font, size);
            out.newLineAtOffset(x, baseline);
            out.showText(text);
            out.endText();
        }

        private static String start(String text) {
            return text.length() <= 40 ? text : text.substring(0, 40) + "...";
        }
    }
}
