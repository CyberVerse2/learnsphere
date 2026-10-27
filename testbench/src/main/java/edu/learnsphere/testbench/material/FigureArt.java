package edu.learnsphere.testbench.material;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.AffineTransform;
import java.awt.geom.Path2D;
import java.awt.geom.Point2D;
import java.awt.geom.QuadCurve2D;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;

/**
 * The drawings placed in the sample chapters as images (so the PDF has no text for them and the figure
 * describer has something to look at), drawn with Java2D like a diagram exported from a drawing tool.
 */
public final class FigureArt {

    private static final Color INK = new Color(0x22, 0x22, 0x22);
    private static final Color FILL = new Color(0xE8, 0xF0, 0xFA);
    private static final Color ACCENT = new Color(0xFD, 0xF1, 0xDC);

    private FigureArt() {
    }

    /**
     * Draws {@code art}: {@code process-states} (the five-state process diagram) or {@code address-translation}
     * (paging with a translation look-aside buffer).
     */
    public static BufferedImage draw(String art) {
        return switch (art) {
            case "process-states" -> processStates();
            case "address-translation" -> addressTranslation();
            default -> throw new IllegalArgumentException("Unknown figure art " + art);
        };
    }

    private static BufferedImage processStates() {
        BufferedImage image = new BufferedImage(1110, 640, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = canvas(image);
        Box created = new Box("new", 40, 50, 190, 78);
        Box ready = new Box("ready", 250, 270, 190, 78);
        Box running = new Box("running", 670, 270, 190, 78);
        Box terminated = new Box("terminated", 880, 50, 190, 78);
        Box waiting = new Box("waiting", 460, 510, 190, 78);
        for (Box box : new Box[] {created, ready, running, terminated, waiting}) {
            box.paint(g, FILL);
        }
        arrow(g, created.right(), ready.top(), -60, "admitted", -10, -18);
        arrow(g, new Point2D.Double(ready.x + ready.w, ready.y + 22),
                new Point2D.Double(running.x, running.y + 22), -50, "scheduler dispatch", 0, -42);
        arrow(g, new Point2D.Double(running.x, running.y + ready.h - 22),
                new Point2D.Double(ready.x + ready.w, ready.y + ready.h - 22), -50, "interrupt", 0, 46);
        arrow(g, running.top(), terminated.left(), -60, "exit", 6, -10);
        arrow(g, new Point2D.Double(running.x + 60, running.y + running.h), waiting.right(), 60,
                "I/O or event wait", 92, 18);
        arrow(g, waiting.left(), new Point2D.Double(ready.x + 130, ready.y + ready.h), 60,
                "I/O or event completion", -132, 18);
        g.dispose();
        return image;
    }

    private static BufferedImage addressTranslation() {
        BufferedImage image = new BufferedImage(1110, 600, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = canvas(image);
        Box cpu = new Box("CPU", 30, 60, 140, 80);
        Box logical = new Box("p | d", 240, 70, 150, 60);
        Box tlb = new Box("TLB", 500, 30, 170, 100);
        Box table = new Box("page table", 500, 330, 170, 100);
        Box physical = new Box("f | d", 760, 190, 150, 60);
        Box memory = new Box("physical memory", 930, 330, 160, 220);
        cpu.paint(g, FILL);
        logical.paint(g, ACCENT);
        tlb.paint(g, FILL);
        table.paint(g, FILL);
        physical.paint(g, ACCENT);
        memory.paint(g, FILL);
        label(g, "logical address", logical.x + logical.w / 2, logical.y - 14, 26);
        label(g, "physical address", physical.x + physical.w / 2, physical.y - 14, 26);
        arrow(g, cpu.right(), logical.left(), 0, null, 0, 0);
        arrow(g, logical.right(), tlb.left(), -20, "look up p", 0, -24);
        arrow(g, new Point2D.Double(logical.x + logical.w / 2, logical.y + logical.h), table.left(), 40,
                "TLB miss", -56, 40);
        arrow(g, tlb.right(), physical.top(), -30, "TLB hit: f", 46, -16);
        arrow(g, table.right(), physical.bottom(), 30, "f", 26, 18);
        arrow(g, new Point2D.Double(physical.x + physical.w / 2 + 40, physical.y + physical.h),
                memory.top(), 0, null, 0, 0);
        g.dispose();
        return image;
    }

    private static Graphics2D canvas(BufferedImage image) {
        Graphics2D g = image.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, image.getWidth(), image.getHeight());
        return g;
    }

    /** A curved arrow from {@code from} to {@code to}, bent by {@code bend} pixels, with an optional label. */
    private static void arrow(Graphics2D g, Point2D from, Point2D to, double bend, String text, double labelDx,
                              double labelDy) {
        double mx = (from.getX() + to.getX()) / 2;
        double my = (from.getY() + to.getY()) / 2;
        double dx = to.getX() - from.getX();
        double dy = to.getY() - from.getY();
        double length = Math.max(1, Math.hypot(dx, dy));
        double cx = mx - dy / length * bend;
        double cy = my + dx / length * bend;
        g.setColor(INK);
        g.setStroke(new BasicStroke(3.2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.draw(new QuadCurve2D.Double(from.getX(), from.getY(), cx, cy, to.getX(), to.getY()));
        double angle = Math.atan2(to.getY() - cy, to.getX() - cx);
        Path2D head = new Path2D.Double();
        head.moveTo(0, 0);
        head.lineTo(-22, -10);
        head.lineTo(-22, 10);
        head.closePath();
        AffineTransform at = AffineTransform.getTranslateInstance(to.getX(), to.getY());
        at.rotate(angle);
        g.fill(at.createTransformedShape(head));
        if (text != null) {
            label(g, text, (from.getX() + 2 * cx + to.getX()) / 4 + labelDx, (from.getY() + 2 * cy + to.getY()) / 4 + labelDy,
                    25);
        }
    }

    private static void label(Graphics2D g, String text, double centerX, double baselineY, int size) {
        g.setColor(INK);
        g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, size));
        FontMetrics metrics = g.getFontMetrics();
        g.drawString(text, (float) (centerX - metrics.stringWidth(text) / 2.0), (float) baselineY);
    }

    private record Box(String name, double x, double y, double w, double h) {

        void paint(Graphics2D g, Color fill) {
            RoundRectangle2D shape = new RoundRectangle2D.Double(x, y, w, h, 26, 26);
            g.setColor(fill);
            g.fill(shape);
            g.setColor(INK);
            g.setStroke(new BasicStroke(3f));
            g.draw(shape);
            g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 30));
            FontMetrics metrics = g.getFontMetrics();
            g.drawString(name, (float) (x + (w - metrics.stringWidth(name)) / 2),
                    (float) (y + (h + metrics.getAscent() - metrics.getDescent()) / 2));
        }

        Point2D left() {
            return new Point2D.Double(x, y + h / 2);
        }

        Point2D right() {
            return new Point2D.Double(x + w, y + h / 2);
        }

        Point2D top() {
            return new Point2D.Double(x + w / 2, y);
        }

        Point2D bottom() {
            return new Point2D.Double(x + w / 2, y + h);
        }
    }
}
