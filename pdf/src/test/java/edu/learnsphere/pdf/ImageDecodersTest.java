package edu.learnsphere.pdf;

import static org.assertj.core.api.Assertions.assertThat;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.BitSet;
import java.util.Iterator;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageOutputStream;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.graphics.color.PDDeviceGray;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.junit.jupiter.api.Test;

/**
 * Scanned PDFs often store page images as JBIG2 or JPEG 2000, which PDFBox decodes only with the ImageIO
 * plugins on the class path. Each test renders a page whose image is dark in the top half and light in the
 * bottom half; without the decoder the page renders blank and both halves look the same.
 */
class ImageDecodersTest {

    private static final int SIZE = 8;

    @Test
    void rendersJbig2PageImages() throws IOException {
        assertTopAndBottomDiffer(render(jbig2HalfBlack(), COSName.JBIG2_DECODE, 1));
    }

    @Test
    void rendersJpeg2000PageImages() throws IOException {
        assertTopAndBottomDiffer(render(jpeg2000HalfBlack(), COSName.JPX_DECODE, 8));
    }

    private static void assertTopAndBottomDiffer(BufferedImage page) {
        int top = luminance(page.getRGB(page.getWidth() / 2, page.getHeight() / 4));
        int bottom = luminance(page.getRGB(page.getWidth() / 2, page.getHeight() * 3 / 4));
        assertThat(Math.abs(top - bottom)).as("top %d, bottom %d", top, bottom).isGreaterThan(128);
    }

    private static BufferedImage render(byte[] encoded, COSName filter, int bitsPerComponent) throws IOException {
        try (PDDocument document = new PDDocument()) {
            PDPage page = new PDPage(new PDRectangle(80, 80));
            document.addPage(page);
            PDImageXObject image = new PDImageXObject(document, new ByteArrayInputStream(encoded), filter, SIZE, SIZE,
                    bitsPerComponent, PDDeviceGray.INSTANCE);
            try (PDPageContentStream content = new PDPageContentStream(document, page)) {
                content.drawImage(image, 0, 0, 80, 80);
            }
            return new PDFRenderer(document).renderImage(0, 1f);
        }
    }

    /**
     * An embedded JBIG2 stream (page information, immediate generic region coded with MMR, end of page) for
     * an 8×8 image whose top four rows are set.
     */
    private static byte[] jbig2HalfBlack() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] pageInfo = new Bits()
                .bytes(4, SIZE).bytes(4, SIZE).bytes(4, 0).bytes(4, 0) // width, height, resolution
                .bytes(1, 0) // page flags: default pixel 0, OR
                .bytes(2, 0) // no striping
                .toBytes();
        segment(out, 0, 48, pageInfo);

        Bits region = new Bits()
                .bytes(4, SIZE).bytes(4, SIZE).bytes(4, 0).bytes(4, 0) // region size and position
                .bytes(1, 0) // external combination: OR
                .bytes(1, 1); // MMR coding
        region.bits("001").bits("00110101").bits("000101"); // row 1: horizontal, white 0, black 8
        region.bits("11").bits("11").bits("11"); // rows 2-4: same as the row above
        region.bits("001").bits("10011").bits("0000110111"); // row 5: horizontal, white 8, black 0
        region.bits("1").bits("1").bits("1"); // rows 6-8: same as the row above
        region.bits("000000000001").bits("000000000001"); // end of block
        segment(out, 1, 38, region.toBytes());

        segment(out, 2, 49, new byte[0]);
        return out.toByteArray();
    }

    private static void segment(ByteArrayOutputStream out, int number, int type, byte[] data) {
        out.writeBytes(new Bits()
                .bytes(4, number)
                .bytes(1, type) // one-byte page association
                .bytes(1, 0) // no referred-to segments
                .bytes(1, 1) // page 1
                .bytes(4, data.length)
                .toBytes());
        out.writeBytes(data);
    }

    private static byte[] jpeg2000HalfBlack() throws IOException {
        BufferedImage image = new BufferedImage(SIZE, SIZE, BufferedImage.TYPE_BYTE_GRAY);
        Graphics2D g = image.createGraphics();
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, SIZE, SIZE);
        g.setColor(Color.BLACK);
        g.fillRect(0, 0, SIZE, SIZE / 2);
        g.dispose();
        Iterator<ImageWriter> writers = ImageIO.getImageWritersByFormatName("jpeg2000");
        assertThat(writers.hasNext()).as("JPEG 2000 codec on the class path").isTrue();
        ImageWriter writer = writers.next();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ImageOutputStream stream = ImageIO.createImageOutputStream(out)) {
            writer.setOutput(stream);
            writer.write(image);
        } finally {
            writer.dispose();
        }
        return out.toByteArray();
    }

    private static int luminance(int rgb) {
        Color color = new Color(rgb);
        return (color.getRed() * 299 + color.getGreen() * 587 + color.getBlue() * 114) / 1000;
    }

    /** Big-endian bit writer. */
    private static final class Bits {
        private final BitSet bits = new BitSet();
        private int length;

        Bits bits(String pattern) {
            for (char c : pattern.toCharArray()) {
                bits.set(length++, c == '1');
            }
            return this;
        }

        Bits bytes(int count, long value) {
            for (int i = count * 8 - 1; i >= 0; i--) {
                bits.set(length++, ((value >>> i) & 1) == 1);
            }
            return this;
        }

        byte[] toBytes() {
            byte[] result = new byte[(length + 7) / 8];
            for (int i = 0; i < length; i++) {
                if (bits.get(i)) {
                    result[i / 8] |= (byte) (0x80 >>> (i % 8));
                }
            }
            return result;
        }
    }
}
