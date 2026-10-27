package edu.learnsphere.core.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import edu.learnsphere.core.AppPaths;
import edu.learnsphere.core.error.ConflictException;
import edu.learnsphere.core.error.InvalidInputException;
import edu.learnsphere.core.error.UserMessage;
import edu.learnsphere.core.model.AuditLogEntry;
import edu.learnsphere.core.model.Course;
import edu.learnsphere.core.model.EntityType;
import edu.learnsphere.core.profile.ProfileRegistry;
import edu.learnsphere.core.security.PasswordHasher;
import edu.learnsphere.core.service.ProfileContext;
import edu.learnsphere.core.service.ProfileDraft;
import edu.learnsphere.core.service.ProfileService;
import edu.learnsphere.core.service.ProfileServices;
import edu.learnsphere.core.service.ProfileSummary;
import edu.learnsphere.core.service.Workspace;
import edu.learnsphere.core.testing.MutableClock;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ProfileServiceImplTest {

    @TempDir
    Path home;

    private final MutableClock clock = MutableClock.fixed();
    private final List<ProfileContext> opened = new ArrayList<>();
    private AppPaths paths;
    private ProfileService profiles;

    @BeforeEach
    void setUp() {
        paths = AppPaths.at(home);
        profiles = ProfileServices.create(paths, new ProfileRegistry(paths.profilesFile()), new PasswordHasher(1_000), clock,
                context -> {
                    opened.add(context);
                    Workspace workspace = mock(Workspace.class);
                    when(workspace.profile()).thenReturn(context.profile());
                    when(workspace.events()).thenReturn(context.events());
                    return workspace;
                });
    }

    @Test
    void createsRenamesAndListsProfilesMostRecentlyOpenedFirst() {
        ProfileSummary ada = profiles.create(ProfileDraft.of("  Ada Lovelace ", 210));
        clock.advance(Duration.ofMinutes(1));
        ProfileSummary alan = profiles.create(ProfileDraft.of("Alan", 30));

        assertThat(ada.displayName()).isEqualTo("Ada Lovelace");
        assertThat(paths.profile(ada.id()).exists()).isTrue();

        clock.advance(Duration.ofMinutes(1));
        profiles.open(ada.id(), null).close();
        assertThat(profiles.list()).extracting(ProfileSummary::id).containsExactly(ada.id(), alan.id());

        assertThat(profiles.rename(alan.id(), "Alan Turing").displayName()).isEqualTo("Alan Turing");
        assertThat(profiles.setAvatarHue(alan.id(), 300).avatarHue()).isEqualTo(300);
        assertThat(profiles.find(alan.id())).get().extracting(ProfileSummary::displayName).isEqualTo("Alan Turing");
    }

    @Test
    void rejectsBlankNamesAndColoursOutsideTheWheel() {
        assertThatThrownBy(() -> profiles.create(ProfileDraft.of("   ", 10))).isInstanceOf(InvalidInputException.class);
        assertThatThrownBy(() -> profiles.create(ProfileDraft.of("Ada", 360))).isInstanceOf(InvalidInputException.class);
        ProfileSummary ada = profiles.create(ProfileDraft.of("Ada", 10));
        assertThatThrownBy(() -> profiles.rename(ada.id(), "")).isInstanceOf(InvalidInputException.class);
    }

    @Test
    void checksChangesAndClearsPasswordsAndWipesEveryArray() {
        char[] initial = "first".toCharArray();
        ProfileSummary ada = profiles.create(new ProfileDraft("Ada", 10, initial));
        assertThat(ada.passwordProtected()).isTrue();
        assertThat(initial).containsOnly('\0');

        assertThat(profiles.checkPassword(ada.id(), "first".toCharArray())).isTrue();
        assertThat(profiles.checkPassword(ada.id(), "wrong".toCharArray())).isFalse();

        char[] wrong = "wrong".toCharArray();
        char[] next = "second".toCharArray();
        assertThatThrownBy(() -> profiles.setPassword(ada.id(), wrong, next))
                .isInstanceOf(InvalidInputException.class)
                .extracting(e -> ((InvalidInputException) e).userMessage())
                .isEqualTo(UserMessage.WRONG_PASSWORD);
        assertThat(wrong).containsOnly('\0');
        assertThat(next).containsOnly('\0');

        profiles.setPassword(ada.id(), "first".toCharArray(), "second".toCharArray());
        assertThatThrownBy(() -> profiles.open(ada.id(), "first".toCharArray())).isInstanceOf(InvalidInputException.class);
        profiles.open(ada.id(), "second".toCharArray()).close();

        assertThat(profiles.clearPassword(ada.id(), "second".toCharArray()).passwordProtected()).isFalse();
        assertThat(profiles.checkPassword(ada.id(), null)).isTrue();
    }

    @Test
    void opensAProfileOnceAndReleasesItWhenTheWorkspaceCloses() {
        ProfileSummary ada = profiles.create(ProfileDraft.of("Ada", 10));
        Workspace workspace = profiles.open(ada.id(), null);

        assertThat(workspace.profile().lastOpenedAt()).isEqualTo(clock.millis());
        assertThat(Files.exists(paths.profile(ada.id()).database())).isTrue();
        assertThatThrownBy(() -> profiles.open(ada.id(), null)).isInstanceOf(ConflictException.class);
        assertThatThrownBy(() -> profiles.delete(ada.id())).isInstanceOf(ConflictException.class);

        workspace.close();
        workspace.close();
        profiles.open(ada.id(), null).close();
        profiles.delete(ada.id());

        assertThat(profiles.list()).isEmpty();
        assertThat(paths.profile(ada.id()).exists()).isFalse();
    }

    @Test
    void aFailingWorkspaceFactoryLeavesTheProfileClosed() {
        ProfileService failing = ProfileServices.create(paths, new ProfileRegistry(paths.profilesFile()),
                new PasswordHasher(1_000), clock, context -> {
                    throw new IllegalStateException("broken");
                });
        ProfileSummary ada = failing.create(ProfileDraft.of("Ada", 10));

        assertThatThrownBy(() -> failing.open(ada.id(), null)).isInstanceOf(IllegalStateException.class);
        failing.delete(ada.id());
        assertThat(failing.list()).isEmpty();
    }

    @Test
    void restoresABackupAsAnotherProfileWithItsCoursesAndFiles() throws IOException {
        ProfileSummary ada = profiles.create(new ProfileDraft("Ada", 10, "secret".toCharArray()));
        Course course;
        try (Workspace _ = profiles.open(ada.id(), "secret".toCharArray())) {
            ProfileContext context = opened.getLast();
            course = Course.newDraft("Biology", "Ada", 120, clock.millis());
            context.repositories().courses().insert(course);
            Files.writeString(paths.profile(ada.id()).filesDir().resolve("a".repeat(64) + ".pdf"), "%PDF-1.7");
            // Backups work while the profile is open.
            profiles.backup(ada.id(), home.resolve("out").resolve("ada.zip"));
        }

        ProfileSummary restored = profiles.restore(home.resolve("out").resolve("ada.zip"));

        assertThat(restored.id()).isNotEqualTo(ada.id());
        assertThat(restored.displayName()).isEqualTo("Ada");
        assertThat(restored.passwordProtected()).isTrue();
        assertThat(paths.profile(restored.id()).filesDir().resolve("a".repeat(64) + ".pdf")).hasContent("%PDF-1.7");
        try (Workspace _ = profiles.open(restored.id(), "secret".toCharArray())) {
            assertThat(opened.getLast().repositories().courses().find(course.id())).isPresent();
            assertThat(opened.getLast().repositories().audit().listForEntity(EntityType.PROFILE, restored.id()))
                    .extracting(AuditLogEntry::action, AuditLogEntry::detail)
                    .containsExactly(tuple(AuditAction.RESTORE.code(), ada.id()));
        }
        try (Workspace _ = profiles.open(ada.id(), "secret".toCharArray())) {
            assertThat(opened.getLast().repositories().audit().listForEntity(EntityType.PROFILE, ada.id()))
                    .extracting(AuditLogEntry::action, AuditLogEntry::detail)
                    .containsExactly(tuple(AuditAction.BACKUP.code(), "ada.zip"));
        }
    }

    @Test
    void refusesFilesThatAreNotBackupsAndEntriesThatEscapeTheFolder() throws IOException {
        Path text = Files.writeString(home.resolve("notes.zip"), "just text");
        assertThatThrownBy(() -> profiles.restore(text)).isInstanceOf(InvalidInputException.class);

        Path noManifest = zip(home.resolve("empty.zip"), "files/x.pdf", "x");
        assertThatThrownBy(() -> profiles.restore(noManifest)).isInstanceOf(InvalidInputException.class);

        ProfileSummary ada = profiles.create(ProfileDraft.of("Ada", 10));
        Path good = home.resolve("ada.zip");
        profiles.backup(ada.id(), good);
        String manifest;
        try (var zip = new java.util.zip.ZipFile(good.toFile())) {
            manifest = new String(zip.getInputStream(zip.getEntry(ProfileServiceImpl.MANIFEST)).readAllBytes(),
                    StandardCharsets.UTF_8);
        }
        Path evil = home.resolve("evil.zip");
        try (OutputStream out = Files.newOutputStream(evil); ZipOutputStream zip = new ZipOutputStream(out)) {
            zip.putNextEntry(new ZipEntry(ProfileServiceImpl.MANIFEST));
            zip.write(manifest.getBytes(StandardCharsets.UTF_8));
            zip.putNextEntry(new ZipEntry("files/../../escaped.pdf"));
            zip.write(1);
        }
        assertThatThrownBy(() -> profiles.restore(evil)).isInstanceOf(InvalidInputException.class);
        assertThat(home.resolve("escaped.pdf")).doesNotExist();
        assertThat(profiles.list()).hasSize(1);
    }

    private static Path zip(Path file, String entry, String content) throws IOException {
        try (OutputStream out = Files.newOutputStream(file); ZipOutputStream zip = new ZipOutputStream(out)) {
            zip.putNextEntry(new ZipEntry(entry));
            zip.write(content.getBytes(StandardCharsets.UTF_8));
        }
        return file;
    }
}
