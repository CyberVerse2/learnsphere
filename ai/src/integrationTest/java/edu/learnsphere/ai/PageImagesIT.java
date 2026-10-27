package edu.learnsphere.ai;

import static org.assertj.core.api.Assertions.assertThat;

import edu.learnsphere.core.spi.AiCapabilities;
import edu.learnsphere.core.spi.PageTranscription;
import edu.learnsphere.core.util.CancellationToken;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.AffineTransform;
import java.awt.geom.Path2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * Reading page images with the vision model: a rendered page of notes is transcribed and compared word by
 * word, a drawn process-state diagram is described, and a page of plain text yields no figures. Results go
 * to {@code build/reports/ai-eval/vision.md}.
 */
@ExtendWith(RealRuntime.Resolver.class)
class PageImagesIT {

    private static final int WIDTH = 1240;
    private static final int HEIGHT = 1754;
    private static final int MARGIN = 110;

    static final String HEADING = "3.2 Virtual memory";
    static final List<String> PARAGRAPHS = List.of(
            "Virtual memory lets a process run even when only part of it is in physical memory. The rest of the "
                    + "process stays on disk until it is needed.",
            "Each page-table entry has a valid bit. When a process touches a page whose valid bit is clear, the "
                    + "hardware raises a page fault and the operating system takes over.",
            "To handle the fault, the operating system finds a free frame, reads the missing page from disk, "
                    + "updates the page table and restarts the instruction that caused the fault.");
    static final String SUBHEADING = "Page replacement policies";
    static final List<String> BULLETS = List.of(
            "FIFO evicts the page that has been in memory the longest.",
            "LRU evicts the page that has not been used for the longest time.",
            "The clock algorithm approximates LRU with a reference bit.");

    private static AiCapabilities ai;
    private static final EvalReport REPORT = new EvalReport("vision", "Reading page images");

    @BeforeAll
    static void setUp(LocalAi localAi) {
        System.setProperty("java.awt.headless", "true");
        ai = localAi.capabilities();
    }

    @AfterAll
    static void write() {
        REPORT.write();
    }

    // ------------------------------------------------------------------ rendering

