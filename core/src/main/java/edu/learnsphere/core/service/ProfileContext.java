package edu.learnsphere.core.service;

import edu.learnsphere.core.AppPaths;
import edu.learnsphere.core.db.Database;
import edu.learnsphere.core.db.Repositories;
import edu.learnsphere.core.events.EventBus;
import java.time.Clock;
import java.util.Objects;

/**
 * An opened profile's storage, handed to a {@link WorkspaceFactory}. The profile service opened (and
 * migrated) the database and created the event bus; it closes both after the workspace closes, so a
 * workspace never closes them itself.
 *
 * @param profile      the profile being opened
 * @param paths        its folders (database, index, files, renders)
 * @param repositories every repository over its database
 * @param events       its event bus
 * @param clock        the clock every service uses
 */
public record ProfileContext(ProfileSummary profile, AppPaths.ProfilePaths paths, Repositories repositories,
                             EventBus events, Clock clock) {

    public ProfileContext {
        Objects.requireNonNull(profile, "profile");
        Objects.requireNonNull(paths, "paths");
        Objects.requireNonNull(repositories, "repositories");
        Objects.requireNonNull(events, "events");
        Objects.requireNonNull(clock, "clock");
    }

    /** The profile's database. */
    public Database database() {
        return repositories.database();
    }
}
