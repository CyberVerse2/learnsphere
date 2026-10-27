package edu.learnsphere.core.model;

import edu.learnsphere.core.util.Ids;
import java.util.Objects;

/**
 * A row of {@code courses}.
 *
 * @param id            course id
 * @param title         course name, never blank
 * @param subtitle      one-line subtitle, may be empty
 * @param description   longer description, may be empty
 * @param authorName    creator's display name, may be empty
 * @param origin        created here or imported
 * @param visibility    whether and how it can be shared
 * @param status        draft, published or archived
 * @param coverHue      hue 0–359 for the generated cover art
 * @param versionNumber latest published version number; 0 when never published
 * @param publishedAt   when the latest version was published, or null
 * @param packageId     stable id shared by every copy of this course, used to match updates on import
 * @param lastOpenedAt  when the learner last opened it, or null
 * @param createdAt     creation time (epoch millis)
 * @param updatedAt     last change (epoch millis)
 */
public record Course(
        String id,
        String title,
        String subtitle,
        String description,
        String authorName,
        CourseOrigin origin,
        Visibility visibility,
        CourseStatus status,
        int coverHue,
        int versionNumber,
        Long publishedAt,
        String packageId,
        Long lastOpenedAt,
        long createdAt,
        long updatedAt) {

    public Course {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(title, "title");
        subtitle = Objects.requireNonNullElse(subtitle, "");
        description = Objects.requireNonNullElse(description, "");
        authorName = Objects.requireNonNullElse(authorName, "");
        Objects.requireNonNull(origin, "origin");
        Objects.requireNonNull(visibility, "visibility");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(packageId, "packageId");
        if (coverHue < 0 || coverHue > 359) {
            throw new IllegalArgumentException("coverHue must be 0-359: " + coverHue);
        }
        if (versionNumber < 0) {
            throw new IllegalArgumentException("versionNumber must be >= 0: " + versionNumber);
        }
    }

    /** A new private draft created in this profile, with a fresh id and package id. */
    public static Course newDraft(String title, String authorName, int coverHue, long now) {
        return new Course(Ids.newId(), title, "", "", authorName, CourseOrigin.LOCAL, Visibility.PRIVATE,
                CourseStatus.DRAFT, coverHue, 0, null, Ids.newId(), null, now, now);
    }

    /** True when this profile created the course (and can edit it in the Studio). */
    public boolean isCreatedHere() {
        return origin == CourseOrigin.LOCAL;
    }

    /** True when at least one version has been published. */
    public boolean isPublished() {
        return versionNumber > 0;
    }
}
