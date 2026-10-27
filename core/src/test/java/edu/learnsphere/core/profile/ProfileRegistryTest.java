package edu.learnsphere.core.profile;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import edu.learnsphere.core.error.LearnSphereException;
import edu.learnsphere.core.error.NotFoundException;
import edu.learnsphere.core.model.Profile;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ProfileRegistryTest {

    @TempDir
    Path dir;

    @Test
    void startsEmptyWithoutAFile() {
        ProfileRegistry registry = new ProfileRegistry(dir.resolve("profiles.json"));
        assertThat(registry.isEmpty()).isTrue();
        assertThat(registry.list()).isEmpty();
        assertThat(Files.exists(registry.file())).isFalse();
    }

    @Test
    void savesAndReloadsProfiles() {
        Path file = dir.resolve("profiles.json");
        ProfileRegistry registry = new ProfileRegistry(file);
        Profile amara = Profile.create("Amara", 210, 1000);
        Profile ben = Profile.create("Ben", 30, 2000).withPasswordHash("pbkdf2-sha256$1000$abc$def");
        registry.save(amara);
        registry.save(ben);

        ProfileRegistry reloaded = new ProfileRegistry(file);
        assertThat(reloaded.get(amara.id())).isEqualTo(amara);
        assertThat(reloaded.get(ben.id()).hasPassword()).isTrue();
        assertThat(reloaded.list()).hasSize(2);
        assertThat(file).content().contains("\"version\"").doesNotContain("Amara\n\"");
    }

    @Test
    void listsLastOpenedFirstThenByName() {
        ProfileRegistry registry = new ProfileRegistry(dir.resolve("profiles.json"));
        Profile zoe = Profile.create("Zoe", 1, 1);
        Profile adam = Profile.create("adam", 1, 1);
        Profile mia = Profile.create("Mia", 1, 1).withLastOpenedAt(500L);
        registry.save(zoe);
        registry.save(adam);
        registry.save(mia);

        assertThat(registry.list()).extracting(Profile::displayName).containsExactly("Mia", "adam", "Zoe");
    }

    @Test
    void updatesAndRemoves() {
        Path file = dir.resolve("profiles.json");
        ProfileRegistry registry = new ProfileRegistry(file);
        Profile profile = Profile.create("Amara", 210, 1000);
        registry.save(profile);

        Profile renamed = registry.update(profile.id(), p -> p.withDisplayName("Amara O."));
        assertThat(renamed.displayName()).isEqualTo("Amara O.");
        assertThat(new ProfileRegistry(file).get(profile.id()).displayName()).isEqualTo("Amara O.");

        assertThat(registry.remove(profile.id())).isTrue();
        assertThat(registry.remove(profile.id())).isFalse();
        assertThat(new ProfileRegistry(file).isEmpty()).isTrue();
        assertThatThrownBy(() -> registry.get(profile.id())).isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> registry.update(profile.id(), p -> p)).isInstanceOf(NotFoundException.class);
    }

    @Test
    void leavesNoTemporaryFilesBehind() throws IOException {
        ProfileRegistry registry = new ProfileRegistry(dir.resolve("profiles.json"));
        for (int i = 0; i < 5; i++) {
            registry.save(Profile.create("P" + i, i, i));
        }
        try (var files = Files.list(dir)) {
            assertThat(files.map(p -> p.getFileName().toString())).containsExactly("profiles.json");
        }
    }

    @Test
    void reportsAnUnreadableFile() throws IOException {
        Path file = dir.resolve("profiles.json");
        Files.writeString(file, "{ not json");
        assertThatThrownBy(() -> new ProfileRegistry(file)).isInstanceOf(LearnSphereException.class);
    }
}
