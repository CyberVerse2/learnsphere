package edu.learnsphere.app;

import edu.learnsphere.ai.LocalAi;
import edu.learnsphere.app.dev.DemoWorkspace;
import edu.learnsphere.app.ui.Fx;
import edu.learnsphere.core.AppPaths;
import edu.learnsphere.core.error.InvalidInputException;
import edu.learnsphere.core.error.UserMessage;
import edu.learnsphere.core.events.EventBus;
import edu.learnsphere.core.profile.AppPreferencesStore;
import edu.learnsphere.core.profile.ProfileRegistry;
import edu.learnsphere.core.security.PasswordHasher;
import edu.learnsphere.core.service.ProfileService;
import edu.learnsphere.core.service.ProfileServices;
import edu.learnsphere.core.service.ProfileSummary;
import edu.learnsphere.core.service.Workspace;
import edu.learnsphere.core.service.WorkspaceFactory;
import edu.learnsphere.core.service.impl.WorkspaceImpl;
import edu.learnsphere.core.spi.AiCapabilities;
import edu.learnsphere.core.spi.Capabilities;
import edu.learnsphere.pdf.PdfModule;
import edu.learnsphere.search.LuceneSearchIndexFactory;
import java.time.Clock;
import java.util.Arrays;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import javafx.beans.property.ReadOnlyObjectProperty;
import javafx.beans.property.ReadOnlyObjectWrapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Application-wide objects, wired by hand (no DI framework). Created once at start-up and closed on exit.
 *
 * <p>Holds the app folders, the clock, app-wide preferences, the {@link ProfileService} and the open
 * {@link Workspace}. Views read the workspace from {@link #workspaceProperty()} (null while no profile is
 * open) and call its services through {@code UiTasks}; {@link #profileProperty()} is the open profile as it
 * is now (its name and colour follow edits made after it was opened).
 *
 * <p>Opening another profile ({@link #open}) checks its password first, so a mistyped password never
 * closes the profile in use; then the open workspace closes (its jobs stop, then the study assistant, then
 * its search index and database) and the new one opens. {@link #close()} closes the open workspace and then
 * stops the study assistant's processes for good.
 *
 * <p>In demo mode ({@code -Dlearnsphere.demo=true}) workspaces are {@link DemoWorkspace}s: real settings,
 * assistant and job queue over the profile's database with the simulated study assistant, and sample
 * courses and progress. Otherwise workspaces are real ({@link WorkspaceImpl}) over {@link #capabilities}, with the
 * study assistant from {@link LocalAi}.
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
    private final ReadOnlyObjectWrapper<ProfileSummary> profile = new ReadOnlyObjectWrapper<>(this, "profile");
    private final AutoCloseable resources;
    private final AtomicBoolean closed = new AtomicBoolean();
    private Workspace openWorkspace;

    public AppContext(AppPaths paths, Clock clock, boolean demo, WorkspaceFactory workspaces) {
        this(paths, clock, demo, workspaces, () -> { });
    }

    /**
     * A context whose workspaces come from {@code workspaces}; {@code resources} (the study assistant's
     * processes) close after the open workspace when the context closes.
     */
    public AppContext(AppPaths paths, Clock clock, boolean demo, WorkspaceFactory workspaces, AutoCloseable resources) {
        this.resources = Objects.requireNonNull(resources, "resources");
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
        AppPaths paths = AppPaths.resolve();
        if (demo) {
            return new AppContext(paths, clock, true, DemoWorkspace.factory());
        }
        return real(paths, clock);
    }

    /**
     * A context over the real services: the PDF tools, the Lucene index and the study assistant's processes
     * from the runtime folder {@code LocalAi} finds (system property {@code learnsphere.runtime} first).
     */
    public static AppContext real(AppPaths paths, Clock clock) {
        LocalAi ai = LocalAi.create(paths);
        return new AppContext(paths, clock, false, WorkspaceImpl.factory(capabilities(paths, ai.capabilities())), ai);
    }

    /**
     * Everything a real workspace is built from: the PDF tools, the Lucene search index and the given AI.
     * PDFBox keeps its font cache under the app home.
     */
    public static Capabilities capabilities(AppPaths paths, AiCapabilities ai) {
        PdfModule.useFontCacheDirectory(paths.home().resolve("cache").resolve("pdf-fonts"));
        return PdfModule.install(Capabilities.builder())
                .searchIndexFactory(new LuceneSearchIndexFactory())
                .ai(ai)
                .build();
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
     * The open profile as it is now (renaming it or changing its colour or password updates this), or null
     * while none is open. Changes on the FX thread, together with {@link #workspaceProperty()}.
     */
    public ReadOnlyObjectProperty<ProfileSummary> profileProperty() {
        return profile.getReadOnlyProperty();
    }

    /** The open profile, or null. FX thread. */
    public ProfileSummary profile() {
        return profile.get();
    }

    /** The id of the open profile, or null; any thread. */
    public synchronized String openProfileId() {
        return openWorkspace == null ? null : openWorkspace.profile().id();
    }

    /**
     * The profile offered at start-up: the one used last time, else the most recently opened one; empty when
     * there are no profiles. Reads the registry in memory.
     */
    public Optional<ProfileSummary> lastProfile() {
        String last = preferences.get().lastProfileId();
        return Optional.ofNullable(last).flatMap(profiles::find)
                .or(() -> profiles.list().stream().findFirst());
    }

    /**
     * Opens {@code profileId} in place of the open profile and publishes it through {@link #workspaceProperty()}.
     * A wrong password leaves the open profile as it was. Opening the profile that is already open returns its
     * workspace. Blocking: call it through {@code UiTasks}.
     *
     * @param password the password (wiped afterwards), or null for a profile without one
     * @throws InvalidInputException with {@link UserMessage#WRONG_PASSWORD} when the password is wrong
     */
    public synchronized Workspace open(String profileId, char[] password) {
        Objects.requireNonNull(profileId, "profileId");
        try {
            if (openWorkspace != null && openWorkspace.profile().id().equals(profileId)) {
                return openWorkspace;
            }
            ProfileSummary target = profiles.find(profileId).orElse(null);
            // With another profile open, check the password before closing it, so a typo never closes it.
            if (openWorkspace != null && target != null && target.passwordProtected()
                    && (password == null || !profiles.checkPassword(profileId, password.clone()))) {
                throw new InvalidInputException(UserMessage.WRONG_PASSWORD, "Wrong password for profile " + profileId);
            }
            closeWorkspace();
            long started = System.nanoTime();
            Workspace opened = profiles.open(profileId, password == null ? null : password.clone());
            openWorkspace = opened;
            LOG.info("Opened profile {} in {} ms", profileId, (System.nanoTime() - started) / 1_000_000);
            try {
                preferences.update(p -> p.withLastProfileId(profileId));
            } catch (RuntimeException e) {
                LOG.warn("Could not remember the last profile", e);
            }
            ProfileSummary summary = opened.profile();
            Fx.run(() -> {
                profile.set(summary);
                workspace.set(opened);
            });
            return opened;
        } finally {
            if (password != null) {
                Arrays.fill(password, '\0');
            }
        }
    }

    /**
     * Opens the profile used last time (or the most recently opened one) when it has no password; empty
     * when there is none, it needs a password or it cannot be opened. Blocking.
     */
    public synchronized Optional<Workspace> openLastProfile() {
        Optional<ProfileSummary> candidate = lastProfile();
        if (candidate.isEmpty() || candidate.get().passwordProtected()) {
            return Optional.empty();
        }
        try {
            return Optional.of(open(candidate.get().id(), null));
        } catch (RuntimeException e) {
            LOG.warn("Could not open profile {} at start-up", candidate.get().id(), e);
            return Optional.empty();
        }
    }

    /**
     * Reads the open profile again after it was renamed, recoloured or its password changed, and publishes it
     * through {@link #profileProperty()}. Blocking; does nothing while no profile is open.
     */
    public void refreshProfile() {
        String id = openProfileId();
        if (id == null) {
            return;
        }
        profiles.find(id).ifPresent(updated -> Fx.run(() -> {
            if (profile.get() != null && profile.get().id().equals(updated.id())) {
                profile.set(updated);
            }
        }));
    }

    /**
     * Closes the open workspace, if any, and publishes null: its jobs stop, then the study assistant, then its
     * search index and database. Blocking.
     */
    public synchronized void closeWorkspace() {
        Workspace closing = openWorkspace;
        if (closing == null) {
            return;
        }
        openWorkspace = null;
        Fx.run(() -> {
            if (workspace.get() == closing) {
                workspace.set(null);
                profile.set(null);
            }
        });
        long started = System.nanoTime();
        closing.close();
        LOG.info("Closed profile {} in {} ms", closing.profile().id(), (System.nanoTime() - started) / 1_000_000);
    }

    /** True once {@link #close()} has run. */
    public boolean isClosed() {
        return closed.get();
    }

    /**
     * Closes the open workspace, then stops the study assistant's processes and the app's event delivery.
     * Idempotent; blocking.
     */
    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        closeWorkspace();
        long started = System.nanoTime();
        try {
            resources.close();
        } catch (Exception e) {
            LOG.warn("Stopping the study assistant failed", e);
        }
        LOG.info("Stopped the study assistant in {} ms", (System.nanoTime() - started) / 1_000_000);
        events.close();
    }
}
