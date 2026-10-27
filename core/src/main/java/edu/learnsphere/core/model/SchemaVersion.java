package edu.learnsphere.core.model;

/**
 * A row of {@code schema_version}: one applied migration.
 *
 * @param version   the migration number {@code n} from {@code V{n}__name.sql}
 * @param name      the script's file name
 * @param appliedAt when it was applied (epoch millis)
 */
public record SchemaVersion(int version, String name, long appliedAt) {
}
