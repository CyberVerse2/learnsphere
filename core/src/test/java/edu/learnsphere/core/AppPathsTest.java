package edu.learnsphere.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import edu.learnsphere.core.util.Ids;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Properties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AppPathsTest {

    @TempDir
    Path dir;

    private static Properties props(String os, String home) {
        Properties properties = new Properties();
        properties.setProperty("os.name", os);
        properties.setProperty("user.home", home);
        return properties;
    }

    @Test
    void resolvesThePlatformHome() {
        assertThat(AppPaths.resolveHome(props("Mac OS X", "/Users/a"), Map.of()))
                .isEqualTo(Path.of("/Users/a/Library/Application Support/LearnSphere"));
        assertThat(AppPaths.resolveHome(props("Windows 11", "C:\\Users\\a"), Map.of("LOCALAPPDATA", "/local")))
                .isEqualTo(Path.of("/local/LearnSphere"));
        assertThat(AppPaths.resolveHome(props("Linux", "/home/a"), Map.of()))
                .isEqualTo(Path.of("/home/a/.local/share/learnsphere"));
        assertThat(AppPaths.resolveHome(props("Linux", "/home/a"), Map.of("XDG_DATA_HOME", "/data")))
                .isEqualTo(Path.of("/data/learnsphere"));
    }

    @Test
    void honoursTheHomeOverride() {
        Properties properties = props("Linux", "/home/a");
        properties.setProperty(AppPaths.HOME_PROPERTY, "/tmp/ls-test");
        assertThat(AppPaths.resolveHome(properties, Map.of())).isEqualTo(Path.of("/tmp/ls-test"));
    }

    @Test
    void laysOutProfileFolders() {
        AppPaths paths = AppPaths.at(dir);
        String id = Ids.newId();
        AppPaths.ProfilePaths profile = paths.profile(id);

        assertThat(paths.profilesFile()).isEqualTo(dir.resolve("profiles.json"));
        assertThat(profile.exists()).isFalse();
        assertThat(profile.database()).isEqualTo(dir.resolve("profiles").resolve(id).resolve("learnsphere.db"));
        assertThat(profile.exists()).isTrue();
        assertThat(profile.indexDir()).isDirectory();
        String sha = "a".repeat(64);
        assertThat(profile.pdfFile(sha)).isEqualTo(profile.filesDir().resolve(sha + ".pdf"));
        assertThat(profile.renderFile(id, 12, 2f).getFileName().toString()).isEqualTo("12@2.00.png");
        assertThat(Files.isDirectory(paths.logsDir())).isTrue();
    }

    @Test
    void rejectsUnsafeNames() {
        AppPaths paths = AppPaths.at(dir);
        assertThatThrownBy(() -> paths.profile("../escape")).isInstanceOf(IllegalArgumentException.class);
        String id = Ids.newId();
        assertThatThrownBy(() -> paths.profile(id).pdfFile("../../x")).isInstanceOf(IllegalArgumentException.class);
    }
}
