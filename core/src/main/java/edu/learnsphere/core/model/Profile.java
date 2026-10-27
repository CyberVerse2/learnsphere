package edu.learnsphere.core.model;

import edu.learnsphere.core.util.Ids;
import java.util.Objects;

/**
 * An entry of {@code profiles.json}. Each profile has its own folder and database.
 *
 * @param displayName  name shown on the profile chip
 * @param avatarHue    hue 0–359 of the avatar
 * @param passwordHash encoded PBKDF2 hash from {@code PasswordHasher}, or null when there is no password
 * @param lastOpenedAt when it was last opened, or null
 */
public record Profile(String id, String displayName, int avatarHue, String passwordHash, long createdAt, Long lastOpenedAt) {

    public Profile {
        Ids.requireValid(id);
        Objects.requireNonNull(displayName, "displayName");
        if (avatarHue < 0 || avatarHue > 359) {
            throw new IllegalArgumentException("avatarHue must be 0-359: " + avatarHue);
        }
    }

    /** A new profile with a fresh id and no password. */
    public static Profile create(String displayName, int avatarHue, long now) {
        return new Profile(Ids.newId(), displayName, avatarHue, null, now, null);
    }

    /** True when opening the profile needs a password. */
    public boolean hasPassword() {
        return passwordHash != null;
    }

    public Profile withDisplayName(String name) {
        return new Profile(id, name, avatarHue, passwordHash, createdAt, lastOpenedAt);
    }

    public Profile withAvatarHue(int hue) {
        return new Profile(id, displayName, hue, passwordHash, createdAt, lastOpenedAt);
    }

    public Profile withPasswordHash(String hash) {
        return new Profile(id, displayName, avatarHue, hash, createdAt, lastOpenedAt);
    }

    public Profile withLastOpenedAt(long time) {
        return new Profile(id, displayName, avatarHue, passwordHash, createdAt, time);
    }
}
