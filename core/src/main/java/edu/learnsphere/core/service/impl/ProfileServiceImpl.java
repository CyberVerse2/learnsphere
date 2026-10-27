package edu.learnsphere.core.service.impl;

import edu.learnsphere.core.AppPaths;
import edu.learnsphere.core.db.AuditLogRepository;
import edu.learnsphere.core.db.Database;
import edu.learnsphere.core.db.Repositories;
import edu.learnsphere.core.error.ConflictException;
import edu.learnsphere.core.error.InvalidInputException;
import edu.learnsphere.core.error.LearnSphereException;
import edu.learnsphere.core.error.UserMessage;
import edu.learnsphere.core.events.EventBus;
import edu.learnsphere.core.model.EntityType;
import edu.learnsphere.core.model.Profile;
import edu.learnsphere.core.profile.ProfileRegistry;
import edu.learnsphere.core.security.PasswordHasher;
import edu.learnsphere.core.service.ProfileContext;
import edu.learnsphere.core.service.ProfileDraft;
import edu.learnsphere.core.service.ProfileService;
import edu.learnsphere.core.service.ProfileSummary;
import edu.learnsphere.core.service.Workspace;
import edu.learnsphere.core.service.WorkspaceFactory;
import edu.learnsphere.core.util.Ids;
import edu.learnsphere.core.util.Json;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Clock;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * {@link ProfileService} over {@code profiles.json} and the profile folders. Opening a profile opens and
 * migrates its database, creates its event bus and asks the {@link WorkspaceFactory} for its services; the
 * returned workspace releases all of it when closed.
 *
 * <p>A backup zip holds {@value #MANIFEST} (the profile entry), a consistent copy of the database made
 * with {@code VACUUM INTO} (so it works while the profile is open) and the imported PDFs. The search index
 * and page renders are left out: both are rebuilt from the database.
 */
public final class ProfileServiceImpl implements ProfileService {

    /** Name of the profile entry inside a backup. */
    public static final String MANIFEST = "profile.json";
    /** Backup format version. */
    public static final int BACKUP_VERSION = 1;

    private static final Logger LOG = LoggerFactory.getLogger(ProfileServiceImpl.class);

    private static final UserMessage NAME_REQUIRED = UserMessage.of("Enter a name for your profile.");
    private static final UserMessage HUE_INVALID = UserMessage.of("Pick one of the avatar colours.");
    private static final UserMessage PASSWORD_REQUIRED = UserMessage.of("Enter a password, or remove the password instead.");
    private static final UserMessage PROFILE_OPEN = UserMessage.of("This profile is open. Close it and try again.");
    private static final UserMessage ALREADY_OPEN = UserMessage.of("This profile is already open.");
    private static final UserMessage NOT_A_BACKUP = UserMessage.of("This file isn't a LearnSphere backup.");
    private static final UserMessage BACKUP_FAILED =
            UserMessage.of("The backup couldn't be saved. Check that there's free space and try again.");
    private static final UserMessage RESTORE_FAILED =
            UserMessage.of("The backup couldn't be restored. Check that there's free space and try again.");

    private static final String DATABASE_ENTRY = Database.FILE_NAME;
    private static final String FILES_PREFIX = "files/";

    /** The JSON document stored as {@value #MANIFEST}. */
    record BackupManifest(int version, Profile profile, long createdAt) {
    }

    private final AppPaths paths;
    private final ProfileRegistry registry;
    private final PasswordHasher hasher;
    private final Clock clock;
    private final WorkspaceFactory workspaces;
    private final Set<String> open = ConcurrentHashMap.newKeySet();

    public ProfileServiceImpl(AppPaths paths, ProfileRegistry registry, PasswordHasher hasher, Clock clock,
                              WorkspaceFactory workspaces) {
        this.paths = Objects.requireNonNull(paths, "paths");
        this.registry = Objects.requireNonNull(registry, "registry");
        this.hasher = Objects.requireNonNull(hasher, "hasher");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.workspaces = Objects.requireNonNull(workspaces, "workspaces");
    }

    @Override
    public List<ProfileSummary> list() {
        return registry.list().stream().map(ProfileSummary::of).toList();
    }

    @Override
    public Optional<ProfileSummary> find(String profileId) {
        return registry.find(profileId).map(ProfileSummary::of);
    }

    @Override
    public ProfileSummary create(ProfileDraft draft) {
        char[] password = draft.password();
        try {
            String name = requireName(draft.displayName());
            requireHue(draft.avatarHue());
            Profile profile = Profile.create(name, draft.avatarHue(), clock.millis());
            if (password != null && password.length > 0) {
                profile = profile.withPasswordHash(hasher.hash(password));
            }
            paths.profile(profile.id()).root();
            registry.save(profile);
            return ProfileSummary.of(profile);
        } finally {
            PasswordHasher.wipe(password);
        }
    }

    @Override
    public ProfileSummary rename(String profileId, String displayName) {
        String name = requireName(displayName);
        return ProfileSummary.of(registry.update(profileId, p -> p.withDisplayName(name)));
    }

    @Override
    public ProfileSummary setAvatarHue(String profileId, int hue) {
        requireHue(hue);
        return ProfileSummary.of(registry.update(profileId, p -> p.withAvatarHue(hue)));
    }

    @Override
    public void delete(String profileId) {
        registry.get(profileId);
        if (open.contains(profileId)) {
            throw new ConflictException(PROFILE_OPEN, "Profile " + profileId + " is open");
        }
        registry.remove(profileId);
        AppPaths.ProfilePaths folder = paths.profile(profileId);
        if (folder.exists()) {
            try {
                deleteRecursively(folder.root());
            } catch (IOException | UncheckedIOException e) {
                LOG.warn("Profile {} was removed but its folder could not be deleted completely", profileId, e);
            }
        }
    }

    @Override
    public ProfileSummary setPassword(String profileId, char[] currentPassword, char[] newPassword) {
        try {
            Profile profile = registry.get(profileId);
            requireCurrentPassword(profile, currentPassword);
            if (newPassword == null || newPassword.length == 0) {
                throw new InvalidInputException(PASSWORD_REQUIRED, "Empty new password");
            }
            String hash = hasher.hash(newPassword);
            return ProfileSummary.of(registry.update(profileId, p -> p.withPasswordHash(hash)));
        } finally {
            PasswordHasher.wipe(currentPassword);
            PasswordHasher.wipe(newPassword);
        }
    }

    @Override
    public ProfileSummary clearPassword(String profileId, char[] currentPassword) {
        try {
            Profile profile = registry.get(profileId);
            requireCurrentPassword(profile, currentPassword);
            return ProfileSummary.of(registry.update(profileId, p -> p.withPasswordHash(null)));
        } finally {
            PasswordHasher.wipe(currentPassword);
        }
    }

    @Override
    public boolean checkPassword(String profileId, char[] password) {
        try {
            Profile profile = registry.get(profileId);
            return !profile.hasPassword() || hasher.verify(password, profile.passwordHash());
        } finally {
            PasswordHasher.wipe(password);
        }
    }

    @Override
    public Workspace open(String profileId, char[] password) {
        try {
            Profile profile = registry.get(profileId);
            requireCurrentPassword(profile, password);
            if (profile.hasPassword() && hasher.needsRehash(profile.passwordHash())) {
                String stronger = hasher.hash(password);
                registry.update(profileId, p -> p.withPasswordHash(stronger));
            }
        } finally {
            PasswordHasher.wipe(password);
        }
        if (!open.add(profileId)) {
            throw new ConflictException(ALREADY_OPEN, "Profile " + profileId + " is already open");
        }
        Database database = null;
        EventBus events = null;
        try {
            AppPaths.ProfilePaths folder = paths.profile(profileId);
            database = openDatabase(folder.database());
            events = new EventBus();
            Profile opened = registry.update(profileId, p -> p.withLastOpenedAt(clock.millis()));
            ProfileContext context = new ProfileContext(ProfileSummary.of(opened), folder,
                    new Repositories(database, clock), events, clock);
            Workspace workspace = Objects.requireNonNull(workspaces.open(context), "workspace");
            Database ownedDatabase = database;
            EventBus ownedEvents = events;
            return new TrackedWorkspace(workspace, () -> {
                ownedEvents.close();
                ownedDatabase.close();
                open.remove(profileId);
            });
        } catch (RuntimeException e) {
            if (events != null) {
                events.close();
            }
            if (database != null) {
                database.close();
            }
            open.remove(profileId);
            throw e;
        }
    }

    private Database openDatabase(Path file) {
        try {
            return Database.open(file, clock);
        } catch (LearnSphereException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new LearnSphereException(UserMessage.PROFILE_NOT_OPENED, "Cannot open " + file, e);
        }
    }

    @Override
    public void backup(String profileId, Path zipFile) {
        Profile profile = registry.get(profileId);
        AppPaths.ProfilePaths folder = paths.profile(profileId);
        Path target = zipFile.toAbsolutePath();
        Path snapshot = null;
        Path partial = null;
        try {
            Path parent = Files.createDirectories(target.getParent());
            partial = Files.createTempFile(parent, ".backup-", ".partial");
            if (Files.exists(folder.database())) {
                snapshot = Files.createTempFile(folder.root(), ".backup-", ".db");
                Files.delete(snapshot);
                snapshotDatabase(folder.database(), snapshot);
            }
            try (OutputStream out = Files.newOutputStream(partial); ZipOutputStream zip = new ZipOutputStream(out)) {
                BackupManifest manifest = new BackupManifest(BACKUP_VERSION, profile, clock.millis());
                zip.putNextEntry(new ZipEntry(MANIFEST));
                zip.write(Json.writePretty(manifest).getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
                if (snapshot != null) {
                    zip.putNextEntry(new ZipEntry(DATABASE_ENTRY));
                    Files.copy(snapshot, zip);
                    zip.closeEntry();
                }
                Path files = folder.filesDir();
                try (Stream<Path> pdfs = Files.list(files)) {
                    for (Path pdf : pdfs.filter(Files::isRegularFile).sorted().toList()) {
                        zip.putNextEntry(new ZipEntry(FILES_PREFIX + pdf.getFileName()));
                        Files.copy(pdf, zip);
                        zip.closeEntry();
                    }
                }
            }
            Files.move(partial, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            partial = null;
            recordAudit(folder.database(), AuditAction.BACKUP, profileId, target.getFileName().toString());
        } catch (IOException | UncheckedIOException | org.jdbi.v3.core.JdbiException e) {
            throw new LearnSphereException(BACKUP_FAILED, "Backup of profile " + profileId + " failed", e);
        } finally {
            deleteQuietly(snapshot);
            deleteQuietly(partial);
        }
    }

    /**
     * Appends an entry to the audit log of the profile database at {@code database}, when it exists. The
     * backup or restore it records has already succeeded, so a failure here is logged, never thrown.
     */
    private void recordAudit(Path database, AuditAction action, String profileId, String detail) {
        if (!Files.isRegularFile(database)) {
            return;
        }
        try (Database db = Database.openWithoutMigrations(database)) {
            action.record(new AuditLogRepository(db, clock), EntityType.PROFILE, profileId, detail);
        } catch (RuntimeException e) {
            LOG.warn("Could not record the {} of profile {}", action.code(), profileId, e);
        }
    }

    private static void snapshotDatabase(Path database, Path snapshot) {
        try (Database source = Database.openWithoutMigrations(database)) {
            source.useHandle(h -> h.createUpdate("VACUUM INTO :target").bind("target", snapshot.toString()).execute());
        }
    }

    @Override
    public ProfileSummary restore(Path zipFile) {
        BackupManifest manifest;
        Path staging = null;
        try (ZipFile zip = new ZipFile(zipFile.toFile())) {
            manifest = readManifest(zip);
            Profile original = manifest.profile();
            String id = registry.find(original.id()).isPresent() || paths.profile(original.id()).exists()
                    ? Ids.newId() : original.id();
            staging = Files.createTempDirectory(paths.profilesDir(), ".restore-");
            extract(zip, staging);
            Path destination = paths.profilesDir().resolve(id);
            Files.move(staging, destination, StandardCopyOption.ATOMIC_MOVE);
            staging = null;
            Profile restored = new Profile(id, original.displayName(), original.avatarHue(), original.passwordHash(),
                    original.createdAt(), original.lastOpenedAt());
            registry.save(restored);
            recordAudit(paths.profile(id).database(), AuditAction.RESTORE, id, original.id());
            return ProfileSummary.of(restored);
        } catch (java.util.zip.ZipException e) {
            throw new InvalidInputException(NOT_A_BACKUP, "Not a zip file: " + zipFile, e);
        } catch (FileAlreadyExistsException e) {
            throw new LearnSphereException(RESTORE_FAILED, "Restore target exists", e);
        } catch (IOException | UncheckedIOException e) {
            throw new LearnSphereException(RESTORE_FAILED, "Restore of " + zipFile + " failed", e);
        } finally {
            if (staging != null) {
                try {
                    deleteRecursively(staging);
                } catch (IOException | UncheckedIOException e) {
                    LOG.warn("Could not remove {}", staging, e);
                }
            }
        }
    }

    private static BackupManifest readManifest(ZipFile zip) throws IOException {
        ZipEntry entry = zip.getEntry(MANIFEST);
        if (entry == null) {
            throw new InvalidInputException(NOT_A_BACKUP, "No " + MANIFEST + " in " + zip.getName());
        }
        try (InputStream in = zip.getInputStream(entry)) {
            BackupManifest manifest = Json.read(new String(in.readAllBytes(), StandardCharsets.UTF_8), BackupManifest.class);
            if (manifest.profile() == null || manifest.version() < 1 || manifest.version() > BACKUP_VERSION) {
                throw new InvalidInputException(NOT_A_BACKUP, "Unsupported backup manifest in " + zip.getName());
            }
            return manifest;
        } catch (IllegalArgumentException | UncheckedIOException e) {
            throw new InvalidInputException(NOT_A_BACKUP, "Unreadable " + MANIFEST + " in " + zip.getName(), e);
        }
    }

    /** Copies the database and PDFs into {@code folder}, refusing entries that would land outside it. */
    private static void extract(ZipFile zip, Path folder) throws IOException {
        Path root = folder.toAbsolutePath().normalize();
        Enumeration<? extends ZipEntry> entries = zip.entries();
        while (entries.hasMoreElements()) {
            ZipEntry entry = entries.nextElement();
            String name = entry.getName();
            if (entry.isDirectory() || name.equals(MANIFEST)) {
                continue;
            }
            Path target = root.resolve(name).normalize();
            if (!target.startsWith(root) || target.equals(root)) {
                throw new InvalidInputException(NOT_A_BACKUP, "Entry outside the profile folder: " + name);
            }
            boolean wanted = name.equals(DATABASE_ENTRY)
                    || (name.startsWith(FILES_PREFIX) && name.indexOf('/', FILES_PREFIX.length()) < 0);
            if (!wanted) {
                LOG.info("Skipping unexpected backup entry {}", name);
                continue;
            }
            Files.createDirectories(target.getParent());
            try (InputStream in = zip.getInputStream(entry)) {
                Files.copy(in, target);
            }
        }
    }

    private static String requireName(String displayName) {
        String name = displayName == null ? "" : displayName.strip();
        if (name.isEmpty()) {
            throw new InvalidInputException(NAME_REQUIRED, "Blank profile name");
        }
        return name;
    }

    private static void requireHue(int hue) {
        if (hue < 0 || hue > 359) {
            throw new InvalidInputException(HUE_INVALID, "Hue out of range: " + hue);
        }
    }

    private void requireCurrentPassword(Profile profile, char[] password) {
        if (profile.hasPassword() && !hasher.verify(password, profile.passwordHash())) {
            throw new InvalidInputException(UserMessage.WRONG_PASSWORD, "Wrong password for profile " + profile.id());
        }
    }

    private static void deleteRecursively(Path folder) throws IOException {
        if (!Files.exists(folder)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(folder)) {
            for (Path path : walk.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }

    private static void deleteQuietly(Path file) {
        if (file == null) {
            return;
        }
        try {
            Files.deleteIfExists(file);
        } catch (IOException e) {
            LOG.debug("Could not delete {}", file, e);
        }
    }
}
