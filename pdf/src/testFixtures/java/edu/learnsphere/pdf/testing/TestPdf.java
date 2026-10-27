package edu.learnsphere.pdf.testing;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Random;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.encryption.AccessPermission;
import org.apache.pdfbox.pdmodel.encryption.StandardProtectionPolicy;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.font.PDType0Font;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.apache.pdfbox.pdmodel.graphics.state.RenderingMode;

/**
 * Writes realistic PDFs for tests: flowing text in one or two columns with a title, headings, paragraphs,
 * bulleted and numbered lists and two-column glossary tables laid out the way word processors do, running
 * headers and footers and page numbers, plus scanned-looking image pages, password-protected files and damaged
 * files. Text uses the standard PDF fonts (Helvetica, Times), which PDF readers supply themselves, or with
 * {@link Builder#embeddedFonts()} a TrueType font embedded in the file as most real documents do. Only ASCII
 * text is supported.
 *
 * <pre>{@code
 * Path pdf = TestPdf.builder()
 *         .runningHeader("Cell Biology")
 *         .pageNumbers()
 *         .title("Cells")
 *         .heading("1 Membranes")
 *         .paragraph(TestPdf.sentences(1, 12))
 *         .write(dir.resolve("cells.pdf"));
 * }</pre>
 */
public final class TestPdf {

    /** US Letter width in points. */
    public static final float LETTER_WIDTH = 612;
    /** US Letter height in points. */
    public static final float LETTER_HEIGHT = 792;
    /** Where list markers sit, from the column's left edge: a quarter inch, as word processors place them. */
    public static final float LIST_MARKER_INDENT = 18;
    /** Where list item text sits, from the column's left edge: half an inch, as word processors place it. */
    public static final float LIST_TEXT_INDENT = 36;
    /** The TrueType font that PDFBox ships, embedded by {@link Builder#embeddedFonts()}. */
    private static final String EMBEDDED_FONT = "/org/apache/pdfbox/resources/ttf/LiberationSans-Regular.ttf";

    private static final String[] WORDS = ("cell membrane protein lipid nucleus ribosome energy molecule enzyme "
            + "transport gradient signal receptor organelle cytoplasm mitochondria chloroplast genome sequence "
            + "structure function pathway reaction substrate binding channel vesicle division growth tissue "
            + "organism species population evolution selection variation inheritance expression regulation "
            + "the of and to in is that by with from as on are which into across within during after before "
            + "each many several most often rapidly slowly directly carefully important complex simple stable "
            + "active passive internal external primary secondary small large specific general").split(" ");

    private TestPdf() {
    }

    /** A new document builder. */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Deterministic filler prose: {@code count} sentences of 8 to 20 words, each starting with a capital and
     * ending with a full stop, separated by single spaces.
     */
    public static String sentences(long seed, int count) {
        Random random = new Random(seed);
        StringBuilder text = new StringBuilder();
        for (int s = 0; s < count; s++) {
            int words = 8 + random.nextInt(13);
            for (int w = 0; w < words; w++) {
                String word = WORDS[random.nextInt(WORDS.length)];
                if (w == 0) {
                    word = Character.toUpperCase(word.charAt(0)) + word.substring(1);
                    if (!text.isEmpty()) {
                        text.append(' ');
                    }
                } else {
                    text.append(' ');
                }
                text.append(word);
            }
            text.append('.');
        }
        return text.toString();
    }

    /**
     * Writes a copy of {@code source} that needs {@code userPassword} to open (and an owner password to
     * change). With an empty user password the copy opens freely but carries copy and print restrictions.
     */
    public static Path encrypt(Path source, Path target, String userPassword) throws IOException {
        try (PDDocument document = Loader.loadPDF(source.toFile())) {
            AccessPermission permissions = new AccessPermission();
            if (userPassword.isEmpty()) {
                permissions.setCanExtractContent(false);
                permissions.setCanPrint(false);
            }
            StandardProtectionPolicy policy = new StandardProtectionPolicy("owner-secret", userPassword, permissions);
            policy.setEncryptionKeyLength(128);
            document.protect(policy);
            document.save(target.toFile());
        }
        return target;
    }