    private static Graphics2D page(BufferedImage image) {
        Graphics2D g = image.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, image.getWidth(), image.getHeight());
        g.setColor(Color.BLACK);
        return g;
    }

    /** Draws {@code text} wrapped to the page width from {@code y}; returns the next free y. */
    private static int paragraph(Graphics2D g, String text, int x, int y, int width) {
        FontMetrics metrics = g.getFontMetrics();
        StringBuilder line = new StringBuilder();
        for (String word : text.split(" ")) {
            String candidate = line.isEmpty() ? word : line + " " + word;
            if (metrics.stringWidth(candidate) > width && !line.isEmpty()) {
                g.drawString(line.toString(), x, y);
                y += metrics.getHeight();
                line = new StringBuilder(word);
            } else {
                line = new StringBuilder(candidate);
            }
        }
        if (!line.isEmpty()) {
            g.drawString(line.toString(), x, y);
            y += metrics.getHeight();
        }
        return y;
    }

    /** A page of notes: heading, three paragraphs, a subheading and a bulleted list, with a page number. */
    static BufferedImage textPage() {
        BufferedImage image = new BufferedImage(WIDTH, HEIGHT, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = page(image);
        int width = WIDTH - 2 * MARGIN;
        int y = MARGIN + 40;
        g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 44));
        g.drawString(HEADING, MARGIN, y);
        y += 80;
        g.setFont(new Font(Font.SERIF, Font.PLAIN, 30));
        for (String text : PARAGRAPHS) {
            y = paragraph(g, text, MARGIN, y, width) + 24;
        }
        y += 20;
        g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 34));
        g.drawString(SUBHEADING, MARGIN, y);
        y += 60;
        g.setFont(new Font(Font.SERIF, Font.PLAIN, 30));
        for (String bullet : BULLETS) {
            g.fillOval(MARGIN + 6, y - 14, 10, 10);
            y = paragraph(g, bullet, MARGIN + 36, y, width - 36) + 12;
        }
        g.setFont(new Font(Font.SERIF, Font.PLAIN, 24));
        g.drawString("17", WIDTH / 2 - 12, HEIGHT - 70);
        g.dispose();
        return image;
    }

    /** A page with a short paragraph and a diagram of process states with labelled transitions. */
    static BufferedImage diagramPage() {
        BufferedImage image = new BufferedImage(WIDTH, HEIGHT, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = page(image);
        g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 40));
        g.drawString("2.1 Process states", MARGIN, MARGIN + 40);
        g.setFont(new Font(Font.SERIF, Font.PLAIN, 30));
        int y = paragraph(g, "A process moves between three states while it runs. Figure 2 shows the "
                + "transitions between them.", MARGIN, MARGIN + 120, WIDTH - 2 * MARGIN);

        int top = y + 40;
        int[][] boxes = {{170, top + 260}, {820, top + 260}, {495, top + 760}}; // ready, running, blocked (centres)
        String[] names = {"Ready", "Running", "Blocked"};
        g.setStroke(new BasicStroke(4));
        g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 36));
        for (int i = 0; i < 3; i++) {
            int cx = boxes[i][0] + 120;
            int cy = boxes[i][1];
            g.drawRoundRect(cx - 130, cy - 55, 260, 110, 40, 40);
            FontMetrics m = g.getFontMetrics();
            g.drawString(names[i], cx - m.stringWidth(names[i]) / 2, cy + 12);
        }
        g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 28));
        int readyX = boxes[0][0] + 120;
        int runningX = boxes[1][0] + 120;
        int blockedX = boxes[2][0] + 120;
        int rowY = boxes[0][1];
        int blockedY = boxes[2][1];
        arrow(g, readyX + 130, rowY - 25, runningX - 130, rowY - 25, "dispatch", 0, -18);
        arrow(g, runningX - 130, rowY + 25, readyX + 130, rowY + 25, "timeout", 0, 45);
        arrow(g, runningX - 20, rowY + 55, blockedX + 90, blockedY - 55, "I/O wait", 110, 0);
        arrow(g, blockedX - 90, blockedY - 55, readyX + 20, rowY + 55, "I/O done", -150, 0);

        g.setFont(new Font(Font.SERIF, Font.ITALIC, 28));
        g.drawString("Figure 2. Process states and transitions.", MARGIN + 250, blockedY + 160);
        g.dispose();
        return image;
    }

    private static void arrow(Graphics2D g, int x1, int y1, int x2, int y2, String label, int dx, int dy) {
        g.drawLine(x1, y1, x2, y2);
        double angle = Math.atan2(y2 - y1, x2 - x1);
        Path2D head = new Path2D.Double();
        head.moveTo(0, 0);
        head.lineTo(-22, -11);
        head.lineTo(-22, 11);
        head.closePath();
        AffineTransform at = AffineTransform.getTranslateInstance(x2, y2);
        at.rotate(angle);
        g.fill(at.createTransformedShape(head));
        FontMetrics m = g.getFontMetrics();
        int mx = (x1 + x2) / 2 - m.stringWidth(label) / 2 + dx;
        int my = (y1 + y2) / 2 + dy;
        g.drawString(label, mx, my);
    }

    static byte[] png(BufferedImage image) {
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            ImageIO.write(image, "png", out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    // ------------------------------------------------------------------ measures

    static List<String> words(String text) {
        List<String> words = new ArrayList<>();
        for (String w : text.toLowerCase(Locale.ROOT).replace('’', '\'').split("[^\\p{L}\\p{N}/'.-]+")) {
            String word = w.replaceAll("^[.'-]+|[.'-]+$", "");
            if (!word.isEmpty()) {
                words.add(word);
            }
        }
        return words;
    }

    /** 1 minus the word error rate: word-level edit distance divided by the expected word count. */
    static double wordAccuracy(List<String> expected, List<String> actual) {
        int[] previous = new int[actual.size() + 1];
        int[] current = new int[actual.size() + 1];
        for (int j = 0; j <= actual.size(); j++) {
            previous[j] = j;
        }
        for (int i = 1; i <= expected.size(); i++) {
            current[0] = i;
            for (int j = 1; j <= actual.size(); j++) {
                int substitution = previous[j - 1] + (expected.get(i - 1).equals(actual.get(j - 1)) ? 0 : 1);
                current[j] = Math.min(substitution, Math.min(previous[j] + 1, current[j - 1] + 1));
            }
            int[] swap = previous;
            previous = current;
            current = swap;
        }
        return Math.max(0, 1 - (double) previous[actual.size()] / expected.size());
    }

    // ------------------------------------------------------------------ tests

    @Test
    void transcribesARenderedPageWordForWord() {
        byte[] png = png(textPage());
        long start = System.nanoTime();
        PageTranscription page = ai.pageReader().read(png, 17, CancellationToken.NONE);
        double millis = (System.nanoTime() - start) / 1e6;

        String expectedText = HEADING + " " + String.join(" ", PARAGRAPHS) + " " + SUBHEADING + " "
                + String.join(" ", BULLETS);
        List<String> expected = words(expectedText);
        List<String> actual = words(page.markdown().replaceAll("(?m)^\\s*17\\s*$", ""));
        double accuracy = wordAccuracy(expected, actual);
        boolean headings = page.markdown().lines().anyMatch(l -> l.startsWith("#") && l.contains("Virtual memory"))
                && page.markdown().lines().anyMatch(l -> l.startsWith("#") && l.contains("Page replacement"));
        long bullets = page.markdown().lines().filter(l -> l.matches("\\s*[-*•] .*")).count();

        REPORT.line("## Page transcription").line("")
                .line("| Measure | Result |").line("|---|---|")
                .line("| Image | %d × %d PNG, %d KB |", WIDTH, HEIGHT, png.length / 1024)
                .line("| Word accuracy (1 − word error rate) | %s (%d expected words) |", EvalReport.pct(accuracy),
                        expected.size())
                .line("| Headings kept as Markdown headings | %s |", headings ? "yes" : "no")
                .line("| Bullets kept as a list | %d of %d |", bullets, BULLETS.size())
                .line("| Flagged parts | %s |", page.illegibleSpans())
                .line("| Time | %s |", EvalReport.ms(millis))
                .line("")
                .line("```markdown").line(page.markdown()).line("```").line("");

        assertThat(accuracy).as("word accuracy").isGreaterThanOrEqualTo(0.95);
        assertThat(headings).as("headings").isTrue();
        assertThat(bullets).isEqualTo(BULLETS.size());
    }

    @Test
    void describesADiagramAndFindsNoFiguresOnAPageOfText() {
        long start = System.nanoTime();
        List<String> figures = ai.figureDescriber().describe(png(diagramPage()), 9, CancellationToken.NONE);
        double diagramMillis = (System.nanoTime() - start) / 1e6;
        start = System.nanoTime();
        List<String> none = ai.figureDescriber().describe(png(textPage()), 17, CancellationToken.NONE);
        double textMillis = (System.nanoTime() - start) / 1e6;

        String all = String.join(" ", figures).toLowerCase(Locale.ROOT);
        List<String> states = List.of("ready", "running", "blocked").stream().filter(all::contains).toList();
        List<String> transitions = List.of("dispatch", "timeout", "i/o wait", "i/o done").stream()
                .filter(t -> all.contains(t) || all.contains(t.replace("i/o", "i/o ")) || all.contains(t.replace("/", "")))
                .toList();
        REPORT.line("## Figure descriptions").line("")
                .line("| Measure | Result |").line("|---|---|")
                .line("| Figures found on the diagram page | %d |", figures.size())
                .line("| States named | %s of 3 %s |", states.size(), states)
                .line("| Transitions named | %s of 4 %s |", transitions.size(), transitions)
                .line("| Figures found on the text-only page | %d %s |", none.size(), none)
                .line("| Time (diagram / text page) | %s / %s |", EvalReport.ms(diagramMillis), EvalReport.ms(textMillis))
                .line("");
        figures.forEach(f -> REPORT.line("> %s", f));
        REPORT.line("");

        assertThat(figures).hasSize(1);
        assertThat(states).hasSize(3);
        assertThat(transitions.size()).isGreaterThanOrEqualTo(2);
        assertThat(none).isEmpty();
    }
}
