import java.awt.AlphaComposite;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.LinearGradientPaint;
import java.awt.MultipleGradientPaint;
import java.awt.RadialGradientPaint;
import java.awt.RenderingHints;
import java.awt.Shape;
import java.awt.geom.AffineTransform;
import java.awt.geom.Arc2D;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Path2D;
import java.awt.geom.Point2D;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.awt.image.ConvolveOp;
import java.awt.image.Kernel;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import javax.imageio.ImageIO;

/**
 * Renders the LearnSphere app icon: a lapis sphere with one tilted orbit and a small moon on a warm paper
 * tile. The geometry follows the app mark ({@code SphereArt}): orbits tilted by -16 degrees, the moon at 152
 * degrees on the ring, and the colours from docs/DESIGN.md.
 *
 * <p>Usage: {@code java IconRenderer.java <output folder>}. Writes {@code LearnSphere.iconset/} (the PNG
 * sizes {@code iconutil} expects), {@code LearnSphere.ico} (PNG-compressed entries from 16 to 256 px) and
 * {@code LearnSphere-1024.png}. Each size is drawn natively, with fewer details at small sizes, rather than
 * scaled down from one large image.
 */
public final class IconRenderer {

    private static final double TILT = Math.toRadians(-16);

    // Paper and ink from docs/DESIGN.md, lapis accent #2F55D4.
    private static final Color PAPER_TOP = new Color(0xFBF9F5);
    private static final Color PAPER_BOTTOM = new Color(0xEDE7DD);
    private static final Color PAPER_EDGE = new Color(0xDCD4C7);
    private static final Color LAPIS_LIGHT = new Color(0x9DB2F5);
    private static final Color LAPIS = new Color(0x2F55D4);
    private static final Color LAPIS_DEEP = new Color(0x1A2F86);
    private static final Color LAPIS_INK = new Color(0x22409F);

    /** How the tile sits on the canvas. */
    enum Layout {
        /** macOS grid: an 824/1024 superellipse tile with room for its shadow. */
        MAC(100.0 / 1024, 0, true),
        /** Windows: a rounded square that nearly fills the image. */
        WINDOWS(12.0 / 256, 44.0 / 256, false);

        final double inset;
        /** Corner radius as a fraction of the image; 0 draws the macOS superellipse. */
        final double cornerRadius;
        final boolean shadow;

        Layout(double inset, double cornerRadius, boolean shadow) {
            this.inset = inset;
            this.cornerRadius = cornerRadius;
            this.shadow = shadow;
        }
    }

    private IconRenderer() {
    }

    public static void main(String[] args) throws IOException {
        if (args.length != 1) {
            System.err.println("Usage: java IconRenderer.java <output folder>");
            System.exit(2);
        }
        Path out = Path.of(args[0]);
        Path iconset = out.resolve("LearnSphere.iconset");
        Files.createDirectories(iconset);

        for (int points : new int[] {16, 32, 128, 256, 512}) {
            write(render(points, Layout.MAC), iconset.resolve("icon_" + points + "x" + points + ".png"));
            write(render(points * 2, Layout.MAC), iconset.resolve("icon_" + points + "x" + points + "@2x.png"));
        }
        write(render(1024, Layout.MAC), out.resolve("LearnSphere-1024.png"));

        List<BufferedImage> icoImages = new ArrayList<>();
        for (int size : new int[] {16, 20, 24, 32, 40, 48, 64, 96, 128, 256}) {
            icoImages.add(render(size, Layout.WINDOWS));
        }
        writeIco(icoImages, out.resolve("LearnSphere.ico"));
    }