    /** Bytes that start like a PDF but are not one. */
    public static byte[] damagedBytes() {
        StringBuilder junk = new StringBuilder("%PDF-1.7\n%\u00e2\u00e3\n");
        Random random = new Random(7);
        for (int i = 0; i < 2_000; i++) {
            junk.append((char) ('!' + random.nextInt(90)));
            if (i % 70 == 0) {
                junk.append('\n');
            }
        }
        return junk.toString().getBytes(StandardCharsets.ISO_8859_1);
    }

    /** Builds a document element by element; pages fill and break automatically. */
    public static final class Builder {

        private float width = LETTER_WIDTH;
        private float height = LETTER_HEIGHT;
        private float margin = 72;
        private float bodySize = 11;
        private String header;
        private String footer;
        private boolean pageNumbers;
        private boolean headerOnFirstPage;
        private boolean embeddedFonts;
        private final List<Element> elements = new ArrayList<>();

        private Builder() {
        }

        /** Page size in points (default US Letter). */
        public Builder pageSize(float pageWidth, float pageHeight) {
            this.width = pageWidth;
            this.height = pageHeight;
            return this;
        }

        /** Margin on all sides in points (default 72). */
        public Builder margins(float points) {
            this.margin = points;
            return this;
        }

        /** Body text size in points (default 11). */
        public Builder bodySize(float points) {
            this.bodySize = points;
            return this;
        }

        /** Text in small italics at the top of every page (except the first unless {@link #headerOnFirstPage}). */
        public Builder runningHeader(String text) {
            this.header = requireAscii(text);
            return this;
        }

        /** Text in small italics at the bottom left of every page. */
        public Builder runningFooter(String text) {
            this.footer = requireAscii(text);
            return this;
        }

        /** The page number, centred at the bottom of every text page. */
        public Builder pageNumbers() {
            this.pageNumbers = true;
            return this;
        }

        /** Whether the running header also appears on the first page (default no). */
        public Builder headerOnFirstPage(boolean value) {
            this.headerOnFirstPage = value;
            return this;
        }

        /**
         * Sets all text in a TrueType font embedded in the file (as a subset with its own character map, the way
         * most word processors write PDFs) instead of the standard PDF fonts. Bold text is drawn filled and
         * outlined, as PDF writers do for fonts without a bold face.
         */
        public Builder embeddedFonts() {
            this.embeddedFonts = true;
            return this;
        }

        /** A large bold title. */
        public Builder title(String text) {
            elements.add(new Element(Kind.TEXT, requireAscii(text), Style.BOLD, 20, 0.6f, 0.9f, 0, 1));
            return this;
        }

        /** A bold section heading. */
        public Builder heading(String text) {
            elements.add(new Element(Kind.TEXT, requireAscii(text), Style.BOLD, 15, 1.0f, 0.5f, 0, 1));
            return this;
        }

        /** A bold subsection heading, a little larger than body text. */
        public Builder subheading(String text) {
            elements.add(new Element(Kind.TEXT, requireAscii(text), Style.BOLD, 12.5f, 0.8f, 0.4f, 0, 1));
            return this;
        }

        /** A body paragraph, wrapped to the column; a '\n' in the text forces a line break there. */
        public Builder paragraph(String text) {
            elements.add(new Element(Kind.TEXT, requireAscii(text), Style.REGULAR, 0, 0, 0.6f, 0, 1));
            return this;
        }

        /**
         * A bulleted list as word processors lay it out: a bullet at {@link #LIST_MARKER_INDENT} and the item's
         * text at {@link #LIST_TEXT_INDENT}, with wrapped lines aligned with the text.
         */
        public Builder bulletList(String... items) {
            return list(false, items);
        }

        /** A numbered list ("1.", "2.", …) laid out like {@link #bulletList}. */
        public Builder numberedList(String... items) {
            return list(true, items);
        }

