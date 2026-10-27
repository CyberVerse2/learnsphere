package edu.learnsphere.core.service;

import edu.learnsphere.core.AppPaths;
import edu.learnsphere.core.profile.ProfileRegistry;
import edu.learnsphere.core.security.PasswordHasher;
import edu.learnsphere.core.service.impl.ProfileServiceImpl;
import edu.learnsphere.core.service.impl.WorkspaceImpl;
import edu.learnsphere.core.spi.Capabilities;
import java.time.Clock;

/**
 * The entry point to every service. The app calls it once at start-up:
 *
 * <pre>{@code
 * ProfileService profiles = ProfileServices.create(paths, new ProfileRegistry(paths.profilesFile()),
 *         new PasswordHasher(), Clock.systemDefaultZone(), capabilities);
 * try (Workspace workspace = profiles.open(profileId, password)) {
 *     workspace.settings().current();
 * }
 * }</pre>
 */
public final class ProfileServices {

    private ProfileServices() {
    }

    /** Profile management whose workspaces run every service on {@code capabilities}. */
    public static ProfileService create(AppPaths paths, ProfileRegistry registry, PasswordHasher hasher, Clock clock,
                                        Capabilities capabilities) {
        return create(paths, registry, hasher, clock, WorkspaceImpl.factory(capabilities));
    }

    /** Profile management whose workspaces come from {@code workspaces} (demo mode and tests). */
    public static ProfileService create(AppPaths paths, ProfileRegistry registry, PasswordHasher hasher, Clock clock,
                                        WorkspaceFactory workspaces) {
        return new ProfileServiceImpl(paths, registry, hasher, clock, workspaces);
    }
}
