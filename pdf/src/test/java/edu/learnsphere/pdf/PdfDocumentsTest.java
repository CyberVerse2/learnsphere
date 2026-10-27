package edu.learnsphere.pdf;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import edu.learnsphere.pdf.testing.TestPdf;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicBoolean;
import org.apache.pdfbox.io.RandomAccessReadBuffer;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PdfDocumentsTest {

    @TempDir
    Path dir;

    @Test
    void closesTheFileWhenParsingFailsWithARuntimeException() throws Exception {
        byte[] bytes = TestPdf.builder().paragraph(TestPdf.sentences(1, 3)).bytes();
        IllegalStateException broken = new IllegalStateException("broken structure");
        ClosingBuffer failing = new ClosingBuffer(bytes) {
            @Override
            public int read() {
                throw broken;
            }

            @Override
            public int read(byte[] b, int offset, int length) {
                throw broken;
            }
        };
        assertSame(broken, assertThrows(IllegalStateException.class, () -> PdfDocuments.load(failing)));
        assertTrue(failing.closed.get(), "the reader is closed");
    }

    @Test
    void closesTheFileWhenTheFileIsDamaged() throws Exception {
        ClosingBuffer damaged = new ClosingBuffer(TestPdf.damagedBytes());
        assertThrows(IOException.class, () -> PdfDocuments.load(damaged));
        assertTrue(damaged.closed.get());
    }

    @Test
    void handsTheFileToTheDocumentWhenParsingSucceeds() throws Exception {
        ClosingBuffer good = new ClosingBuffer(TestPdf.builder().paragraph(TestPdf.sentences(2, 3)).bytes());
        try (PDDocument document = PdfDocuments.load(good)) {
            assertEquals(1, document.getNumberOfPages());
            assertFalse(good.closed.get(), "open while the document is");
        }
        assertTrue(good.closed.get(), "closed with the document");
    }

    @Test
    void reportsDamagedFilesAndLetsThemBeReplaced() throws Exception {
        Path damaged = Files.write(dir.resolve("damaged.pdf"), TestPdf.damagedBytes());
        assertEquals(PdfReadException.Reason.DAMAGED,
                assertThrows(PdfReadException.class, () -> PdfDocuments.open(damaged)).reason());
        Files.delete(damaged);
    }

    /** An in-memory file that records whether it was closed. */
    private static class ClosingBuffer extends RandomAccessReadBuffer {
        final AtomicBoolean closed = new AtomicBoolean();

        ClosingBuffer(byte[] bytes) {
            super(bytes);
        }

        @Override
        public void close() throws IOException {
            closed.set(true);
            super.close();
        }
    }
}
