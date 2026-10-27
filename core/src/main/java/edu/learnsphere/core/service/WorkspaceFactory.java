package edu.learnsphere.core.service;

/**
 * Builds the services of a profile the {@link ProfileService} has just opened. The real factory builds
 * them from the app's {@link edu.learnsphere.core.spi.Capabilities} (see
 * {@link ProfileServices#create(edu.learnsphere.core.AppPaths, edu.learnsphere.core.profile.ProfileRegistry,
 * edu.learnsphere.core.security.PasswordHasher, java.time.Clock, edu.learnsphere.core.spi.Capabilities)}); the
 * app's demo mode and tests pass their own.
 */
@FunctionalInterface
public interface WorkspaceFactory {

    /**
     * Builds the workspace. Throwing aborts the open: the profile service closes the database and event bus
     * and the profile can be opened again.
     */
    Workspace open(ProfileContext context);
}
