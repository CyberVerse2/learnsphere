package edu.learnsphere.core.service;

import java.util.Objects;

/**
 * What the "create your profile" step collects.
 *
 * @param password optional password, or null for none (the service wipes the array after hashing)
 */
public record ProfileDraft(String displayName, int avatarHue, char[] password) {

    public ProfileDraft {
        Objects.requireNonNull(displayName, "displayName");
    }

    /** A profile without a password. */
    public static ProfileDraft of(String displayName, int avatarHue) {
        return new ProfileDraft(displayName, avatarHue, null);
    }
}
