package edu.learnsphere.pdf.testing;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import org.apache.pdfbox.pdmodel.font.FontMappers;
import org.junit.platform.launcher.LauncherSession;
import org.junit.platform.launcher.LauncherSessionListener;

/**
 * Loads PDFBox's list of the computer's fonts as a test JVM starts, one JVM at a time. The build points every test
 * JVM of a module at one list ({@code pdfbox.fontcache}) and runs several of them at once; PDFBox writes the list
 * without locking, so JVMs building it together could leave another reading a half-written file. Holding a lock
 * on the list's folder, the first JVM builds the list and the others read the finished one. Does nothing when
 * {@code pdfbox.fontcache} is not set. Registered in {@code META-INF/services}.
 */
public final class SharedFontList implements LauncherSessionListener {

    /** The system property PDFBox reads for the folder of its font list. */
    static final String FONT_CACHE_PROPERTY = "pdfbox.fontcache";

    @Override
    public void launcherSessionOpened(LauncherSession session) {
        String folder = System.getProperty(FONT_CACHE_PROPERTY);
        if (folder == null || folder.isBlank()) {
            return;
        }
        try (FileChannel channel = FileChannel.open(Path.of(folder, ".lock"), StandardOpenOption.CREATE,
                StandardOpenOption.WRITE)) {
            // Held until the channel closes.
            channel.lock();
            // Looking up any font loads the list, building and saving it first when there is none.
            FontMappers.instance().getFontBoxFont("Helvetica", null);
        } catch (IOException e) {
            // Without the lock PDFBox still loads the list when a test first needs it.
        }
    }
}
