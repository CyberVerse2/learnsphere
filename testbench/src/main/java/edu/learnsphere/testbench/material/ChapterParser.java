package edu.learnsphere.testbench.material;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Reads a chapter written in the testbench's small material format:
 *
 * <pre>
 * &#64;header CS2040 Operating Systems        (running header, once, before the first page)
 * &#64;title Chapter 3: Processes ...         (title block; also the document title)
 * &#64;page                                   (starts the next page)
 * # 3.1 Introduction                         (heading, level 1)
 * ## 3.2.1 The process control block         (heading, level 2)
 * Lines of text up to a blank line form one paragraph.
 * - a bullet item (one line each; consecutive items form one list)
 * &#64;table Table 3.1: Caption               (then "| a | b |" rows, the first is the header)
 * &#64;end
 * &#64;figure process-states Figure 3.1: Caption
 * % a comment
 * </pre>
 *
 * Text must be printable ASCII, so the standard PDF fonts can show every character.
 */
public final class ChapterParser {

    private ChapterParser() {
    }

    /** Parses the material file bundled at {@code resource} (relative to this class). */
    public static Chapter parseResource(String id, String resource) {
        try (InputStream in = ChapterParser.class.getResourceAsStream(resource)) {
            if (in == null) {
                throw new IllegalStateException("Missing material resource " + resource);
            }
            return parse(id, new String(in.readAllBytes(), StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Parses chapter {@code id} from {@code source}. */
    public static Chapter parse(String id, String source) {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(source, "source");
        String header = null;
        String title = null;
        List<Chapter.Page> pages = new ArrayList<>();
        List<Chapter.Block> blocks = new ArrayList<>();
        StringBuilder paragraph = new StringBuilder();
        List<String> bullets = new ArrayList<>();
        String tableCaption = null;
        List<List<String>> tableRows = new ArrayList<>();
        String[] lines = source.split("\\R", -1);
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i].strip();
            int lineNumber = i + 1;
            checkAscii(line, id, lineNumber);
            if (tableCaption != null) {
                if (line.equals("@end")) {
                    blocks.add(new Chapter.Table(tableCaption, tableRows));
                    tableCaption = null;
                    tableRows = new ArrayList<>();
                } else if (line.startsWith("|")) {
                    tableRows.add(cells(line));
                } else if (!line.isEmpty()) {
                    throw error(id, lineNumber, "expected a table row or @end");
                }
                continue;
            }
            boolean continuesParagraph = !line.isEmpty() && !line.startsWith("@") && !line.startsWith("#")
                    && !line.startsWith("- ") && !line.startsWith("%");
            if (!continuesParagraph) {
                flushParagraph(paragraph, blocks);
            }
            if (!line.startsWith("- ")) {
                flushBullets(bullets, blocks);
            }
            if (line.isEmpty() || line.startsWith("%")) {
                continue;
            }
            if (line.startsWith("@header ")) {
                header = line.substring("@header ".length()).strip();
            } else if (line.startsWith("@title ")) {
                title = line.substring("@title ".length()).strip();
                blocks.add(new Chapter.Title(title));
            } else if (line.equals("@page")) {
                if (blocks.isEmpty()) {
                    throw error(id, lineNumber, "empty page");
                }
                pages.add(new Chapter.Page(pages.size() + 1, blocks));
                blocks = new ArrayList<>();
            } else if (line.startsWith("@table ")) {
                tableCaption = line.substring("@table ".length()).strip();
            } else if (line.startsWith("@figure ")) {
                String[] parts = line.substring("@figure ".length()).strip().split("\\s+", 2);
                if (parts.length < 2) {
                    throw error(id, lineNumber, "a figure needs an art name and a caption");
                }
                blocks.add(new Chapter.Figure(parts[0], parts[1]));
            } else if (line.startsWith("@")) {
                throw error(id, lineNumber, "unknown directive " + line);
            } else if (line.startsWith("## ")) {
                blocks.add(new Chapter.Heading(2, line.substring(3).strip()));
            } else if (line.startsWith("# ")) {
                blocks.add(new Chapter.Heading(1, line.substring(2).strip()));
            } else if (line.startsWith("- ")) {
                bullets.add(line.substring(2).strip());
            } else {
                if (!paragraph.isEmpty()) {
                    paragraph.append(' ');
                }
                paragraph.append(line);
            }
        }
        if (tableCaption != null) {
            throw error(id, lines.length, "table \"" + tableCaption + "\" has no @end");
        }
        flushParagraph(paragraph, blocks);
        flushBullets(bullets, blocks);
        if (!blocks.isEmpty()) {
            pages.add(new Chapter.Page(pages.size() + 1, blocks));
        }
        if (header == null || title == null) {
            throw new IllegalArgumentException("Material " + id + " needs @header and @title");
        }
        return new Chapter(id, title, header, pages);
    }

    private static List<String> cells(String line) {
        String body = line.strip();
        if (body.startsWith("|")) {
            body = body.substring(1);
        }
        if (body.endsWith("|")) {
            body = body.substring(0, body.length() - 1);
        }
        List<String> cells = new ArrayList<>();
        for (String cell : body.split("\\|", -1)) {
            cells.add(cell.strip());
        }
        return cells;
    }

    private static void flushParagraph(StringBuilder paragraph, List<Chapter.Block> blocks) {
        if (!paragraph.isEmpty()) {
            blocks.add(new Chapter.Paragraph(paragraph.toString()));
            paragraph.setLength(0);
        }
    }

    private static void flushBullets(List<String> bullets, List<Chapter.Block> blocks) {
        if (!bullets.isEmpty()) {
            blocks.add(new Chapter.Bullets(bullets));
            bullets.clear();
        }
    }

    private static void checkAscii(String line, String id, int lineNumber) {
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c < 0x20 || c > 0x7e) {
                throw error(id, lineNumber, "character U+" + String.format("%04X", (int) c) + " is not printable ASCII");
            }
        }
    }

    private static IllegalArgumentException error(String id, int line, String message) {
        return new IllegalArgumentException("Material " + id + ", line " + line + ": " + message);
    }
}
