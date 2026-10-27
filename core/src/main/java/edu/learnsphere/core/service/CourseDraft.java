package edu.learnsphere.core.service;

import java.util.Objects;

/**
 * What the "New course" form collects.
 *
 * @param coverHue cover hue 0–359, or null to pick one
 */
public record CourseDraft(String title, String subtitle, String description, String authorName, Integer coverHue) {

    public CourseDraft {
        Objects.requireNonNull(title, "title");
        subtitle = Objects.requireNonNullElse(subtitle, "");
        description = Objects.requireNonNullElse(description, "");
        authorName = Objects.requireNonNullElse(authorName, "");
    }

    /** A course with just a title. */
    public static CourseDraft titled(String title) {
        return new CourseDraft(title, "", "", "", null);
    }
}
