package edu.learnsphere.core.service;

import java.util.Objects;

/** New values for a course's details form; every field replaces the stored one. */
public record CourseUpdate(String title, String subtitle, String description, String authorName) {

    public CourseUpdate {
        Objects.requireNonNull(title, "title");
        subtitle = Objects.requireNonNullElse(subtitle, "");
        description = Objects.requireNonNullElse(description, "");
        authorName = Objects.requireNonNullElse(authorName, "");
    }
}