    static BufferedImage render(int size, Layout layout) {
        // Draw large and halve repeatedly: smooth edges without relying on the platform's stroke quality.
        int scale = size <= 64 ? 8 : 4;
        int big = size * scale;
        BufferedImage canvas = new BufferedImage(big, big, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = canvas.createGraphics();
        hints(g);
        double s = big;

        double inset = s * layout.inset;
        double tileSize = s - 2 * inset;
        double arc = s * layout.cornerRadius * 2;
        Shape tile = layout.cornerRadius == 0
                ? superellipse(inset, inset, tileSize)
                : new RoundRectangle2D.Double(inset, inset, tileSize, tileSize, arc, arc);

        if (layout.shadow) {
            drawShadow(g, tile, s);
        }
        g.setPaint(new LinearGradientPaint(new Point2D.Double(0, inset), new Point2D.Double(0, inset + tileSize),
                new float[] {0f, 1f}, new Color[] {PAPER_TOP, PAPER_BOTTOM}));
        g.fill(tile);
        g.setStroke(new BasicStroke((float) Math.max(scale * 0.6, s / 512)));
        g.setColor(PAPER_EDGE);
        g.draw(tile);

        drawSphere(g, s / 2, s / 2, tileSize * 0.25, size, scale);
        g.dispose();
        return downscale(canvas, size);
    }

    private static void drawSphere(Graphics2D g, double cx, double cy, double r, int size, int scale) {
        boolean small = size <= 32;
        double ringRx = r * 1.66;
        double ringRy = r * 0.47;
        double ringWidth = Math.max(r * (small ? 0.15 : 0.085), scale * 1.1);

        // Back half of the orbit, behind the sphere.
        g.setStroke(new BasicStroke((float) ringWidth, BasicStroke.CAP_BUTT, BasicStroke.JOIN_ROUND));
        g.setColor(withAlpha(LAPIS, small ? 0.50 : 0.32));
        g.draw(tilted(new Arc2D.Double(cx - ringRx, cy - ringRy, ringRx * 2, ringRy * 2, 0, 180, Arc2D.OPEN), cx, cy));

        // Sphere body: lit from the upper left.
        Ellipse2D body = new Ellipse2D.Double(cx - r, cy - r, r * 2, r * 2);
        g.setPaint(new RadialGradientPaint(new Point2D.Double(cx - r * 0.40, cy - r * 0.46), (float) (r * 1.66),
                new float[] {0f, 0.50f, 1f}, new Color[] {LAPIS_LIGHT, LAPIS, LAPIS_DEEP},
                MultipleGradientPaint.CycleMethod.NO_CYCLE));
        g.fill(body);

        if (!small) {
            // Orbit lines inside the sphere, as on the course covers.
            Shape oldClip = g.getClip();
            g.clip(body);
            float line = (float) Math.max(r * 0.022, scale * 0.9);
            g.setStroke(new BasicStroke(line));
            g.setColor(new Color(255, 255, 255, 64));
            for (double k : new double[] {0.22, 0.58}) {
                g.draw(tilted(new Ellipse2D.Double(cx - r, cy - r * k, r * 2, r * 2 * k), cx, cy));
            }
            g.setClip(oldClip);
        }

        // Front half of the orbit, over the sphere.
        g.setStroke(new BasicStroke((float) ringWidth, BasicStroke.CAP_BUTT, BasicStroke.JOIN_ROUND));
        g.setColor(LAPIS_INK);
        g.draw(tilted(new Arc2D.Double(cx - ringRx, cy - ringRy, ringRx * 2, ringRy * 2, 180, 180, Arc2D.OPEN), cx, cy));

        // The moon, riding the front of the orbit.
        Point2D moonAt = pointOnOrbit(cx, cy, ringRx, ringRy, 152);
        double mr = r * (small ? 0.27 : 0.17);
        Ellipse2D moon = new Ellipse2D.Double(moonAt.getX() - mr, moonAt.getY() - mr, mr * 2, mr * 2);
        g.setColor(PAPER_TOP);
        g.fill(new Ellipse2D.Double(moonAt.getX() - mr - ringWidth * 0.6, moonAt.getY() - mr - ringWidth * 0.6,
                (mr + ringWidth * 0.6) * 2, (mr + ringWidth * 0.6) * 2));
        g.setPaint(new RadialGradientPaint(new Point2D.Double(moonAt.getX() - mr * 0.4, moonAt.getY() - mr * 0.45),
                (float) (mr * 1.8), new float[] {0f, 1f}, new Color[] {LAPIS_LIGHT, LAPIS},
                MultipleGradientPaint.CycleMethod.NO_CYCLE));
        g.fill(moon);
    }

    /** The macOS icon tile: a superellipse (exponent 5), which matches Apple's continuous corners closely. */
    private static Shape superellipse(double x, double y, double size) {
        double a = size / 2;
        double cx = x + a;
        double cy = y + a;
        double n = 5;
        Path2D.Double p = new Path2D.Double();
        int steps = 720;
        for (int i = 0; i <= steps; i++) {
            double t = 2 * Math.PI * i / steps;
            double cos = Math.cos(t);
            double sin = Math.sin(t);
            double px = cx + a * Math.signum(cos) * Math.pow(Math.abs(cos), 2 / n);
            double py = cy + a * Math.signum(sin) * Math.pow(Math.abs(sin), 2 / n);
            if (i == 0) {
                p.moveTo(px, py);
            } else {
                p.lineTo(px, py);
            }
        }
        p.closePath();
        return p;
    }

    private static void drawShadow(Graphics2D g, Shape tile, double s) {
        // Blur a quarter-size copy: the shadow is soft, so the lower resolution does not show.
        int small = (int) Math.ceil(s / 4);
        BufferedImage shadow = new BufferedImage(small, small, BufferedImage.TYPE_INT_ARGB);
        Graphics2D sg = shadow.createGraphics();
        hints(sg);
        sg.scale(0.25, 0.25);
        sg.translate(0, s * 0.014);
        sg.setColor(new Color(30, 28, 25, 64));
        sg.fill(tile);
        sg.dispose();
        BufferedImage blurred = blur(shadow, Math.max(1, (int) Math.round(small * 0.022)));
        Graphics2D bg = (Graphics2D) g.create();
        bg.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        bg.drawImage(blurred, 0, 0, (int) s, (int) s, null);
        bg.dispose();
    }

    private static BufferedImage blur(BufferedImage src, int radius) {
        // Two passes of a separable box blur approximate a gaussian.
        BufferedImage img = src;
        for (int pass = 0; pass < 2; pass++) {
            img = boxBlur(img, radius, true);
            img = boxBlur(img, radius, false);
        }
        return img;
    }

    private static BufferedImage boxBlur(BufferedImage src, int radius, boolean horizontal) {
        int n = radius * 2 + 1;
        float[] kernel = new float[n];
        java.util.Arrays.fill(kernel, 1f / n);
        Kernel k = horizontal ? new Kernel(n, 1, kernel) : new Kernel(1, n, kernel);
        BufferedImage dst = new BufferedImage(src.getWidth(), src.getHeight(), BufferedImage.TYPE_INT_ARGB);
        new ConvolveOp(k, ConvolveOp.EDGE_ZERO_FILL, null).filter(src, dst);
        return dst;
    }

    private static BufferedImage downscale(BufferedImage src, int size) {
        BufferedImage img = src;
        int w = src.getWidth();
        while (w > size) {
            w = Math.max(size, w / 2);
            BufferedImage next = new BufferedImage(w, w, BufferedImage.TYPE_INT_ARGB);
            Graphics2D g = next.createGraphics();
            g.setComposite(AlphaComposite.Src);
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            g.drawImage(img, 0, 0, w, w, null);
            g.dispose();
            img = next;
        }
        return img;
    }

    private static Shape tilted(Shape shape, double cx, double cy) {
        return AffineTransform.getRotateInstance(TILT, cx, cy).createTransformedShape(shape);
    }

    /** A point on the tilted orbit; degrees run counter-clockwise from 3 o'clock, front half below. */
    private static Point2D pointOnOrbit(double cx, double cy, double rx, double ry, double degrees) {
        double t = Math.toRadians(degrees);
        double x = rx * Math.cos(t);
        double y = ry * Math.sin(t);
        // Screen y grows downwards: the front (lower) half has positive y, matching SphereArt.
        double tilt = TILT;
        return new Point2D.Double(cx + x * Math.cos(tilt) - y * Math.sin(tilt), cy + x * Math.sin(tilt) + y * Math.cos(tilt));
    }

    private static Color withAlpha(Color c, double alpha) {
        return new Color(c.getRed(), c.getGreen(), c.getBlue(), (int) Math.round(alpha * 255));
    }

    private static void hints(Graphics2D g) {
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
        g.setRenderingHint(RenderingHints.KEY_COLOR_RENDERING, RenderingHints.VALUE_COLOR_RENDER_QUALITY);
    }

    private static void write(BufferedImage image, Path file) throws IOException {
        if (!ImageIO.write(image, "png", file.toFile())) {
            throw new IOException("No PNG writer");
        }
    }

    /** Writes an .ico whose entries are PNG images (supported by Windows Vista and later). */
    private static void writeIco(List<BufferedImage> images, Path file) throws IOException {
        List<byte[]> pngs = new ArrayList<>();
        for (BufferedImage image : images) {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            ImageIO.write(image, "png", bytes);
            pngs.add(bytes.toByteArray());
        }
        ByteBuffer header = ByteBuffer.allocate(6 + 16 * images.size()).order(ByteOrder.LITTLE_ENDIAN);
        header.putShort((short) 0).putShort((short) 1).putShort((short) images.size());
        int offset = header.capacity();
        for (int i = 0; i < images.size(); i++) {
            int size = images.get(i).getWidth();
            header.put((byte) (size >= 256 ? 0 : size));
            header.put((byte) (size >= 256 ? 0 : size));
            header.put((byte) 0); // no palette
            header.put((byte) 0); // reserved
            header.putShort((short) 1); // colour planes
            header.putShort((short) 32); // bits per pixel
            header.putInt(pngs.get(i).length);
            header.putInt(offset);
            offset += pngs.get(i).length;
        }
        try (OutputStream out = Files.newOutputStream(file)) {
            out.write(header.array());
            for (byte[] png : pngs) {
                out.write(png);
            }
        }
    }
}
