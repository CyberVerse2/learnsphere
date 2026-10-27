package edu.learnsphere.core.model;

/** A row of {@code course_version_items}: an approved item contained in a published version. */
public record CourseVersionItem(String versionId, String itemId, long createdAt) {
}
