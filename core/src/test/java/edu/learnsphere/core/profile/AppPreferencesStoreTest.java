package edu.learnsphere.core.profile;

import static org.assertj.core.api.Assertions.assertThat;

import edu.learnsphere.core.service.ThemePreference;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AppPreferencesStoreTest {

    @TempDir
    Path dir;

    @Test
    void startsWithDefaultsAndPersistsChanges() {
        Path file = dir.resolve("app-settings.json");
        AppPreferencesStore store = new AppPreferencesStore(file);
        assertThat(store.get()).isEqualTo(AppPreferences.DEFAULTS);

        List<AppPreferences> seen = new ArrayList<>();
        store.addListener(seen::add);
        store.update(p -> p.withTheme(ThemePreference.DARK).withReduceMotion(true).withLastProfileId("p-1"));
        store.update(p -> p.withTheme(ThemePreference.DARK));

        AppPreferences reloaded = new AppPreferencesStore(file).get();
        assertThat(reloaded).isEqualTo(new AppPreferences(ThemePreference.DARK, true, "p-1", false));
        assertThat(seen).as("unchanged updates are not announced").hasSize(1);
    }

    @Test
    void anUnreadableFileGivesTheDefaults() throws Exception {
        Path file = dir.resolve("app-settings.json");
        Files.writeString(file, "{ not json");
        assertThat(new AppPreferencesStore(file).get()).isEqualTo(AppPreferences.DEFAULTS);
        Files.writeString(file, "{\"version\":1,\"theme\":\"sepia\"}");
        assertThat(new AppPreferencesStore(file).get()).isEqualTo(AppPreferences.DEFAULTS);
    }
}