        private Builder list(boolean numbered, String... items) {
            for (int i = 0; i < items.length; i++) {
                String marker = numbered ? (i + 1) + "." : "\u2022";
                float after = i + 1 == items.length ? 0.6f : 0.2f;
                elements.add(new Element(Kind.ROW, requireAscii(items[i]), Style.REGULAR, 0, i == 0 ? 0.3f : 0, after, 0, 1,
                        marker, LIST_MARKER_INDENT, LIST_TEXT_INDENT));
            }
            return this;
        }

        /**
         * A borderless two-column table of terms and their definitions: terms at the column's left edge,
         * definitions starting {@code definitionIndent} points to the right; long cells wrap within their
         * column.
         *
         * @param termsAndDefinitions term, definition, term, definition, …
         */
        public Builder glossary(float definitionIndent, String... termsAndDefinitions) {
            if (termsAndDefinitions.length % 2 != 0) {
                throw new IllegalArgumentException("Terms and definitions come in pairs");
            }
            for (int i = 0; i < termsAndDefinitions.length; i += 2) {
                float after = i + 2 == termsAndDefinitions.length ? 0.6f : 0.3f;
                elements.add(new Element(Kind.ROW, requireAscii(termsAndDefinitions[i + 1]), Style.REGULAR, 0, i == 0 ? 0.3f : 0,
                        after, 0, 1, requireAscii(termsAndDefinitions[i]), 0, definitionIndent));
            }
            return this;
        }

        /** Starts a new page laid out in {@code count} columns (1 or 2). */
        public Builder columns(int count) {
            if (count < 1 || count > 2) {
                throw new IllegalArgumentException("1 or 2 columns: " + count);
            }
            elements.add(new Element(Kind.COLUMNS, "", null, 0, 0, 0, count, 1));
            return this;
        }

        /** Starts a new page. */
        public Builder pageBreak() {
            elements.add(new Element(Kind.PAGE_BREAK, "", null, 0, 0, 0, 0, 1));
            return this;
        }

        /** Starts a new page whose display is turned clockwise by {@code degrees} (0, 90, 180 or 270). */
        public Builder rotatedPage(int degrees) {
            elements.add(new Element(Kind.ROTATION, "", null, 0, 0, 0, degrees, 1));
            return this;
        }

        /**
         * A page that is one picture of text-like marks and has no text layer, as a scanner makes. The
         * picture covers {@code coverage} (0–1) of the page area, centred.
         */
        public Builder imagePage(double coverage) {
            elements.add(new Element(Kind.IMAGE_PAGE, "", null, 0, 0, 0, 0, (float) coverage));
            return this;
        }

        /** Writes the document to {@code file} and returns it. */
        public Path write(Path file) throws IOException {
            Files.write(file, bytes());
            return file;
        }

        /** The document as bytes. */
        public byte[] bytes() throws IOException {
            try (PDDocument document = new PDDocument()) {
                new Typesetter(this, document).run();
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                document.save(out);
                return out.toByteArray();
            }
        }
    }

    private enum Kind { TEXT, ROW, COLUMNS, PAGE_BREAK, ROTATION, IMAGE_PAGE }

    private enum Style { REGULAR, BOLD }

    /**
     * One element; {@code size} 0 means the body size. A {@link Kind#ROW} (list item or table row) shows
     * {@code label} at {@code labelX} and {@code text} at {@code textX}, both relative to the column's left edge.
     */
    private record Element(Kind kind, String text, Style style, float size, float spaceBefore, float spaceAfter, int value,
                           float coverage, String label, float labelX, float textX) {

        Element(Kind kind, String text, Style style, float size, float spaceBefore, float spaceAfter, int value, float coverage) {
            this(kind, text, style, size, spaceBefore, spaceAfter, value, coverage, "", 0, 0);
        }
    }

    /** Lays elements out onto pages. */
    private static final class Typesetter {
        private static final float GUTTER = 24;
        private static final float LEADING = 1.3f;

        private final Builder spec;
        private final PDDocument document;
        private final PDFont regular;
        private final PDFont bold;
        private final PDFont italic;
        private final PDFont sans;
        private PDPage page;
        private PDPageContentStream content;
        private int columns = 1;
        private int column;
        private int rotation;
        private float y;
        private boolean columnEmpty = true;

