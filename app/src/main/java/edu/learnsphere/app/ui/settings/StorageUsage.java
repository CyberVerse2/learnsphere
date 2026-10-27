package edu.learnsphere.app.ui.settings;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.Locale;

/** The space a profile takes on disk, and how it reads ("12.4 MB"). Blocking; call it off the FX thread. */
public final class StorageUsage {

    private static final String[] UNITS = {"bytes", "KB", "MB", "GB", "TB"};

    private StorageUsage() {
    }

    /** The total size of the regular files under {@code folder}; 0 when it doesn't exist. */
    public static long bytesIn(Path folder) {
        if (!Files.isDirectory(folder)) {
            return 0;
        }
        long[] total = {0};
        try {
            Files.walkFileTree(folder, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) {
                    if (attributes.isRegularFile()) {
                        total[0] += attributes.size();
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFileFailed(Path file, IOException e) {
                    // A file removed while counting doesn't count.
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return total[0];
    }

    /** {@code bytes} in the largest unit that keeps the number at least 1, with one decimal from MB up. */
    public static String format(long bytes) {
        if (bytes < 1024) {
            return bytes == 1 ? "1 byte" : bytes + " bytes";
        }
        double value = bytes;
        int unit = 0;
        while (value >= 1024 && unit < UNITS.length - 1) {
            value /= 1024;
            unit++;
        }
        DecimalFormat format = new DecimalFormat(unit >= 2 ? "0.0" : "0", DecimalFormatSymbols.getInstance(Locale.ROOT));
        return format.format(value) + " " + UNITS[unit];
    }
}
