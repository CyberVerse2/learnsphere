package edu.learnsphere.core.sharing;

import edu.learnsphere.core.db.Repositories;
import edu.learnsphere.core.db.StudySessionRepository.ItemAttemptStats;
import edu.learnsphere.core.error.ConflictException;
import edu.learnsphere.core.error.InvalidInputException;
import edu.learnsphere.core.error.NotFoundException;
import edu.learnsphere.core.events.EventBus;
import edu.learnsphere.core.events.ProgressChanged;
import edu.learnsphere.core.model.ClassInsight;
import edu.learnsphere.core.model.Course;
import edu.learnsphere.core.model.EntityType;
import edu.learnsphere.core.model.LearningItem;
import edu.learnsphere.core.model.ReviewState;
import edu.learnsphere.core.service.ClassInsightSummary;
import edu.learnsphere.core.sharing.ProgressFile.ItemProgress;
import edu.learnsphere.core.util.Ids;
import edu.learnsphere.core.util.Json;
import java.nio.file.Path;
import java.time.Clock;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Progress sharing: a learner exports per-item attempt summaries ({@code .lsprogress}) under a label they
 * choose; the creator imports several into class insights for their own course. Thread-safe.
 */
public final class ProgressSharing {

    private final Repositories repos;
    private final EventBus events;
    private final Clock clock;

