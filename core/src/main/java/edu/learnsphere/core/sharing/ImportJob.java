package edu.learnsphere.core.sharing;

import java.util.Objects;

/**
 * The payload of an {@code IMPORT_PACKAGE} job. It never holds the code or the key of a protected package,
 * only a reference to the key held in memory by {@link PendingKeys}.
 *
 * @param file       absolute path of the package
 * @param keyRef     reference to the key of a protected package, or null
 * @param allowOlder true when the learner confirmed going back to an older version
 */
public record ImportJob(String file, String keyRef, boolean allowOlder) {

    public ImportJob {
        Objects.requireNonNull(file, "file");
    }
}