        Typesetter(Builder spec, PDDocument document) throws IOException {
            this.spec = spec;
            this.document = document;
            if (spec.embeddedFonts) {
                PDFont embedded;
                try (InputStream font = TestPdf.class.getResourceAsStream(EMBEDDED_FONT)) {
                    if (font == null) {
                        throw new IOException("Font not found: " + EMBEDDED_FONT);
                    }
                    embedded = PDType0Font.load(document, font);
                }
                regular = embedded;
                bold = embedded;
                italic = embedded;
                sans = embedded;
            } else {
                regular = new PDType1Font(Standard14Fonts.FontName.TIMES_ROMAN);
                bold = new PDType1Font(Standard14Fonts.FontName.HELVETICA_BOLD);
                italic = new PDType1Font(Standard14Fonts.FontName.HELVETICA_OBLIQUE);
                sans = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
            }
        }

        void run() throws IOException {
            for (int i = 0; i < spec.elements.size(); i++) {
                Element element = spec.elements.get(i);
                switch (element.kind) {
                    case COLUMNS -> {
                        columns = element.value;
                        endPage();
                    }
                    case PAGE_BREAK -> endPage();
                    case ROTATION -> {
                        rotation = element.value;
                        endPage();
                    }
                    case IMAGE_PAGE -> {
                        endPage();
                        imagePage(element.coverage);
                    }
                    case TEXT -> {
                        boolean heading = element.style == Style.BOLD;
                        Element next = i + 1 < spec.elements.size() ? spec.elements.get(i + 1) : null;
                        text(element, heading && next != null && (next.kind == Kind.TEXT || next.kind == Kind.ROW));
                    }
                    case ROW -> row(element);
                }
            }
            endPage();
        }

        private float columnWidth() {
            return (spec.width - 2 * spec.margin - (columns - 1) * GUTTER) / columns;
        }

        private float columnLeft() {
            return spec.margin + column * (columnWidth() + GUTTER);
        }

        private void text(Element element, boolean keepWithNext) throws IOException {
            float size = element.size > 0 ? element.size : spec.bodySize;
            PDFont font = element.style == Style.BOLD ? bold : regular;
            List<String> lines = wrap(element.text, font, size, columnWidth());
            float lineHeight = size * LEADING;
            if (page == null) {
                startPage();
            }
            if (!columnEmpty) {
                y -= element.spaceBefore * size;
            }
            float needed = keepWithNext ? lineHeight * (lines.size() + 2) : lineHeight;
            if (y - needed < spec.margin) {
                nextColumn();
            }
            for (String line : lines) {
                if (y - lineHeight < spec.margin) {
                    nextColumn();
                }
                y -= size;
                show(line, font, element.style == Style.BOLD, size, columnLeft(), y);
                y -= lineHeight - size;
                columnEmpty = false;
            }
            y -= element.spaceAfter * size;
        }

        /** A list item or table row: the label and the text side by side, each wrapped within its own column. */
        private void row(Element element) throws IOException {
            float size = spec.bodySize;
            float lineHeight = size * LEADING;
            float labelWidth = element.textX - element.labelX - size;
            List<String> labels = element.label.isEmpty() ? List.of() : wrap(element.label, regular, size, Math.max(size, labelWidth));
            List<String> lines = wrap(element.text, regular, size, columnWidth() - element.textX);
            if (page == null) {
                startPage();
            }
            if (!columnEmpty) {
                y -= element.spaceBefore * size;
            }
            if (y - lineHeight * Math.min(2, Math.max(labels.size(), lines.size())) < spec.margin) {
                nextColumn();
            }
            for (int i = 0; i < Math.max(labels.size(), lines.size()); i++) {
                if (y - lineHeight < spec.margin) {
                    nextColumn();
                }
                y -= size;
                if (i < labels.size()) {
                    show(labels.get(i), regular, false, size, columnLeft() + element.labelX, y);
                }
                if (i < lines.size()) {
                    show(lines.get(i), regular, false, size, columnLeft() + element.textX, y);
                }
                y -= lineHeight - size;
                columnEmpty = false;
            }
            y -= element.spaceAfter * size;
        }

