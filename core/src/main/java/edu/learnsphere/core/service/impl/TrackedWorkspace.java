package edu.learnsphere.core.service.impl;

import edu.learnsphere.core.events.EventBus;
import edu.learnsphere.core.service.AskService;
import edu.learnsphere.core.service.AssistantService;
import edu.learnsphere.core.service.CourseService;
import edu.learnsphere.core.service.DocumentService;
import edu.learnsphere.core.service.GenerationService;
import edu.learnsphere.core.service.ItemService;
import edu.learnsphere.core.service.JobQueue;
import edu.learnsphere.core.service.PracticeService;
import edu.learnsphere.core.service.ProfileSummary;
import edu.learnsphere.core.service.ProgressService;
import edu.learnsphere.core.service.SettingsService;
import edu.learnsphere.core.service.SharingService;
import edu.learnsphere.core.service.Workspace;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The workspace {@link ProfileServiceImpl#open} returns: the factory's workspace, plus releasing the
 * database and event bus (and marking the profile closed) after it closes. Closing is idempotent.
 */
final class TrackedWorkspace implements Workspace {

    private static final Logger LOG = LoggerFactory.getLogger(TrackedWorkspace.class);

    private final Workspace delegate;
    private final Runnable release;
    private final AtomicBoolean closed = new AtomicBoolean();

    TrackedWorkspace(Workspace delegate, Runnable release) {
        this.delegate = delegate;
        this.release = release;
    }

    @Override
    public ProfileSummary profile() {
        return delegate.profile();
    }

    @Override
    public CourseService courses() {
        return delegate.courses();
    }

    @Override
    public DocumentService documents() {
        return delegate.documents();
    }

    @Override
    public AskService ask() {
        return delegate.ask();
    }

    @Override
    public GenerationService generation() {
        return delegate.generation();
    }

    @Override
    public ItemService items() {
        return delegate.items();
    }

    @Override
    public PracticeService practice() {
        return delegate.practice();
    }

    @Override
    public ProgressService progress() {
        return delegate.progress();
    }

    @Override
    public SharingService sharing() {
        return delegate.sharing();
    }

    @Override
    public SettingsService settings() {
        return delegate.settings();
    }

    @Override
    public AssistantService assistant() {
        return delegate.assistant();
    }

    @Override
    public JobQueue jobs() {
        return delegate.jobs();
    }

    @Override
    public EventBus events() {
        return delegate.events();
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        try {
            delegate.close();
        } catch (RuntimeException e) {
            LOG.warn("Closing the workspace of {} failed", delegate.profile().id(), e);
        } finally {
            release.run();
        }
    }
}
