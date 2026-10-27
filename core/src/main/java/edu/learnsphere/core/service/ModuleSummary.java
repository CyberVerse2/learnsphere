package edu.learnsphere.core.service;

import edu.learnsphere.core.model.CourseModule;
import java.util.Objects;

/**
 * A module on the course page's path.
 *
 * @param itemCount      approved items
 * @param draftItemCount items waiting for review (Studio)
 * @param documentCount  PDFs in the module
 * @param dueCount       items due now
 * @param completion     share of approved items studied at least once, 0–1
 */
public record ModuleSummary(CourseModule module, int itemCount, int draftItemCount, int documentCount, int dueCount,
                            double completion) {

    public ModuleSummary {
        Objects.requireNonNull(module, "module");
    }

    public String id() {
        return module.id();
    }

    public String title() {
        return module.title();
    }

    /** True when every approved item has been studied. */
    public boolean isComplete() {
        return itemCount > 0 && completion >= 1;
    }
}