        private void show(String text, PDFont font, boolean boldStyle, float size, float x, float baseline) throws IOException {
            content.beginText();
            content.setFont(font, size);
            boolean fakeBold = boldStyle && spec.embeddedFonts;
            if (fakeBold) {
                content.setRenderingMode(RenderingMode.FILL_STROKE);
                content.setLineWidth(size * 0.03f);
            }
            content.newLineAtOffset(x, baseline);
            content.showText(text);
            if (fakeBold) {
                content.setRenderingMode(RenderingMode.FILL);
            }
            content.endText();
        }

        private void nextColumn() throws IOException {
            if (column + 1 < columns) {
                column++;
                y = spec.height - spec.margin;
                columnEmpty = true;
            } else {
                endPage();
                startPage();
            }
        }

        private void startPage() throws IOException {
            page = new PDPage(new PDRectangle(spec.width, spec.height));
            page.setRotation(rotation);
            document.addPage(page);
            content = new PDPageContentStream(document, page);
            column = 0;
            y = spec.height - spec.margin;
            columnEmpty = true;
            int number = document.getNumberOfPages();
            if (spec.header != null && (number > 1 || spec.headerOnFirstPage)) {
                small(spec.header, spec.margin, spec.height - 42);
            }
            if (spec.footer != null) {
                small(spec.footer, spec.margin, 36);
            }
            if (spec.pageNumbers) {
                String label = Integer.toString(number);
                float labelWidth = sans.getStringWidth(label) / 1000 * 9;
                show(label, sans, false, 9, (spec.width - labelWidth) / 2, 36);
            }
        }

        private void small(String text, float x, float baseline) throws IOException {
            show(text, italic, false, 9, x, baseline);
        }

        private void endPage() throws IOException {
            if (content != null) {
                content.close();
            }
            content = null;
            page = null;
        }

        private void imagePage(double coverage) throws IOException {
            PDPage imagePage = new PDPage(new PDRectangle(spec.width, spec.height));
            imagePage.setRotation(rotation);
            document.addPage(imagePage);
            BufferedImage scan = new BufferedImage(306, 396, BufferedImage.TYPE_BYTE_GRAY);
            Graphics2D g = scan.createGraphics();
            g.setColor(Color.WHITE);
            g.fillRect(0, 0, scan.getWidth(), scan.getHeight());
            g.setColor(Color.DARK_GRAY);
            Random random = new Random(document.getNumberOfPages());
            for (int row = 40; row < scan.getHeight() - 40; row += 9) {
                int length = 120 + random.nextInt(140);
                g.fillRect(36, row, Math.min(length, scan.getWidth() - 72), 4);
            }
            g.dispose();
            PDImageXObject image = LosslessFactory.createFromImage(document, scan);
            double side = Math.sqrt(Math.max(0, Math.min(1, coverage)));
            float w = (float) (spec.width * side);
            float h = (float) (spec.height * side);
            try (PDPageContentStream stream = new PDPageContentStream(document, imagePage)) {
                stream.drawImage(image, (spec.width - w) / 2, (spec.height - h) / 2, w, h);
            }
        }

        private static List<String> wrap(String text, PDFont font, float size, float maxWidth) {
            List<String> lines = new ArrayList<>();
            for (String paragraphLine : text.split("\n", -1)) {
                StringBuilder line = new StringBuilder();
                for (String word : paragraphLine.trim().split("\\s+")) {
                    if (word.isEmpty()) {
                        continue;
                    }
                    String candidate = line.isEmpty() ? word : line + " " + word;
                    if (!line.isEmpty() && width(candidate, font, size) > maxWidth) {
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
            }
            return lines;
        }

        private static float width(String text, PDFont font, float size) {
            try {
                return font.getStringWidth(text) / 1000 * size;
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
    }

    /** Fails fast when a test passes text the standard fonts cannot show. */
    static String requireAscii(String text) {
        Objects.requireNonNull(text, "text");
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) > 0x7E) {
                throw new IllegalArgumentException("Only ASCII text is supported: " + text);
            }
        }
        return text;
    }
}
