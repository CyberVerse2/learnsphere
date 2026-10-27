package edu.learnsphere.core.service;

import edu.learnsphere.core.events.EventBus;

/**
 * An opened profile. Gives access to every per-profile service. All services are thread-safe and
 * blocking: call them off the FX thread. Close the workspace to release the database, search index and
 * study assistant processes (the services must not be used afterwards).
 */
public interface Workspace extends AutoCloseable {

    /** The profile that is open. */
    ProfileSummary profile();

    CourseService courses();

    DocumentService documents();

    AskService ask();

    GenerationService generation();

    ItemService items();

    PracticeService practice();

    ProgressService progress();

    SharingService sharing();

    SettingsService settings();

    AssistantService assistant();

    JobQueue jobs();

    /** Change notifications for this profile. */
    EventBus events();

    /** Stops jobs, the study assistant, and closes the index and database. Idempotent. */
    @Override
    void close();
}
