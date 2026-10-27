package edu.learnsphere.app;

import edu.learnsphere.core.AppPaths;
import edu.learnsphere.core.events.EventBus;
import edu.learnsphere.core.profile.ProfileRegistry;
import edu.learnsphere.core.security.PasswordHasher;
import java.time.Clock;
import java.util.Objects;

/**
 * Application-wide objects, wired by hand (no DI framework). Created once at start-up and closed on exit.
 *
 * <p>Holds what exists before a profile is opened: the app folders, the clock, the profile registry, the
 * password hasher and the app-level event bus. Service implementations and the AI runtime are added here
 * as their modules land: in demo mode ({@code -Dlearnsphere.demo=true}) the AI implementations are the
 * deterministic fakes from {@code edu.learnsphere.ai.fake}.
 */
public final class AppContext implements AutoCloseable {

    /** System property that starts the app with demo data and the simulated study assistant. */
    public static final String DEMO_PROPERTY = "learnsphere.demo";

    private final AppPaths paths;
    private final Clock clock;
    private final boolean demo;
    private final ProfileRegistry profiles;
    private final PasswordHasher passwordHasher = new PasswordHasher();
    private final EventBus events = new EventBus();

    public AppContext(AppPaths paths, Clock clock, boolean demo) {
        this.paths = Objects.requireNonNull(paths, "paths");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.demo = demo;
        this.profiles = new ProfileRegistry(paths.profilesFile());
    }

    /** The context for this run: app home from {@link AppPaths#resolve()}, the system clock, demo mode from the system property. */
    public static AppContext create() {
        return new AppContext(AppPaths.resolve(), Clock.systemDefaultZone(), Boolean.getBoolean(DEMO_PROPERTY));
    }

    public AppPaths paths() {
        return paths;
    }

    public Clock clock() {
        return clock;
    }

    /** True when running with demo data and the simulated study assistant. */
    public boolean isDemo() {
        return demo;
    }

    public ProfileRegistry profiles() {
        return profiles;
    }

    public PasswordHasher passwordHasher() {
        return passwordHasher;
    }

    /** App-level events (profile list, settings). Each open workspace has its own bus as well. */
    public EventBus events() {
        return events;
    }

    @Override
    public void close() {
        events.close();
    }
}
