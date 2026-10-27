package edu.learnsphere.core.service.impl;

import edu.learnsphere.core.db.ChunkRepository.ChunkWithTitle;
import edu.learnsphere.core.db.Repositories;
import edu.learnsphere.core.db.StudyScope;
import edu.learnsphere.core.error.InvalidInputException;
import edu.learnsphere.core.error.NotFoundException;
import edu.learnsphere.core.error.UserMessage;
import edu.learnsphere.core.events.EventBus;
import edu.learnsphere.core.events.ProgressChanged;
import edu.learnsphere.core.learning.ActivityCalendar;
import edu.learnsphere.core.learning.AnswerGrader;
import edu.learnsphere.core.learning.FsrsScheduler;
import edu.learnsphere.core.learning.GradeOutcome;
import edu.learnsphere.core.learning.Hints;
import edu.learnsphere.core.learning.MasteryCalculator;
import edu.learnsphere.core.learning.PlannedItem;
import edu.learnsphere.core.learning.RatingRules;
import edu.learnsphere.core.learning.StudyMessages;
import edu.learnsphere.core.learning.StudyPlanner;
import edu.learnsphere.core.learning.StudyQueries;
import edu.learnsphere.core.model.Attempt;
import edu.learnsphere.core.model.Chunk;
import edu.learnsphere.core.model.Concept;
import edu.learnsphere.core.model.Course;
import edu.learnsphere.core.model.CourseModule;
import edu.learnsphere.core.model.GradedBy;
import edu.learnsphere.core.model.ItemType;
import edu.learnsphere.core.model.LearningItem;
import edu.learnsphere.core.model.Rating;
import edu.learnsphere.core.model.ReviewState;
import edu.learnsphere.core.model.SessionRecord;
import edu.learnsphere.core.service.ConceptChange;
import edu.learnsphere.core.service.ConceptMastery;
import edu.learnsphere.core.service.Feedback;
import edu.learnsphere.core.service.Hint;
import edu.learnsphere.core.service.PracticeService;
import edu.learnsphere.core.service.Response;
import edu.learnsphere.core.service.SessionItem;
import edu.learnsphere.core.service.SessionRequest;
import edu.learnsphere.core.service.SessionSummary;
import edu.learnsphere.core.service.SettingKeys;
import edu.learnsphere.core.service.SettingsService;
import edu.learnsphere.core.service.SourceRef;
import edu.learnsphere.core.service.StudyNext;
import edu.learnsphere.core.service.StudySession;
import edu.learnsphere.core.spi.Passage;
import edu.learnsphere.core.spi.ShortAnswerGrader;
import edu.learnsphere.core.util.CancellationToken;
import edu.learnsphere.core.util.Ids;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Study sessions over SQLite: plans sessions with the {@link StudyPlanner}, grades answers with the
 * {@link AnswerGrader} (short answers by the study assistant), schedules with FSRS-6 at the learner's desired
 * retention, and records every attempt together with the item's new review state in one transaction.
 *
 * <p>A session is a {@code study_sessions} row plus its attempts. The items of a session, their hints and
 * the mastery of their concepts at the start live in memory while the workspace is open; a session started
 * before the workspace was last closed has ended (answers to it are refused, finishing it still summarizes
 * its attempts).
 *
 * <p>Within a session an item can be answered once, except that an item rated Again comes back: it may be
 * answered again, up to {@value #MAX_TRIES_PER_SESSION} times in all ({@link Feedback#rating()} is
 * {@link Rating#AGAIN} and the study screen queues it again). A summary's accuracy counts each item's first
 * answer in the session.
 *
 * <p>Disputing an AI grade changes the attempt to Good and rebuilds the item's review state by replaying its
 * attempts in order through the scheduler, so the schedule is as if the answer had been graded Good.
 *
 * <p>Thread-safe: sessions are guarded individually and every write is one immediate transaction.
 */
public final class PracticeServiceImpl implements PracticeService {

    /** Attempts one item may get in one session (the first answer plus comebacks after Again). */
    public static final int MAX_TRIES_PER_SESSION = 3;
    /** Open sessions kept in memory; the oldest is dropped beyond this. */
    static final int MAX_OPEN_SESSIONS = 32;

    private final Repositories repos;
    private final SettingsService settings;
    private final EventBus events;
    private final Clock clock;
    private final AnswerGrader grader;
    private final StudyQueries queries;
    private final MasteryCalculator mastery;
    private final StudyPlanner planner;
    private final ActivityCalendar calendar;
    private final Map<String, LiveSession> live = new ConcurrentHashMap<>();

    /**
     * @param shortAnswers the study assistant's grader for short answers
     */
    public PracticeServiceImpl(Repositories repos, SettingsService settings, ShortAnswerGrader shortAnswers, EventBus events,
                               Clock clock) {
        this.repos = Objects.requireNonNull(repos, "repos");
        this.settings = Objects.requireNonNull(settings, "settings");
        this.events = Objects.requireNonNull(events, "events");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.grader = new AnswerGrader(Objects.requireNonNull(shortAnswers, "shortAnswers"));
        this.queries = new StudyQueries(repos.database());
        this.mastery = new MasteryCalculator(queries);
        this.planner = new StudyPlanner(repos, queries, mastery, clock);
        this.calendar = new ActivityCalendar(queries, clock);
    }

    // ---- study next ---------------------------------------------------------------------------

    @Override
    public StudyNext studyNext(String courseId) {
        StudyScope scope = courseId == null ? StudyScope.enrolled() : StudyScope.course(repos.courses().get(courseId).id());
        return planner.studyNext(courseId, scope, settings.get(SettingKeys.NEW_ITEMS_PER_DAY));
    }

    // ---- start --------------------------------------------------------------------------------

    @Override
    public StudySession start(SessionRequest request) {
        Objects.requireNonNull(request, "request");
        Target target = resolve(request);
        long now = clock.millis();
        List<PlannedItem> plan = planner.plan(request.kind(), target.scope(), request.size(),
                settings.get(SettingKeys.NEW_ITEMS_PER_DAY));

        Map<String, Double> masteryBefore = masteryOf(plan, conceptIdsOf(plan), now);
        String sessionId = Ids.newId();
        repos.sessions().insertSession(new SessionRecord(sessionId, target.courseId(), target.moduleId(), request.kind(), now,
                null, now, now));

        Map<String, SourceRef> sources = sources(plan.stream().map(PlannedItem::item).toList());
        Map<String, String> conceptNames = conceptNames(conceptIdsOf(plan));
        Map<String, String> moduleTitles = moduleTitles(plan);
        LiveSession session = new LiveSession(sessionId, now, masteryBefore);
        List<SessionItem> items = new ArrayList<>(plan.size());
        for (PlannedItem p : plan) {
            LearningItem item = p.item();
            List<String> choices = choices(sessionId, item);
            SourceRef source = sources.get(item.id());
            boolean hintAvailable = Hints.available(item, source);
            session.entries.put(item.id(), new Entry(item.id(), choices, hintAvailable));
            List<String> names = p.conceptIds().stream().map(conceptNames::get).filter(Objects::nonNull).toList();
            items.add(new SessionItem(item.id(), item.type(), item.prompt(), choices,
                    item.type() == ItemType.FLASHCARD ? item.answer() : null, names,
                    item.moduleId() == null ? null : moduleTitles.get(item.moduleId()), item.difficulty(), p.isNew(),
                    hintAvailable));
        }
        keep(session);
        return new StudySession(sessionId, request.kind(), target.courseId(), target.courseTitle(), target.moduleId(),
                target.moduleTitle(), items, now);
    }

    /** Which courses a request covers and what to call them. */
    private record Target(StudyScope scope, String courseId, String courseTitle, String moduleId, String moduleTitle) {
    }

    private Target resolve(SessionRequest request) {
        String courseId = request.courseId();
        CourseModule module = null;
        if (request.moduleId() != null) {
            module = repos.modules().get(request.moduleId());
            if (courseId != null && !courseId.equals(module.courseId())) {
                throw NotFoundException.of(UserMessage.MODULE_NOT_FOUND, "module in course", request.moduleId());
            }
            courseId = module.courseId();
        }
        if (courseId == null) {
            return new Target(StudyScope.enrolled(), null, null, null, null);
        }
        Course course = repos.courses().get(courseId);
        if (!course.isCreatedHere() && !repos.courses().isEnrolled(courseId)) {
            throw new InvalidInputException(StudyMessages.NOT_STUDYING, "course " + courseId + " is neither created here nor enrolled");
        }
        StudyScope scope = module == null ? StudyScope.course(courseId) : StudyScope.module(courseId, module.id());
        return new Target(scope, courseId, course.title(), module == null ? null : module.id(),
                module == null ? null : module.title());
    }

    /** Options in display order: multiple choice shuffled (the same way every time for a session), true/false fixed. */
    static List<String> choices(String sessionId, LearningItem item) {
        return switch (item.type()) {
            case MCQ -> {
                List<String> options = new ArrayList<>(item.distractors().size() + 1);
                options.add(item.answer());
                options.addAll(item.distractors());
                Collections.shuffle(options, new Random(((long) sessionId.hashCode() << 32) ^ item.id().hashCode()));
                yield List.copyOf(options);
            }
            case TRUE_FALSE -> List.of("true", "false");
            case SHORT_ANSWER, FLASHCARD, CLOZE -> List.of();
        };
    }

    private void keep(LiveSession session) {
        live.put(session.id, session);
        while (live.size() > MAX_OPEN_SESSIONS) {
            live.values().stream().min(Comparator.comparingLong((LiveSession s) -> s.startedAt).thenComparing(s -> s.id))
                    .ifPresent(oldest -> live.remove(oldest.id));
        }
    }

    // ---- submit -------------------------------------------------------------------------------

    @Override
    public Feedback submit(String sessionId, Response response) {
        Objects.requireNonNull(response, "response");
        LiveSession session = requireLive(sessionId);
        Entry entry = session.entries.get(response.itemId());
        if (entry == null) {
            throw new InvalidInputException(StudyMessages.NOT_IN_SESSION, "item " + response.itemId() + " not in session " + sessionId);
        }
        boolean hinted;
        synchronized (session) {
            if (entry.inFlight || !entry.canAnswer()) {
                throw new InvalidInputException(StudyMessages.ALREADY_ANSWERED,
                        "item " + entry.itemId + " already answered in session " + sessionId);
            }
            entry.inFlight = true;
            hinted = entry.hinted || response.hintUsed();
        }
        try {
            LearningItem item = repos.items().get(entry.itemId);
            Optional<ChunkWithTitle> source = sourceChunk(item);
            Passage context = source.map(PracticeServiceImpl::passage).orElse(null);
            GradeOutcome outcome = grader.grade(item, response, hinted, context, CancellationToken.create());
            FsrsScheduler scheduler = scheduler();
            long now = clock.millis();
            String attemptId = Ids.newId();
            String responseText = item.type() == ItemType.FLASHCARD
                    ? outcome.rating().name().toLowerCase(Locale.ROOT)
                    : response.answer();
            Attempt attempt = new Attempt(attemptId, item.id(), sessionId, responseText, outcome.correct(), outcome.score(),
                    outcome.rating(), response.confidence(), hinted, response.durationMs(), outcome.gradedBy(),
                    outcome.graderFeedback(), false, now, now);
            ReviewState next = repos.database().inTransaction(h -> {
                ReviewState current = repos.reviews().find(item.id()).orElse(ReviewState.newItem(item.id(), now));
                ReviewState updated = scheduler.review(current, outcome.rating(), now);
                repos.sessions().insertAttempt(attempt);
                repos.reviews().upsert(updated);
                return updated;
            });
            synchronized (session) {
                entry.record(attemptId, outcome.rating());
            }
            events.publish(new ProgressChanged(item.courseId()));
            return feedback(attempt, item, outcome.missingPoints(), source.map(PracticeServiceImpl::sourceRef).orElse(null),
                    next, now);
        } finally {
            synchronized (session) {
                entry.inFlight = false;
            }
        }
    }

    private LiveSession requireLive(String sessionId) {
        LiveSession session = sessionId == null ? null : live.get(sessionId);
        if (session != null) {
            return session;
        }
        SessionRecord record = repos.sessions().findSession(sessionId)
                .orElseThrow(() -> NotFoundException.of(StudyMessages.SESSION_NOT_FOUND, "session", sessionId));
        throw new InvalidInputException(StudyMessages.SESSION_ENDED, "session " + record.id() + " is not open");
    }

    private FsrsScheduler scheduler() {
        return FsrsScheduler.withRetention(settings.get(SettingKeys.DESIRED_RETENTION));
    }

    // ---- hint ---------------------------------------------------------------------------------

    @Override
    public Hint hint(String sessionId, String itemId) {
        LiveSession session = requireLive(sessionId);
        Entry entry = session.entries.get(itemId);
        if (entry == null) {
            throw new InvalidInputException(StudyMessages.NOT_IN_SESSION, "item " + itemId + " not in session " + sessionId);
        }
        synchronized (session) {
            if (!entry.canAnswer()) {
                throw new InvalidInputException(StudyMessages.ALREADY_ANSWERED, "item " + itemId + " already answered");
            }
        }
        LearningItem item = repos.items().get(itemId);
        SourceRef source = sourceChunk(item).map(PracticeServiceImpl::sourceRef).orElse(null);
        if (!entry.hintAvailable || !Hints.available(item, source)) {
            throw new InvalidInputException(StudyMessages.NO_HINT, "no hint for item " + itemId);
        }
        Hint hint = Hints.hint(item, entry.choices, source);
        synchronized (session) {
            entry.hinted = true;
        }
        return hint;
    }

    // ---- finish -------------------------------------------------------------------------------

    @Override
    public SessionSummary finish(String sessionId) {
        SessionRecord record = repos.sessions().findSession(sessionId)
                .orElseThrow(() -> NotFoundException.of(StudyMessages.SESSION_NOT_FOUND, "session", sessionId));
        long now = clock.millis();
        boolean endedNow = !record.isFinished() && repos.sessions().endSession(sessionId, now);
        long endedAt = endedNow ? now : repos.sessions().findSession(sessionId).map(SessionRecord::endedAt).orElse(now);
        LiveSession session = live.remove(sessionId);

        List<Attempt> attempts = repos.sessions().listAttemptsForSession(sessionId);
        Map<String, Attempt> firstByItem = new LinkedHashMap<>();
        for (Attempt a : attempts) {
            firstByItem.putIfAbsent(a.itemId(), a);
        }
        int answered = firstByItem.size();
        int correct = (int) firstByItem.values().stream().filter(Attempt::correct).count();
        int itemCount = session == null ? answered : session.entries.size();
        int newLearned = repos.database().withHandle(h -> h.createQuery("""
                        SELECT COUNT(*) FROM (SELECT item_id, MIN(created_at) AS first FROM attempts
                                              WHERE session_id = :s GROUP BY item_id) f
                        WHERE NOT EXISTS (SELECT 1 FROM attempts b WHERE b.item_id = f.item_id AND b.created_at < f.first)
                        """)
                .bind("s", sessionId).mapTo(Integer.class).one());

        List<ConceptChange> improved = session == null || answered == 0
                ? List.of()
                : improvedConcepts(session, firstByItem.keySet(), now);
        Long nextReviewAt = repos.reviews().nextDueAfter(scopeOf(record), now).orElse(null);
        if (endedNow && answered > 0) {
            events.publish(new ProgressChanged(record.courseId()));
        }
        return new SessionSummary(sessionId, record.kind(), record.courseId(), itemCount, answered, correct,
                answered == 0 ? 0 : (double) correct / answered, Math.max(0, endedAt - record.startedAt()), newLearned,
                improved, nextReviewAt, calendar.streakDays());
    }

    private static StudyScope scopeOf(SessionRecord record) {
        if (record.courseId() == null) {
            return StudyScope.enrolled();
        }
        return record.moduleId() == null ? StudyScope.course(record.courseId())
                : StudyScope.module(record.courseId(), record.moduleId());
    }

    /** Concepts of the answered items whose mastery rose during the session, biggest gain first. */
    private List<ConceptChange> improvedConcepts(LiveSession session, Set<String> answeredItems, long now) {
        Set<String> conceptIds = new LinkedHashSet<>(queries.conceptIdsByItem(answeredItems).values().stream()
                .flatMap(Set::stream).toList());
        if (conceptIds.isEmpty()) {
            return List.of();
        }
        List<LearningItem> items = repos.items().listByIds(answeredItems);
        Set<String> courseIds = new HashSet<>();
        items.forEach(i -> courseIds.add(i.courseId()));
        List<ConceptChange> changes = new ArrayList<>();
        for (ConceptMastery after : mastery.conceptMastery(StudyScope.courses(courseIds), now)) {
            if (!conceptIds.contains(after.conceptId()) || after.mastery() == null) {
                continue;
            }
            Double before = session.masteryBefore.get(after.conceptId());
            boolean rose = before == null
                    ? after.mastery() >= ConceptMastery.WEAK_THRESHOLD
                    : after.mastery() - before >= 0.005;
            if (rose) {
                changes.add(new ConceptChange(after.conceptId(), after.name(), before, after.mastery()));
            }
        }
        changes.sort(Comparator.comparingDouble(ConceptChange::delta).reversed().thenComparing(ConceptChange::name));
        return changes;
    }

    // ---- dispute ------------------------------------------------------------------------------

    @Override
    public Feedback dispute(String attemptId) {
        Attempt attempt = repos.sessions().findAttempt(attemptId)
                .orElseThrow(() -> NotFoundException.of(StudyMessages.ANSWER_NOT_FOUND, "attempt", attemptId));
        if (attempt.gradedBy() != GradedBy.AI) {
            throw new InvalidInputException(StudyMessages.NOT_DISPUTABLE, "attempt " + attemptId + " graded by " + attempt.gradedBy());
        }
        LearningItem item = repos.items().get(attempt.itemId());
        Optional<ChunkWithTitle> source = sourceChunk(item);
        long now = clock.millis();
        if (attempt.disputed()) {
            ReviewState state = repos.reviews().find(item.id()).orElse(ReviewState.newItem(item.id(), now));
            return feedback(attempt, item, List.of(), source.map(PracticeServiceImpl::sourceRef).orElse(null), state, now);
        }
        FsrsScheduler scheduler = scheduler();
        ReviewState next = repos.database().inTransaction(h -> {
            h.createUpdate("""
                            UPDATE attempts SET disputed = 1, rating = :rating, correct = 1, updated_at = :now
                            WHERE id = :id AND disputed = 0
                            """)
                    .bind("id", attemptId).bind("rating", RatingRules.DISPUTED.value()).bind("now", now).execute();
            ReviewState rebuilt = replay(item.id(), scheduler, now);
            repos.reviews().upsert(rebuilt);
            return rebuilt;
        });
        LiveSession session = live.get(attempt.sessionId());
        if (session != null) {
            synchronized (session) {
                Entry entry = session.entries.get(item.id());
                if (entry != null && attemptId.equals(entry.lastAttemptId)) {
                    entry.lastRating = RatingRules.DISPUTED;
                }
            }
        }
        events.publish(new ProgressChanged(item.courseId()));
        Attempt disputed = repos.sessions().findAttempt(attemptId).orElse(attempt);
        return feedback(disputed, item, List.of(), source.map(PracticeServiceImpl::sourceRef).orElse(null), next, now);
    }

    /**
     * The item's review state rebuilt from all its attempts, oldest first. When the stored state has seen more
     * reviews than there are attempts (its history is incomplete), the stored state is kept and a Good review
     * is applied to it at {@code now} instead.
     */
    private ReviewState replay(String itemId, FsrsScheduler scheduler, long now) {
        List<Attempt> history = new ArrayList<>(repos.sessions().listAttemptsForItem(itemId, Integer.MAX_VALUE));
        Collections.reverse(history);
        Optional<ReviewState> stored = repos.reviews().find(itemId);
        if (stored.isPresent() && stored.get().reps() > history.size()) {
            return scheduler.review(stored.get(), RatingRules.DISPUTED, now);
        }
        long createdAt = stored.map(ReviewState::createdAt).orElse(now);
        ReviewState state = ReviewState.newItem(itemId, createdAt);
        for (Attempt a : history) {
            state = scheduler.review(state, a.rating(), a.createdAt());
        }
        return new ReviewState(state.itemId(), state.phase(), state.stability(), state.difficulty(), state.dueAt(),
                state.lastReviewAt(), state.reps(), state.lapses(), state.scheduledDays(), state.step(), createdAt, now);
    }

    // ---- feedback -----------------------------------------------------------------------------

    private Feedback feedback(Attempt attempt, LearningItem item, List<String> missingPoints, SourceRef source,
                              ReviewState next, long now) {
        boolean ai = attempt.gradedBy() == GradedBy.AI;
        return new Feedback(attempt.id(), item.id(), attempt.correct(), attempt.score(), attempt.rating(),
                attempt.gradedBy(), item.answer(), item.explanation(), ai ? attempt.feedback() : null, missingPoints, source,
                next.dueAt(), intervalDays(now, next.dueAt()), ai && !attempt.disputed(), attempt.disputed());
    }

    /** Calendar days from today to the due date in the learner's time zone (0 = later today). */
    int intervalDays(long now, long dueAt) {
        LocalDate today = LocalDate.ofInstant(Instant.ofEpochMilli(now), clock.getZone());
        LocalDate due = LocalDate.ofInstant(Instant.ofEpochMilli(dueAt), clock.getZone());
        return (int) Math.max(0, ChronoUnit.DAYS.between(today, due));
    }

    // ---- lookups ------------------------------------------------------------------------------

    private Optional<ChunkWithTitle> sourceChunk(LearningItem item) {
        if (item.sourceChunkId() == null) {
            return Optional.empty();
        }
        return repos.chunks().listWithTitles(List.of(item.sourceChunkId())).stream().findFirst();
    }

    private Map<String, SourceRef> sources(List<LearningItem> items) {
        Set<String> chunkIds = new HashSet<>();
        items.forEach(i -> {
            if (i.sourceChunkId() != null) {
                chunkIds.add(i.sourceChunkId());
            }
        });
        Map<String, SourceRef> byChunk = new HashMap<>();
        repos.chunks().listWithTitles(chunkIds).forEach(c -> byChunk.put(c.chunk().id(), sourceRef(c)));
        Map<String, SourceRef> result = new HashMap<>();
        items.forEach(i -> {
            SourceRef ref = i.sourceChunkId() == null ? null : byChunk.get(i.sourceChunkId());
            if (ref != null) {
                result.put(i.id(), ref);
            }
        });
        return result;
    }

    private static SourceRef sourceRef(ChunkWithTitle row) {
        Chunk c = row.chunk();
        return new SourceRef(c.documentId(), row.documentTitle(), c.pageStart(), c.pageEnd(), c.id());
    }

    private static Passage passage(ChunkWithTitle row) {
        Chunk c = row.chunk();
        return new Passage(1, c.id(), c.documentId(), row.documentTitle(), c.pageStart(), c.pageEnd(), c.sectionTitle(),
                c.text(), 0);
    }

    private static Set<String> conceptIdsOf(List<PlannedItem> plan) {
        Set<String> ids = new LinkedHashSet<>();
        plan.forEach(p -> ids.addAll(p.conceptIds()));
        return ids;
    }

    private Map<String, Double> masteryOf(List<PlannedItem> plan, Set<String> conceptIds, long now) {
        if (conceptIds.isEmpty()) {
            return Map.of();
        }
        Set<String> courseIds = new HashSet<>();
        plan.forEach(p -> courseIds.add(p.item().courseId()));
        Map<String, Double> result = new HashMap<>();
        for (ConceptMastery m : mastery.conceptMastery(StudyScope.courses(courseIds), now)) {
            if (conceptIds.contains(m.conceptId()) && m.mastery() != null) {
                result.put(m.conceptId(), m.mastery());
            }
        }
        return result;
    }

    private Map<String, String> conceptNames(Set<String> conceptIds) {
        Map<String, String> names = new HashMap<>();
        for (Concept c : repos.concepts().listByIds(conceptIds)) {
            names.put(c.id(), c.name());
        }
        return names;
    }

    private Map<String, String> moduleTitles(List<PlannedItem> plan) {
        Map<String, String> titles = new HashMap<>();
        for (PlannedItem p : plan) {
            String moduleId = p.item().moduleId();
            if (moduleId != null && !titles.containsKey(moduleId)) {
                repos.modules().find(moduleId).ifPresent(m -> titles.put(m.id(), m.title()));
            }
        }
        return titles;
    }

    // ---- session state ------------------------------------------------------------------------

    /** An open session's items and what has happened to them. Guarded by the session's monitor. */
    private static final class LiveSession {
        final String id;
        final long startedAt;
        final Map<String, Double> masteryBefore;
        final Map<String, Entry> entries = new LinkedHashMap<>();

        LiveSession(String id, long startedAt, Map<String, Double> masteryBefore) {
            this.id = id;
            this.startedAt = startedAt;
            this.masteryBefore = Map.copyOf(masteryBefore);
        }
    }

    /** One item of an open session. */
    private static final class Entry {
        final String itemId;
        final List<String> choices;
        final boolean hintAvailable;
        boolean hinted;
        boolean inFlight;
        int tries;
        Rating lastRating;
        String lastAttemptId;

        Entry(String itemId, List<String> choices, boolean hintAvailable) {
            this.itemId = itemId;
            this.choices = choices;
            this.hintAvailable = hintAvailable;
        }

        /** True before the first answer, and after an Again while tries are left. */
        boolean canAnswer() {
            return tries == 0 || (lastRating == Rating.AGAIN && tries < MAX_TRIES_PER_SESSION);
        }

        void record(String attemptId, Rating rating) {
            tries++;
            lastRating = rating;
            lastAttemptId = attemptId;
            // A hint helps with one answer only.
            hinted = false;
        }
    }
}
