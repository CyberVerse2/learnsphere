package edu.learnsphere.core.profile;

import edu.learnsphere.core.error.LearnSphereException;
import edu.learnsphere.core.error.NotFoundException;
import edu.learnsphere.core.error.UserMessage;
import edu.learnsphere.core.model.Profile;
import edu.learnsphere.core.util.Json;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.UnaryOperator;

/**
 * The list of profiles in {@code profiles.json}. Reads the file once, keeps it in memory and writes the
 * whole file on every change: to a temporary file in the same folder, then moved over the old one, so a
 * crash never leaves a half-written registry.
 *
 * <p>File format: {@code {"version": 1, "profiles": [{id, displayName, avatarHue, passwordHash,
 * createdAt, lastOpenedAt}, …]}}. Thread-safe.
 */
public final class ProfileRegistry {

    /** Current file format version. */
    public static final int FORMAT_VERSION = 1;

    private static final UserMessage CANNOT_READ = UserMessage.of("Your profiles couldn't be loaded. Restart LearnSphere and try again.");
    private static final UserMessage CANNOT_SAVE = UserMessage.of("Your profile changes couldn't be saved. Check that there's free space and try again.");

    /** The JSON document. */
    record RegistryFile(int version, List<Profile> profiles) {
    }

    private final Path file;
    private final Map<String, Profile> profiles = new LinkedHashMap<>();

    /**
     * Loads the registry from {@code file}; a missing file is an empty registry.
     *
     * @throws LearnSphereException when the file exists but cannot be read or parsed
     */
    public ProfileRegistry(Path file) {
        this.file = Objects.requireNonNull(file, "file");
        load();
    }

    /** The registry file. */
    public Path file() {
        return file;
    }

    /** Every profile, most recently opened first, then by name. */
    public synchronized List<Profile> list() {
        List<Profile> result = new ArrayList<>(profiles.values());
        result.sort(Comparator.comparing((Profile p) -> p.lastOpenedAt() == null ? Long.MIN_VALUE : p.lastOpenedAt())
                .reversed()
                .thenComparing(Profile::displayName, String.CASE_INSENSITIVE_ORDER));
        return List.copyOf(result);
    }

    public synchronized Optional<Profile> find(String id) {
        return Optional.ofNullable(profiles.get(id));
    }

    /** @throws NotFoundException when there is no such profile */
    public synchronized Profile get(String id) {
        return find(id).orElseThrow(() -> NotFoundException.of(UserMessage.PROFILE_NOT_FOUND, "profile", id));
    }

    public synchronized boolean isEmpty() {
        return profiles.isEmpty();
    }

    /** Adds or replaces a profile and writes the file. */
    public synchronized void save(Profile profile) {
        Objects.requireNonNull(profile, "profile");
        Map<String, Profile> next = new LinkedHashMap<>(profiles);
        next.put(profile.id(), profile);
        write(next);
        profiles.clear();
        profiles.putAll(next);
    }

    /**
     * Applies {@code change} to a profile, writes the file and returns the new value.
     *
     * @throws NotFoundException when there is no such profile
     */
    public synchronized Profile update(String id, UnaryOperator<Profile> change) {
        Profile updated = change.apply(get(id));
        if (!updated.id().equals(id)) {
            throw new IllegalArgumentException("A profile's id cannot change");
        }
        save(updated);
        return updated;
    }

    /** Removes a profile from the registry (its folder is the caller's to delete). */
    public synchronized boolean remove(String id) {
        if (!profiles.containsKey(id)) {
            return false;
        }
        Map<String, Profile> next = new LinkedHashMap<>(profiles);
        next.remove(id);
        write(next);
        profiles.remove(id);
        return true;
    }

    private void load() {
        if (!Files.exists(file)) {
            return;
        }
        try {
            String json = Files.readString(file, StandardCharsets.UTF_8);
            RegistryFile stored = Json.read(json, RegistryFile.class);
            if (stored.profiles() != null) {
                for (Profile profile : stored.profiles()) {
                    profiles.put(profile.id(), profile);
                }
            }
        } catch (IOException | UncheckedIOException | IllegalArgumentException e) {
            throw new LearnSphereException(CANNOT_READ, "Cannot read " + file, e);
        }
    }

    private void write(Map<String, Profile> content) {
        Path temp = null;
        try {
            Path folder = file.toAbsolutePath().getParent();
            Files.createDirectories(folder);
            temp = Files.createTempFile(folder, "profiles", ".json.tmp");
            String json = Json.writePretty(new RegistryFile(FORMAT_VERSION, List.copyOf(content.values())));
            Files.writeString(temp, json, StandardCharsets.UTF_8);
            try {
                Files.move(temp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            if (temp != null) {
                try {
                    Files.deleteIfExists(temp);
                } catch (IOException ignored) {
                    // best effort
                }
            }
            throw new LearnSphereException(CANNOT_SAVE, "Cannot write " + file, e);
        }
    }
}
