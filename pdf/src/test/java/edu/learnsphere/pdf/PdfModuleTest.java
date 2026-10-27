package edu.learnsphere.pdf;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import edu.learnsphere.core.spi.Capabilities;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PdfModuleTest {

    @TempDir
    Path dir;

    @Test
    void fillsTheBuilderItIsGiven() {
        Capabilities.Builder builder = Capabilities.builder();
        assertSame(builder, PdfModule.install(builder));
    }

    @Test
    void keepsTheFontListWhereItIsTold() {
        String before = System.getProperty(PdfModule.FONT_CACHE_PROPERTY);
        try {
            System.setProperty(PdfModule.FONT_CACHE_PROPERTY, "/somewhere/else");
            PdfModule.useFontCacheDirectory(dir.resolve("ignored"));
            assertEquals("/somewhere/else", System.getProperty(PdfModule.FONT_CACHE_PROPERTY), "a setting given at launch wins");
            assertFalse(Files.exists(dir.resolve("ignored")));

            System.clearProperty(PdfModule.FONT_CACHE_PROPERTY);
            Path fonts = dir.resolve("cache").resolve("fonts");
            PdfModule.useFontCacheDirectory(fonts);
            assertTrue(Files.isDirectory(fonts));
            assertEquals(fonts.toAbsolutePath().toString(), System.getProperty(PdfModule.FONT_CACHE_PROPERTY));
        } finally {
            if (before == null) {
                System.clearProperty(PdfModule.FONT_CACHE_PROPERTY);
            } else {
                System.setProperty(PdfModule.FONT_CACHE_PROPERTY, before);
            }
        }
    }
}
