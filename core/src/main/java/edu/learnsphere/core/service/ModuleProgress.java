package edu.learnsphere.core.service;

import java.util.Objects;

/**
 * Progress through one module.
 *
 * @param startedCount approved items studied at least once
 * @param completion   startedCount / itemCount, 0–1
 * @param mastery      mean mastery of its concepts, or null
 */
public record ModuleProgress(String moduleId, String title, int position, int itemCount, int startedCount, int dueCount,
                             double completion, Double mastery) {

    public ModuleProgress {
        Objects.requireNonNull(moduleId, "moduleId");
        Objects.requireNonNull(title, "title");
    }
}
