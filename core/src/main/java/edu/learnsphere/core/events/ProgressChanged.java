package edu.learnsphere.core.events;

/**
 * The learner's progress changed (an answer was recorded, a session finished, progress was imported).
 * Reload dashboards, due counts and mastery.
 *
 * @param courseId the course concerned, or null for several
 */
public record ProgressChanged(String courseId) implements Event {
}
