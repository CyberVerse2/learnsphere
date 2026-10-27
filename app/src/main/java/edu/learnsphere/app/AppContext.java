package edu.learnsphere.app;

import edu.learnsphere.app.dev.DemoWorkspace;
import edu.learnsphere.app.ui.Fx;
import edu.learnsphere.core.AppPaths;
import edu.learnsphere.core.error.UnavailableException;
import edu.learnsphere.core.error.UserMessage;
import edu.learnsphere.core.events.EventBus;
import edu.learnsphere.core.profile.AppPreferencesStore;
import edu.learnsphere.core.profile.ProfileRegistry;
import edu.learnsphere.core.security.PasswordHasher;
import edu.learnsphere.core.service.ProfileService;
import edu.learnsphere.core.service.ProfileServices;
import edu.learnsphere.core.service.Workspace;
import edu.learnsphere.core.service.WorkspaceFactory;
import java.time.Clock;
import java.util.Objects;
import java.util.Optional;
import javafx.beans.property.ReadOnlyObjectProperty;
import javafx.beans.property.ReadOnlyObjectWrapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Application-wide objects, wired by hand (no DI framework). Created once at start-up and closed on exit.
 *
 * <p>Holds the app folders, the clock, app-wide preferences, the {@link ProfileService} and the open
 * {@link Workspace}. Views read the workspace from {@link #workspaceProperty()} (null while no profile is
 * open) and call its services through {@code UiTasks}.
 *
 * <p>In demo mode ({@code -Dlearnsphere.demo=true}) workspaces are {@link DemoWorkspace}s: real settings,
 * assistant and job queue over the profile's database with the simulated study assistant, and sample
 * courses and progress. Otherwise workspaces come from {@link #workspaceFactory()}.
 */
public final class AppContext implements AutoCloseable {

    /** System property that starts the app with demo data and the simulated study assistant. */
    public static final String DEMO_PROPERTY = "learnsphere.demo";

    private static final Logger LOG = LoggerFactory.getLogger(AppContext.class);

    private final AppPaths paths;
    private final Clock clock;
    private final boolean demo;
    private final AppPreferencesStore preferences;
    private final ProfileService profiles;
    private final EventBus events = new EventBus();
    private final ReadOnlyObjectWrapper<Workspace> workspace = new ReadOnlyObjectWrapper<>(this, "workspace");
    private Workspace openWorkspace;

    public AppContext(AppPaths paths, Clock clock, boolean demo, WorkspaceFactory workspaces) {
        this.paths = Objects.requireNonNull(paths, "paths");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.demo = demo;
        this.preferences = new AppPreferencesStore(paths.preferencesFile());
        this.profiles = ProfileServices.create(paths, new ProfileRegistry(paths.profilesFile()), new PasswordHasher(), clock,
                Objects.requireNonNull(workspaces, "workspaces"));
    }

    /** The context for this run: app home from {@link AppPaths#resolve()}, the system clock, demo mode from the system property. */
    public static AppContext create() {
        boolean demo = Boolean.getBoolean(DEMO_PROPERTY);
        Clock clock = Clock.systemDefaultZone();
        return new AppContext(AppPaths.resolve(), clock, demo, demo ? DemoWorkspace.factory() : workspaceFactory());
    }

    /**
     * Workspaces outside demo mode. The pdf, search and ai modules do not provide their implementations yet,
     * so there is no {@code Capabilities} to build them from and opening a profile reports that it couldn't be
     * opened. Once they exist this returns the factory of
     * {@code ProfileServices.create(paths, registry, hasher, clock, capabilities)}.
     */
    static WorkspaceFactory workspaceFactory() {
        return context -> {
            throw new UnavailableException(UserMessage.PROFILE_NOT_OPENED,
                    "No Capabilities: the pdf, search and ai modules have no implementations yet");
        };
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

    /** Profile management (works before any profile is open). */
    public ProfileService profiles() {
        return profiles;
    }

    /** App-wide preferences: appearance, last profile, onboarding. */
    public AppPreferencesStore preferences() {
        return preferences;
    }

    /** App-level events. Each open workspace has its own bus as well ({@link Workspace#events()}). */
    public EventBus events() {
        return events;
    }

    /** The open profile's workspace, or null while none is open. Changes on the FX thread. */
    public ReadOnlyObjectProperty<Workspace> workspaceProperty() {
        return workspace.getReadOnlyProperty();
    }

    /** The open workspace, or null. FX thread. */
    public Workspace workspace() {
        return workspace.get();
    }

    /**
     * Closes the open profile, opens {@code profileId} and publishes it through {@link #workspaceProperty()}.
     * Blocking: call it through {@code UiTasks}.
     *
     * @param password the password (wiped afterwards), or null for a profile without one
     */
    public synchronized Workspace open(String profileId, char[] password) {
        closeWorkspace();
        Workspace opened = profiles.open(profileId, password);
        openWorkspace = opened;
        preferences.update(p -> p.withLastProfileId(profileId));
        Fx.run(() -> workspace.set(opened));
        return opened;
    }

    /**
     * Opens the profile used last time (or the most recently opened one) when it has no password; empty
     * when there is none, it needs a password or it cannot be opened. Blocking.
     */
    public synchronized Optional<Workspace> openLastProfile() {
        String last = preferences.get().lastProfileId();
        Optional<String> candidate = Optional.ofNullable(last).filter(id -> profiles.find(id).isPresent())
                .or(() -> profiles.list().stream().findFirst().map(p -> p.id()));
        if (candidate.isEmpty() || profiles.find(candidate.get()).map(p -> p.passwordProtected()).orElse(true)) {
            return Optional.empty();
        }
        try {
            return Optional.of(open(candidate.get(), null));
        } catch (RuntimeException e) {
            LOG.warn("Could not open profile {} at start-up", candidate.get(), e);
            return Optional.empty();
        }
    }

    /** Closes the open workspace, if any, and publishes null. Blocking. */
    public synchronized void closeWorkspace() {
        Workspace closing = openWorkspace;
        if (closing == null) {
            return;
        }
        openWorkspace = null;
        Fx.run(() -> {
            if (workspace.get() == closing) {
                workspace.set(null);
            }
        });
        closing.close();
    }

    @Override
    public void close() {
        closeWorkspace();
        events.close();
    }
}
