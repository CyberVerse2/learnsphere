package edu.learnsphere.core.service;

import java.util.Objects;

/**
 * What a course package contains, shown before importing (and returned after exporting).
 *
 * @param packageId        the course's stable id across copies
 * @param versionNumber    published version it contains
 * @param protectedByCode  true when importing needs the creator's code
 * @param existingCourseId the course in this profile it would update, or null for a new course
 * @param installedVersion that course's current version, or null
 * @param createdHere      true when the matching course was created in this profile; such a package
 *                         cannot be imported here
 * @param byteSize         size of the package file
 */
public record PackageInfo(String packageId, int formatVersion, String title, String subtitle, String authorName,
                          int coverHue, int versionNumber, long createdAt, int moduleCount, int itemCount,
                          int documentCount, long byteSize, boolean protectedByCode, String existingCourseId,
                          Integer installedVersion, boolean createdHere) {

    public PackageInfo {
        Objects.requireNonNull(packageId, "packageId");
        Objects.requireNonNull(title, "title");
        subtitle = Objects.requireNonNullElse(subtitle, "");
        authorName = Objects.requireNonNullElse(authorName, "");
    }

    /** True when importing updates a course already in the library. */
    public boolean isUpdate() {
        return existingCourseId != null && !createdHere;
    }

    /** True when {@code SharingService.importCourse} accepts this package. */
    public boolean canImport() {
        return !createdHere;
    }

    /** True when the package is newer than the installed copy (or there is none). */
    public boolean isNewer() {
        return installedVersion == null || versionNumber > installedVersion;
    }
}
