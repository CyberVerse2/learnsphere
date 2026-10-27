package edu.learnsphere.core.service;

import edu.learnsphere.core.error.ConflictException;
import edu.learnsphere.core.error.InvalidInputException;
import edu.learnsphere.core.error.NotFoundException;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/**
 * App-wide profile management (the only service that exists before a profile is opened). Each profile
 * is a folder with its own database, index and files, so profiles never see each other's data.
 *
 * <p>Password arrays passed in are wiped by the service after use.
 */
public interface ProfileService {

    /** Every profile, most recently opened first. */
    List<ProfileSummary> list();

    /** The profile with this id, if it exists. */
    Optional<ProfileSummary> find(String profileId);

    /**
     * Creates a profile and its folder.
     *
     * @throws InvalidInputException when the name is blank or the hue is outside 0–359
     */
    ProfileSummary create(ProfileDraft draft);

    /** @throws InvalidInputException when the name is blank */
    ProfileSummary rename(String profileId, String displayName);

    /** Sets the avatar colour (hue 0–359). */
    ProfileSummary setAvatarHue(String profileId, int hue);

    /**
     * Deletes a profile and everything in its folder.
     *
     * @throws ConflictException when the profile is open
     */
    void delete(String profileId);

    /**
     * Sets or changes the password.
     *
     * @param currentPassword the current password, or null when there is none
     * @throws InvalidInputException when the current password is wrong
     */
    ProfileSummary setPassword(String profileId, char[] currentPassword, char[] newPassword);

    /**
     * Removes the password.
     *
     * @throws InvalidInputException when {@code currentPassword} is wrong
     */
    ProfileSummary clearPassword(String profileId, char[] currentPassword);

    /** True when {@code password} opens the profile (always true for a profile without a password). */
    boolean checkPassword(String profileId, char[] password);

    /**
     * Opens a profile: checks the password, opens and migrates its database, opens its index, starts the
     * study assistant in the background and resumes unfinished jobs. Close the workspace to release it.
     *
     * @param password the password, or null for a profile without one
     * @throws InvalidInputException when the password is wrong
     * @throws NotFoundException     when the profile does not exist
     * @throws ConflictException     when the profile is already open
     */
    Workspace open(String profileId, char[] password);

    /**
     * Writes the whole profile folder to a zip file at {@code zipFile}. Works whether or not the profile
     * is open.
     */
    void backup(String profileId, Path zipFile);

    /**
     * Restores a profile from a backup zip as an additional profile (with a new id when the original id
     * is already in use).
     *
     * @throws InvalidInputException when the file is not a LearnSphere backup
     */
    ProfileSummary restore(Path zipFile);
}