    public ProgressSharing(Repositories repos, EventBus events, Clock clock) {
        this.repos = Objects.requireNonNull(repos, "repos");
        this.events = Objects.requireNonNull(events, "events");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /**
     * Writes this learner's attempt summaries for a course to {@code target}.
     *
     * @throws InvalidInputException for a blank or too long label
     * @throws ConflictException     when no item of the course was answered yet
     */
    public void exportProgress(String courseId, String learnerLabel, Path target) {
        Objects.requireNonNull(target, "target");
        String label = ProgressFile.normalizeLabel(learnerLabel);
        if (label.isEmpty()) {
            throw new InvalidInputException(SharingMessages.LABEL_REQUIRED, "Blank learner label");
        }
        if (label.length() > ProgressFile.MAX_LABEL_LENGTH) {
            throw new InvalidInputException(SharingMessages.LABEL_TOO_LONG, "Learner label of " + label.length() + " chars");
        }
        ProgressFile file = repos.database().inTransaction(h -> {
            Course course = repos.courses().get(courseId);
            Map<String, ItemAttemptStats> stats = repos.sessions().itemStats(courseId);
            Map<String, Integer> lapses = new HashMap<>();
            for (ReviewState state : repos.reviews().listByCourse(courseId)) {
                lapses.put(state.itemId(), state.lapses());
            }
            List<ItemProgress> items = stats.values().stream()
                    .filter(s -> s.attempts() > 0)
                    .sorted(Comparator.comparing(ItemAttemptStats::itemId))
                    .map(s -> new ItemProgress(s.itemId(), s.attempts(), Math.min(s.correct(), s.attempts()),
                            clamp(s.averageScore()), Math.max(0, s.averageDurationMs()), lapses.getOrDefault(s.itemId(), 0)))
                    .toList();
            if (items.isEmpty()) {
                throw new ConflictException(SharingMessages.NO_PROGRESS, "No attempts in course " + courseId);
            }
            return new ProgressFile(ProgressFile.FORMAT, ProgressFile.VERSION, course.packageId(), course.title(),
                    course.versionNumber(), label, clock.millis(), items);
        });
        SharingFiles.write(target, file);
        repos.audit().append("export_progress", EntityType.COURSE, courseId,
                Json.write(Map.of("items", file.items().size())));
    }

    /**
     * Adds learners' progress files to a course created here. A label already present (ignoring case) is
     * replaced; when several files carry one label, the most recently exported wins. Attempts on items the
     * course does not have are left out. All files are checked before anything is stored.
     *
     * @throws ConflictException     when the course was not created here
     * @throws InvalidInputException when no file is given, a file is not a progress file, or belongs to
     *                               another course
     */
    public ClassInsightSummary importClassProgress(String courseId, List<Path> files) {
        Course course = repos.courses().get(courseId);
        if (!course.isCreatedHere()) {
            throw new ConflictException(SharingMessages.INSIGHTS_NEED_OWN_COURSE, "Course " + courseId + " was imported");
        }
        if (files == null || files.isEmpty()) {
            throw new InvalidInputException(SharingMessages.NO_PROGRESS_FILES, "No progress files");
        }
        Set<String> itemIds = repos.items().listByCourse(courseId).stream().map(LearningItem::id)
                .collect(Collectors.toSet());
        Map<String, ProgressFile> byLabel = new LinkedHashMap<>();
        for (Path path : files) {
            ProgressFile read = SharingFiles.read(path, ProgressFile.FORMAT, ProgressFile.class,
                    SharingMessages.NOT_A_PROGRESS_FILE);
            if (!read.isValid()) {
                throw new InvalidInputException(SharingMessages.NOT_A_PROGRESS_FILE, path + " has invalid fields");
            }
            if (!read.packageId().equals(course.packageId())) {
                throw new InvalidInputException(SharingMessages.PROGRESS_OTHER_COURSE,
                        path + " is for package " + read.packageId());
            }
            String label = ProgressFile.normalizeLabel(read.learnerLabel());
            ProgressFile normalized = new ProgressFile(ProgressFile.FORMAT, ProgressFile.VERSION, read.packageId(),
                    read.courseTitle(), read.versionNumber(), label, read.exportedAt(),
                    read.items().stream()
                            .filter(i -> itemIds.contains(i.itemId()))
                            .sorted(Comparator.comparing(ItemProgress::itemId))
                            .toList());
            byLabel.merge(label.toLowerCase(Locale.ROOT), normalized,
                    (a, b) -> b.exportedAt() >= a.exportedAt() ? b : a);
        }
        long now = clock.millis();
        repos.database().useTransaction(h -> {
            Map<String, List<ClassInsight>> existing = repos.classInsights().listByCourse(courseId).stream()
                    .collect(Collectors.groupingBy(i -> i.learnerLabel().toLowerCase(Locale.ROOT)));
            for (Map.Entry<String, ProgressFile> e : byLabel.entrySet()) {
                existing.getOrDefault(e.getKey(), List.of()).forEach(old -> repos.classInsights().delete(old.id()));
                ProgressFile p = e.getValue();
                repos.classInsights().insert(new ClassInsight(Ids.newId(), courseId, p.learnerLabel(), p.toPayload(), now,
                        now));
            }
            repos.audit().append("import_class_progress", EntityType.COURSE, courseId,
                    Json.write(Map.of("learners", byLabel.size())));
        });
        events.publish(new ProgressChanged(courseId));
        return classInsights(courseId);
    }

    /** Class insights for a course: empty when no progress was imported. */
    public ClassInsightSummary classInsights(String courseId) {
        return repos.database().inTransaction(h -> {
            repos.courses().get(courseId);
            return ClassInsightAggregator.aggregate(courseId, repos.classInsights().listByCourse(courseId),
                    repos.items().listByCourse(courseId), repos.concepts().listByCourse(courseId),
                    repos.items().conceptLinks(courseId));
        });
    }

    /** Removes one learner's imported progress. */
    public void removeClassInsight(String insightId) {
        String courseId = repos.database().inTransaction(h -> {
            ClassInsight insight = repos.classInsights().find(insightId)
                    .orElseThrow(() -> new NotFoundException(SharingMessages.INSIGHT_NOT_FOUND,
                            "Class insight not found: " + insightId));
            repos.classInsights().delete(insightId);
            repos.audit().append("remove_class_insight", EntityType.COURSE, insight.courseId(),
                    Json.write(Map.of("insightId", insightId)));
            return insight.courseId();
        });
        events.publish(new ProgressChanged(courseId));
    }

    private static double clamp(double score) {
        return Double.isNaN(score) ? 0 : Math.max(0, Math.min(1, score));
    }

    /** Imported learners per item and their accuracy, for item statistics. */
    public Map<String, ItemClassStats> itemClassStats(String courseId) {
        Map<String, int[]> totals = new HashMap<>();
        for (ClassInsight insight : repos.classInsights().listByCourse(courseId)) {
            ProgressFile progress;
            try {
                progress = ProgressFile.fromPayload(insight.payloadJson());
            } catch (RuntimeException e) {
                continue;
            }
            for (ItemProgress p : progress.items()) {
                if (p != null && p.attempts() > 0) {
                    int[] t = totals.computeIfAbsent(p.itemId(), id -> new int[3]);
                    t[0]++;
                    t[1] += p.attempts();
                    t[2] += Math.min(p.correct(), p.attempts());
                }
            }
        }
        Map<String, ItemClassStats> result = new HashMap<>();
        totals.forEach((itemId, t) -> result.put(itemId, new ItemClassStats(t[0], (double) t[2] / t[1])));
        return result;
    }

    /**
     * Class figures for one item, as {@link edu.learnsphere.core.service.ItemStats} reports them.
     *
     * @param learners learners in imported progress who attempted the item
     * @param accuracy their share of correct attempts
     */
    public record ItemClassStats(int learners, double accuracy) {
    }
}
