package edu.learnsphere.core.sharing;

import edu.learnsphere.core.db.Repositories;
import edu.learnsphere.core.error.ConflictException;
import edu.learnsphere.core.error.InvalidInputException;
import edu.learnsphere.core.error.NotFoundException;
import edu.learnsphere.core.events.ChangeType;
import edu.learnsphere.core.events.EventBus;
import edu.learnsphere.core.events.ItemsChanged;
import edu.learnsphere.core.model.Course;
import edu.learnsphere.core.model.EntityType;
import edu.learnsphere.core.model.Flag;
import edu.learnsphere.core.model.FlagReason;
import edu.learnsphere.core.model.FlagSource;
import edu.learnsphere.core.model.LearningItem;
import edu.learnsphere.core.service.ImportOutcome;
import edu.learnsphere.core.service.ImportResult;
import edu.learnsphere.core.sharing.FeedbackFile.FlagEntry;
import edu.learnsphere.core.util.Json;
import java.nio.file.Path;
import java.time.Clock;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Feedback sharing: a learner exports their open flags on a course ({@code .lsfeedback}); the creator adds
 * them to the matching course as learner feedback. Importing a file twice adds nothing the second time.
 * Thread-safe.
 */
public final class FeedbackSharing {

    private final Repositories repos;
    private final EventBus events;
    private final Clock clock;

    public FeedbackSharing(Repositories repos, EventBus events, Clock clock) {
        this.repos = Objects.requireNonNull(repos, "repos");
        this.events = Objects.requireNonNull(events, "events");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /**
     * Writes the learner's open flags on a course's items to {@code target}.
     *
     * @throws ConflictException when the learner has no open flags on the course
     */
    public void exportFeedback(String courseId, Path target) {
        Objects.requireNonNull(target, "target");
        FeedbackFile file = repos.database().inTransaction(h -> {
            Course course = repos.courses().get(courseId);
            List<FlagEntry> flags = repos.flags().listByCourse(courseId, false).stream()
                    .filter(f -> f.isOpen() && f.source() == FlagSource.SELF)
                    .sorted(Comparator.comparingLong(Flag::createdAt).thenComparing(Flag::id))
                    .map(f -> new FlagEntry(f.id(), f.itemId(), f.reason().code(), truncate(f.note()), f.createdAt()))
                    .toList();
            if (flags.isEmpty()) {
                throw new ConflictException(SharingMessages.NO_FEEDBACK, "No open flags in course " + courseId);
            }
            return new FeedbackFile(FeedbackFile.FORMAT, FeedbackFile.VERSION, course.packageId(), course.title(),
                    course.versionNumber(), clock.millis(), flags);
        });
        SharingFiles.write(target, file);
        repos.audit().append("export_feedback", EntityType.COURSE, courseId,
                Json.write(Map.of("flags", file.flags().size())));
    }

    /**
     * Adds a learner's flags to the matching course created here, skipping flags already added and flags on
     * items the course does not have.
     *
     * @throws InvalidInputException when the file is not a feedback file
     * @throws NotFoundException     when no course in the library matches
     * @throws ConflictException     when the matching course was not created here
     */
    public ImportResult importFeedback(Path path) {
        FeedbackFile file = SharingFiles.read(path, FeedbackFile.FORMAT, FeedbackFile.class,
                SharingMessages.NOT_A_FEEDBACK_FILE);
        if (!file.isValid()) {
            throw new InvalidInputException(SharingMessages.NOT_A_FEEDBACK_FILE, path + " has invalid fields");
        }
        long now = clock.millis();
        record Added(Course course, Set<String> itemIds, int count) {
        }
        Added added = repos.database().inTransaction(h -> {
            Course course = repos.courses().findByPackageId(file.packageId())
                    .orElseThrow(() -> new NotFoundException(SharingMessages.FEEDBACK_COURSE_MISSING,
                            "No course for package " + file.packageId()));
            if (!course.isCreatedHere()) {
                throw new ConflictException(SharingMessages.FEEDBACK_NOT_YOUR_COURSE,
                        "Course " + course.id() + " for feedback was imported");
            }
            Set<String> courseItems = repos.items().listByCourse(course.id()).stream().map(LearningItem::id)
                    .collect(Collectors.toSet());
            Set<String> touched = new LinkedHashSet<>();
            int count = 0;
            for (FlagEntry entry : file.flags()) {
                if (!courseItems.contains(entry.itemId()) || repos.flags().find(entry.flagId()).isPresent()) {
                    continue;
                }
                long createdAt = Math.min(entry.createdAt(), now);
                repos.flags().insert(new Flag(entry.flagId(), entry.itemId(), FlagReason.fromCode(entry.reason()),
                        truncate(entry.note()), FlagSource.LEARNER_FEEDBACK, null, createdAt, now));
                touched.add(entry.itemId());
                count++;
            }
            repos.audit().append("import_feedback", EntityType.COURSE, course.id(),
                    Json.write(Map.of("packageId", file.packageId(), "added", count, "inFile", file.flags().size())));
            return new Added(course, touched, count);
        });
        if (added.count() > 0) {
            events.publish(new ItemsChanged(added.course().id(), null, added.itemIds(), ChangeType.UPDATED));
        }
        return new ImportResult(ImportOutcome.FEEDBACK_ADDED, added.course().id(), added.course().title(),
                file.versionNumber(), added.count(), 0, 0);
    }

    private static String truncate(String note) {
        if (note == null) {
            return "";
        }
        return note.length() <= FeedbackFile.MAX_NOTE_LENGTH ? note : note.substring(0, FeedbackFile.MAX_NOTE_LENGTH);
    }
}
