package edu.learnsphere.core.service;

import edu.learnsphere.core.model.Profile;
import java.util.Locale;
import java.util.Objects;

/**
 * A profile as the profile picker shows it.
 *
 * @param passwordProtected true when opening it asks for a password
 * @param lastOpenedAt      when it was last opened, or null
 */
public record ProfileSummary(String id, String displayName, int avatarHue, boolean passwordProtected, long createdAt,
                             Long lastOpenedAt) {

    public ProfileSummary {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(displayName, "displayName");
    }

    /** The summary of a stored profile. */
    public static ProfileSummary of(Profile profile) {
        return new ProfileSummary(profile.id(), profile.displayName(), profile.avatarHue(), profile.hasPassword(),
                profile.createdAt(), profile.lastOpenedAt());
    }

    /** One or two capital letters for the avatar, e.g. "Ada Lovelace" → "AL". */
    public String initials() {
        String[] words = displayName.strip().split("\\s+");
        if (words.length == 0 || words[0].isEmpty()) {
            return "?";
        }
        String first = words[0].substring(0, words[0].offsetByCodePoints(0, 1));
        if (words.length == 1) {
            return first.toUpperCase(Locale.ROOT);
        }
        String last = words[words.length - 1];
        return (first + last.substring(0, last.offsetByCodePoints(0, 1))).toUpperCase(Locale.ROOT);
    }
}
