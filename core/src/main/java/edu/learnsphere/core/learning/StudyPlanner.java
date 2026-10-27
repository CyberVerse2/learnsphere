package edu.learnsphere.core.learning;

import edu.learnsphere.core.db.Repositories;
import edu.learnsphere.core.db.StudyScope;
import edu.learnsphere.core.model.ItemType;
import edu.learnsphere.core.model.LearningItem;
import edu.learnsphere.core.model.ReviewPhase;
import edu.learnsphere.core.model.ReviewState;
import edu.learnsphere.core.model.SessionKind;
import edu.learnsphere.core.service.ConceptMastery;
import edu.learnsphere.core.service.StudyNext;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Chooses and orders the items of a study session. Read-only and thread-safe.
 *
 * <ul>
 *   <li><b>Study next</b>: items due for review (lowest retrievability first), then reviewed items of weak
 *       concepts (mastery below {@link ConceptMastery#WEAK_THRESHOLD}, weakest first, not reviewed in the last
 *       {@link #WEAK_COOLDOWN}), then new items in module order up to what is left of the daily new-item
 *       limit.</li>
 *   <li><b>Module quiz</b> and <b>flashcards</b> (practice the learner picked): due items, then new items in
 *       module order, then the rest by lowest retrievability. The daily new-item limit does not hold back
 *       items the learner chose to practise, but they count towards it once answered.</li>
 *   <li><b>Weak concepts</b>: reviewed items of weak concepts (weakest concept first, then lowest
 *       retrievability), then their new items within the daily limit. Empty when no concept is weak.</li>
 * </ul>
 *
 * Every kind stops at the session size. The chosen reviews are then interleaved across concepts and the new
 * items spread evenly between them ({@link Interleaving}). The daily limit counts items whose first attempt
 * ever was made since the start of the learner's day (the clock's time zone), across every course.
 */
public final class StudyPlanner {

    /** Most due items considered for one session (the lowest retrievability among them comes first). */
    public static final int DUE_CAP = 2_000;
    /** A weak-concept item reviewed more recently than this is not offered again by Study next. */
    public static final Duration WEAK_COOLDOWN = Duration.ofHours(1);
    /** Assumed time per review before the learner has answered anything. */
    static final long DEFAULT_ANSWER_MS = 20_000;
    /** New items take longer than reviews by this factor. */
    static final double NEW_ITEM_TIME_FACTOR = 1.5;
    private static final int TIMING_SAMPLE = 200;

    private final Repositories repos;
    private final StudyQueries queries;
    private final MasteryCalculator mastery;
    private final FsrsScheduler scheduler;
    private final Clock clock;

    public StudyPlanner(Repositories repos, StudyQueries queries, MasteryCalculator mastery, Clock clock) {
        this.repos = Objects.requireNonNull(repos, "repos");
        this.queries = Objects.requireNonNull(queries, "queries");
        this.mastery = Objects.requireNonNull(mastery, "mastery");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.scheduler = FsrsScheduler.defaults();
    }

    /** Today in the learner's time zone. */
    public LocalDate today() {
        return LocalDate.now(clock);
    }

    /** The start of the learner's day, in epoch millis. */
    public long startOfToday() {
        return DayRange.startOf(today(), clock.getZone());
    }

    /** New items the learner can still meet today under a limit of {@code newItemsPerDay}. */
    public int newItemsLeftToday(int newItemsPerDay) {
        int introduced = repos.sessions().countItemsFirstAttemptedSince(startOfToday());
        return Math.max(0, newItemsPerDay - introduced);
    }

    /**
     * The "Study next" card for {@code scope}.
     *
     * @param courseId the course the card is for, or null across courses
     */
    public StudyNext studyNext(String courseId, StudyScope scope, int newItemsPerDay) {
        long now = clock.millis();
        int due = repos.reviews().countDue(scope, now);
        int left = newItemsLeftToday(newItemsPerDay);
        int fresh = left == 0 ? 0 : Math.min(left, repos.items().countUnseen(scope));
        Long next = repos.reviews().nextDueAfter(scope, now).orElse(null);
        return new StudyNext(courseId, due, fresh, estimatedMinutes(due, fresh), next);
    }

    /** Rough minutes to answer {@code reviews} reviews and {@code newItems} new items, from the learner's pace. */
    public int estimatedMinutes(int reviews, int newItems) {
        if (reviews + newItems == 0) {
            return 0;
        }
        double perItem = queries.averageAttemptMs(TIMING_SAMPLE).orElse(DEFAULT_ANSWER_MS);
        perItem = Math.min(Math.max(perItem, 8_000), 90_000);
        double total = reviews * perItem + newItems * perItem * NEW_ITEM_TIME_FACTOR;
        return (int) Math.max(1, Math.ceil(total / 60_000));
    }

    /**
     * The items of a new session in presentation order.
     *
     * @param size           most items
     * @param newItemsPerDay the daily new-item limit
     */
    public List<PlannedItem> plan(SessionKind kind, StudyScope scope, int size, int newItemsPerDay) {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(scope, "scope");
        if (size < 1) {
            return List.of();
        }
        long now = clock.millis();
        return switch (kind) {
            case STUDY_NEXT -> studyNextPlan(scope, size, newItemsPerDay, now);
            case MODULE_QUIZ -> practicePlan(scope, Set.of(), size, now);
            case FLASHCARDS -> practicePlan(scope, Set.of(ItemType.FLASHCARD), size, now);
            case WEAK_CONCEPTS -> weakConceptPlan(scope, size, newItemsPerDay, now);
        };
    }

    // ---- study next ---------------------------------------------------------------------------

    private List<PlannedItem> studyNextPlan(StudyScope scope, int size, int newItemsPerDay, long now) {
        List<ReviewState> dueStates = repos.reviews().listDue(scope, now, DUE_CAP);
        Map<String, ReviewState> states = new HashMap<>();
        dueStates.forEach(s -> states.put(s.itemId(), s));
        List<Candidate> due = candidates(dueStates.stream().map(ReviewState::itemId).toList(), states, now);
        due.sort(BY_RETRIEVABILITY);
        List<Candidate> reviews = new ArrayList<>(due.subList(0, Math.min(size, due.size())));
        reviews.forEach(c -> c.reason = PlannedItem.Reason.DUE);

        if (reviews.size() < size) {
            reviews.addAll(weakCandidates(scope, states.keySet(), size - reviews.size(), now));
        }
        List<LearningItem> fresh = List.of();
        int slots = size - reviews.size();
        if (slots > 0) {
            int left = newItemsLeftToday(newItemsPerDay);
            if (left > 0) {
                fresh = repos.items().listUnseen(scope, Math.min(slots, left));
            }
        }
        return finish(reviews, fresh);
    }

    private List<Candidate> weakCandidates(StudyScope scope, Set<String> exclude, int slots, long now) {
        Map<String, Double> weak = weakConcepts(scope, now);
        if (weak.isEmpty()) {
            return List.of();
        }
        List<String> ids = queries.reviewedItemIdsForConcepts(scope, weak.keySet(), now, now - WEAK_COOLDOWN.toMillis())
                .stream().filter(id -> !exclude.contains(id)).toList();
        List<Candidate> weakItems = candidates(ids, repos.reviews().findAll(ids), now);
        Map<String, Set<String>> tags = queries.conceptIdsByItem(ids);
        weakItems.forEach(c -> c.weakness = weakest(tags.get(c.item.id()), weak));
        weakItems.sort(BY_WEAKNESS);
        List<Candidate> chosen = new ArrayList<>(weakItems.subList(0, Math.min(slots, weakItems.size())));
        chosen.forEach(c -> c.reason = PlannedItem.Reason.WEAK_CONCEPT);
        return chosen;
    }

    // ---- module quiz and flashcards -----------------------------------------------------------

    private List<PlannedItem> practicePlan(StudyScope scope, Set<ItemType> types, int size, long now) {
        List<String> ids = queries.approvedItemIds(scope, types);
        List<Candidate> all = candidates(ids, repos.reviews().findAll(ids), now);
        List<Candidate> due = new ArrayList<>();
        List<Candidate> rest = new ArrayList<>();
        List<LearningItem> fresh = new ArrayList<>();
        for (Candidate c : all) {
            if (c.isNew()) {
                fresh.add(c.item);
            } else if (c.state.dueAt() <= now) {
                c.reason = PlannedItem.Reason.DUE;
                due.add(c);
            } else {
                c.reason = PlannedItem.Reason.PRACTICE;
                rest.add(c);
            }
        }
        due.sort(BY_RETRIEVABILITY);
        rest.sort(BY_RETRIEVABILITY);
        List<Candidate> reviews = new ArrayList<>(due.subList(0, Math.min(size, due.size())));
        List<LearningItem> chosenNew = fresh.subList(0, Math.min(size - reviews.size(), fresh.size()));
        int slots = size - reviews.size() - chosenNew.size();
        reviews.addAll(rest.subList(0, Math.min(slots, rest.size())));
        return finish(reviews, chosenNew);
    }

    // ---- weak concepts ------------------------------------------------------------------------

    private List<PlannedItem> weakConceptPlan(StudyScope scope, int size, int newItemsPerDay, long now) {
        Map<String, Double> weak = weakConcepts(scope, now);
        if (weak.isEmpty()) {
            return List.of();
        }
        List<String> ids = queries.approvedItemIdsForConcepts(scope, weak.keySet());
        List<Candidate> all = candidates(ids, repos.reviews().findAll(ids), now);
        Map<String, Set<String>> tags = queries.conceptIdsByItem(ids);
        List<Candidate> reviewed = new ArrayList<>();
        List<LearningItem> fresh = new ArrayList<>();
        for (Candidate c : all) {
            if (c.isNew()) {
                fresh.add(c.item);
            } else {
                c.weakness = weakest(tags.get(c.item.id()), weak);
                c.reason = PlannedItem.Reason.WEAK_CONCEPT;
                reviewed.add(c);
            }
        }
        reviewed.sort(BY_WEAKNESS);
        List<Candidate> reviews = new ArrayList<>(reviewed.subList(0, Math.min(size, reviewed.size())));
        int slots = Math.min(size - reviews.size(), newItemsLeftToday(newItemsPerDay));
        List<LearningItem> chosenNew = slots <= 0 ? List.of() : fresh.subList(0, Math.min(slots, fresh.size()));
        return finish(reviews, chosenNew);
    }

    /** Weak concepts of {@code scope} with their mastery, weakest first. */
    private Map<String, Double> weakConcepts(StudyScope scope, long now) {
        Map<String, Double> weak = new LinkedHashMap<>();
        for (ConceptMastery concept : mastery.conceptMastery(scope, now)) {
            if (concept.isWeak()) {
                weak.put(concept.conceptId(), concept.mastery());
            }
        }
        return weak;
    }

    private static double weakest(Set<String> conceptIds, Map<String, Double> weak) {
        double lowest = 1;
        if (conceptIds != null) {
            for (String id : conceptIds) {
                Double m = weak.get(id);
                if (m != null && m < lowest) {
                    lowest = m;
                }
            }
        }
        return lowest;
    }

    // ---- assembly -----------------------------------------------------------------------------

    private List<PlannedItem> finish(List<Candidate> reviews, List<LearningItem> fresh) {
        List<String> ids = new ArrayList<>(reviews.size() + fresh.size());
        reviews.forEach(c -> ids.add(c.item.id()));
        fresh.forEach(i -> ids.add(i.id()));
        Map<String, Set<String>> tags = queries.conceptIdsByItem(ids);
        List<PlannedItem> plannedReviews = reviews.stream()
                .map(c -> new PlannedItem(c.item, c.state, false, tags.get(c.item.id()), c.retrievability, c.reason))
                .toList();
        List<PlannedItem> plannedNew = fresh.stream()
                .map(i -> new PlannedItem(i, null, true, tags.get(i.id()), 0, PlannedItem.Reason.NEW))
                .toList();
        return Interleaving.order(plannedReviews, plannedNew);
    }

    /** Items with these ids (order kept, missing ones skipped) with their states and current retrievability. */
    private List<Candidate> candidates(Collection<String> ids, Map<String, ReviewState> states, long now) {
        List<Candidate> result = new ArrayList<>(ids.size());
        Set<String> seen = new HashSet<>();
        for (LearningItem item : repos.items().listByIds(ids)) {
            if (!seen.add(item.id())) {
                continue;
            }
            ReviewState state = states.get(item.id());
            result.add(new Candidate(item, state, scheduler.retrievability(state, now)));
        }
        return result;
    }

    private static final Comparator<Candidate> BY_RETRIEVABILITY = Comparator
            .comparingDouble((Candidate c) -> c.retrievability)
            .thenComparingLong(c -> c.state == null ? Long.MAX_VALUE : c.state.dueAt())
            .thenComparing(c -> c.item.id());

    private static final Comparator<Candidate> BY_WEAKNESS = Comparator
            .comparingDouble((Candidate c) -> c.weakness)
            .thenComparing(BY_RETRIEVABILITY);

    /** Working data for one item while planning. */
    private static final class Candidate {
        final LearningItem item;
        final ReviewState state;
        final double retrievability;
        double weakness = 1;
        PlannedItem.Reason reason = PlannedItem.Reason.PRACTICE;

        Candidate(LearningItem item, ReviewState state, double retrievability) {
            this.item = item;
            this.state = state;
            this.retrievability = retrievability;
        }

        boolean isNew() {
            return state == null || state.phase() == ReviewPhase.NEW;
        }
    }
}
