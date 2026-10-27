package edu.learnsphere.app.ui.settings;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class StorageUsageTest {

    @TempDir
    Path dir;

    @Test
    void addsUpTheFilesInAFolder() throws IOException {
        Files.write(dir.resolve("a.bin"), new byte[1500]);
        Files.createDirectories(dir.resolve("inner"));
        Files.write(dir.resolve("inner").resolve("b.bin"), new byte[500]);
        assertThat(StorageUsage.bytesIn(dir)).isEqualTo(2000);
        assertThat(StorageUsage.bytesIn(dir.resolve("missing"))).isZero();
    }

    @Test
    void readsInTheLargestFittingUnit() {
        assertThat(StorageUsage.format(0)).isEqualTo("0 bytes");
        assertThat(StorageUsage.format(1)).isEqualTo("1 byte");
        assertThat(StorageUsage.format(1023)).isEqualTo("1023 bytes");
        assertThat(StorageUsage.format(2048)).isEqualTo("2 KB");
        assertThat(StorageUsage.format(13_002_342)).isEqualTo("12.4 MB");
        assertThat(StorageUsage.format(3L * 1024 * 1024 * 1024)).isEqualTo("3.0 GB");
    }
}
