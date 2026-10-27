package edu.learnsphere.core.service.impl;

import edu.learnsphere.core.db.ChunkRepository.ChunkWithTitle;
import edu.learnsphere.core.db.ItemQuery;
import edu.learnsphere.core.db.Repositories;
import edu.learnsphere.core.db.StudySessionRepository.ItemAttemptStats;
import edu.learnsphere.core.error.ConflictException;
import edu.learnsphere.core.error.InvalidInputException;
import edu.learnsphere.core.error.NotFoundException;
import edu.learnsphere.core.error.UserMessage;
import edu.learnsphere.core.events.ChangeType;
import edu.learnsphere.core.events.EventBus;
import edu.learnsphere.core.events.ItemsChanged;
import edu.learnsphere.core.model.Concept;
import edu.learnsphere.core.model.CourseModule;
import edu.learnsphere.core.model.Flag;
import edu.learnsphere.core.model.FlagReason;
import edu.learnsphere.core.model.FlagSource;
import edu.learnsphere.core.model.ItemOrigin;
import edu.learnsphere.core.model.ItemStatus;
import edu.learnsphere.core.model.LearningItem;
import edu.learnsphere.core.model.ReviewState;
import edu.learnsphere.core.model.SupportStatus;
import edu.learnsphere.core.service.FlagSummary;
import edu.learnsphere.core.service.ItemContent;
import edu.learnsphere.core.service.ItemCounts;
import edu.learnsphere.core.service.ItemDetail;
import edu.learnsphere.core.service.ItemFilter;
import edu.learnsphere.core.service.ItemService;
import edu.learnsphere.core.service.ItemStats;
import edu.learnsphere.core.service.ItemSummary;
import edu.learnsphere.core.service.JobQueue;
import edu.learnsphere.core.service.SourcePassage;
import edu.learnsphere.core.service.SourceRef;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * {@link ItemService} over the profile database.
 *
 * <p>Status changes: drafts are approved or rejected; approved items are retired; rejected and retired items
 * go back to the review queue ({@link #returnToDraft}) before they can be approved again. Content is checked
 * against its {@link edu.learnsphere.core.model.ItemType} conventions when written and again before
 * approving, so learners never study a broken item. Editing the prompt, answer, type or options of an item
 * clears its support check, which was made for the old text.
 *
 * <p>Items of courses imported from a package can be flagged (that is how learners report problems) but
 * not changed. Thread-safe.
 */
public final class ItemServiceImpl implements ItemService {

    private static final Logger LOG = LoggerFactory.getLogger(ItemServiceImpl.class);

    static final int MAX_FLAG_NOTE = 1_000;

    static final UserMessage CONCEPT_NOT_IN_COURSE = UserMessage.of("Choose concepts from this course.");
    static final UserMessage ONLY_DRAFTS_APPROVED = UserMessage.of("Only questions waiting for review can be approved.");
    static final UserMessage ONLY_DRAFTS_REJECTED = UserMessage.of("Only questions waiting for review can be rejected.");
    static final UserMessage ONLY_APPROVED_RETIRED = UserMessage.of("Only approved questions can be retired.");
    static final UserMessage NOTE_TOO_LONG = UserMessage.of("This note is too long. Shorten it and try again.");

    /**
     * Statistics of one item, as {@code ProgressService.itemStatsFor} computes them (including imported class
     * insights). Without one, statistics come from this profile's own attempts.
     */
    @FunctionalInterface
    public interface ItemStatsSource {

        ItemStats statsFor(String itemId);
    }

    private final Repositories repos;
    private final EventBus events;
    private final Clock clock;
    private final JobQueue jobs;
    private final ItemStatsSource stats;

    /** An item service whose statistics come from this profile's attempts. */
    public ItemServiceImpl(Repositories repos, EventBus events, Clock clock, JobQueue jobs) {
        this(repos, events, clock, jobs, null);
    }

    /**
     * @param jobs  the job queue (deleting an item cancels work on it)
     * @param stats item statistics for {@link ItemDetail#stats()}, or null for this profile's own attempts
     */
    public ItemServiceImpl(Repositories repos, EventBus events, Clock clock, JobQueue jobs, ItemStatsSource stats) {
        this.repos = Objects.requireNonNull(repos, "repos");
        this.events = Objects.requireNonNull(events, "events");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.jobs = Objects.requireNonNull(jobs, "jobs");
        this.stats = stats;
    }

    // ---- reading -----------------------------------------------------------------------------

    @Override
    public List<ItemSummary> list(ItemFilter filter) {
        Objects.requireNonNull(filter, "filter");
        repos.courses().get(filter.courseId());
        ItemQuery query = new ItemQuery(filter.courseId(), filter.moduleId(), filter.statuses(), filter.types(),
                filter.support(), filter.origin(), filter.conceptId(), filter.search(), filter.flaggedOnly(),
                Math.max(0, filter.limit()), Math.max(0, filter.offset()));
        List<LearningItem> items = repos.items().list(query);
        if (items.isEmpty()) {
            return List.of();
        }
        String courseId = filter.courseId();
        Map<String, String> moduleTitles = moduleTitles(courseId);
        Map<String, Concept> concepts = conceptsById(courseId);
        Map<String, List<String>> links = repos.items().conceptLinks(courseId);
        Map<String, ChunkWithTitle> sources = sources(items);
        Map<String, Integer> openFlags = repos.flags().countOpenByItem(courseId);
        return items.stream().map(item -> {
            List<String> names = links.getOrDefault(item.id(), List.of()).stream()
                    .map(concepts::get).filter(Objects::nonNull).map(Concept::name).toList();
            ChunkWithTitle source = item.sourceChunkId() == null ? null : sources.get(item.sourceChunkId());
            return new ItemSummary(item, names, item.moduleId() == null ? null : moduleTitles.get(item.moduleId()),
                    source == null ? null : source.documentTitle(), openFlags.getOrDefault(item.id(), 0));
        }).toList();
    }

    @Override
    public List<ItemDetail> listDetails(ItemFilter filter) {
        Objects.requireNonNull(filter, "filter");
        repos.courses().get(filter.courseId());
        ItemQuery query = new ItemQuery(filter.courseId(), filter.moduleId(), filter.statuses(), filter.types(),
                filter.support(), filter.origin(), filter.conceptId(), filter.search(), filter.flaggedOnly(),
                Math.max(0, filter.limit()), Math.max(0, filter.offset()));
        return details(filter.courseId(), repos.items().list(query));
    }

    @Override
    public List<ItemDetail> reviewQueue(String courseId, String moduleId, int limit, int offset) {
        if (limit < 1 || offset < 0) {
            throw new IllegalArgumentException("limit must be >= 1 and offset >= 0: " + limit + ", " + offset);
        }
        repos.courses().get(courseId);
        ServiceSupport.requireModuleOf(repos, courseId, moduleId);
        ItemQuery query = ItemQuery.forCourse(courseId).withModule(moduleId).withStatuses(ItemStatus.DRAFT)
                .withPage(limit, offset);
        return details(courseId, repos.items().list(query));
    }

    @Override
    public ItemCounts counts(String courseId) {
        repos.courses().get(courseId);
        Map<ItemStatus, Integer> byStatus = repos.items().countByStatus(courseId);
        return new ItemCounts(byStatus.get(ItemStatus.DRAFT), byStatus.get(ItemStatus.APPROVED),
                byStatus.get(ItemStatus.REJECTED), byStatus.get(ItemStatus.RETIRED),
                repos.items().countUnsupportedDrafts(courseId), repos.items().countFlagged(courseId));
    }

    @Override
    public ItemDetail get(String itemId) {
        LearningItem item = repos.items().get(itemId);
        return details(item.courseId(), List.of(item)).getFirst();
    }

    // ---- writing -----------------------------------------------------------------------------

    @Override
    public LearningItem create(String courseId, String moduleId, ItemContent content) {
        Objects.requireNonNull(content, "content");
        ServiceSupport.requireEditableCourse(repos, courseId);
        ServiceSupport.requireModuleOf(repos, courseId, moduleId);
        ItemContent valid = ItemValidator.validate(content);
        requireConcepts(courseId, valid.conceptIds());
        LearningItem item = LearningItem.draft(courseId, moduleId, valid.type(), valid.prompt(), valid.answer(),
                valid.explanation(), valid.distractors(), valid.difficulty(), null, null, ItemOrigin.MANUAL, clock.millis());
        repos.database().useTransaction(h -> {
            repos.items().insert(item);
            repos.items().setConcepts(item.id(), valid.conceptIds());
        });
        events.publish(new ItemsChanged(courseId, moduleId, Set.of(item.id()), ChangeType.CREATED));
        return item;
    }

    @Override
    public LearningItem edit(String itemId, ItemContent content) {
        Objects.requireNonNull(content, "content");
        LearningItem item = editable(itemId);
        ItemContent valid = ItemValidator.validate(content);
        requireConcepts(item.courseId(), valid.conceptIds());
        boolean textChanged = item.type() != valid.type() || !item.prompt().equals(valid.prompt())
                || !item.answer().equals(valid.answer()) || !item.distractors().equals(valid.distractors());
        LearningItem updated = new LearningItem(item.id(), item.courseId(), item.moduleId(), valid.type(), valid.prompt(),
                valid.answer(), valid.explanation(), valid.distractors(), valid.difficulty(), item.sourceChunkId(),
                item.sourcePage(), item.origin(), item.status(),
                textChanged ? SupportStatus.UNCHECKED : item.support(), textChanged ? null : item.supportNote(),
                item.createdAt(), clock.millis());
        repos.database().useTransaction(h -> {
            repos.items().update(updated);
            repos.items().setConcepts(itemId, valid.conceptIds());
        });
        changed(item);
        return repos.items().get(itemId);
    }

    @Override
    public void moveToModule(String itemId, String moduleId) {
        LearningItem item = editable(itemId);
        ServiceSupport.requireModuleOf(repos, item.courseId(), moduleId);
        if (Objects.equals(item.moduleId(), moduleId)) {
            return;
        }
        LearningItem moved = new LearningItem(item.id(), item.courseId(), moduleId, item.type(), item.prompt(), item.answer(),
                item.explanation(), item.distractors(), item.difficulty(), item.sourceChunkId(), item.sourcePage(),
                item.origin(), item.status(), item.support(), item.supportNote(), item.createdAt(), clock.millis());
        repos.items().update(moved);
        events.publish(new ItemsChanged(item.courseId(), null, Set.of(itemId), ChangeType.UPDATED));
    }

    /**
     * Approves a draft. Approving an approved item does nothing.
     *
     * @throws ConflictException for a rejected or retired item, which goes back to the review queue first
     */
    @Override
    public void approve(String itemId) {
        LearningItem item = editable(itemId);
        switch (item.status()) {
            case APPROVED -> {
                return;
            }
            case DRAFT -> {
                ItemValidator.validate(item);
                repos.items().setStatus(itemId, ItemStatus.APPROVED);
                changed(item);
            }
            default -> throw new ConflictException(ONLY_DRAFTS_APPROVED, "Item " + itemId + " is " + item.status());
        }
    }

    /**
     * Approves the listed drafts whose content is valid; drafts that need fixing stay in the review queue, and
     * items that are not drafts are left as they are.
     */
    @Override
    public int approveAll(Collection<String> itemIds) {
        Objects.requireNonNull(itemIds, "itemIds");
        List<LearningItem> items = repos.items().listByIds(new LinkedHashSet<>(itemIds));
        Map<String, List<String>> approvable = new LinkedHashMap<>();
        Set<String> checkedCourses = new HashSet<>();
        for (LearningItem item : items) {
            if (checkedCourses.add(item.courseId())) {
                ServiceSupport.requireEditableCourse(repos, item.courseId());
            }
            if (item.status() != ItemStatus.DRAFT) {
                continue;
            }
            if (!ItemValidator.isValid(item)) {
                LOG.info("Item {} needs fixing before it can be approved", item.id());
                continue;
            }
            approvable.computeIfAbsent(item.courseId(), k -> new ArrayList<>()).add(item.id());
        }
        if (approvable.isEmpty()) {
            return 0;
        }
        int changed = repos.database().inTransaction(h -> approvable.values().stream()
                .mapToInt(ids -> repos.items().setStatus(ids, ItemStatus.APPROVED)).sum());
        approvable.forEach((courseId, ids) ->
                events.publish(new ItemsChanged(courseId, null, Set.copyOf(ids), ChangeType.UPDATED)));
        return changed;
    }

    @Override
    public void reject(String itemId) {
        LearningItem item = editable(itemId);
        switch (item.status()) {
            case REJECTED -> {
                return;
            }
            case DRAFT -> {
                repos.items().setStatus(itemId, ItemStatus.REJECTED);
                changed(item);
            }
            default -> throw new ConflictException(ONLY_DRAFTS_REJECTED, "Item " + itemId + " is " + item.status());
        }
    }

    @Override
    public void retire(String itemId) {
        LearningItem item = editable(itemId);
        switch (item.status()) {
            case RETIRED -> {
                return;
            }
            case APPROVED -> {
                repos.items().setStatus(itemId, ItemStatus.RETIRED);
                changed(item);
            }
            default -> throw new ConflictException(ONLY_APPROVED_RETIRED, "Item " + itemId + " is " + item.status());
        }
    }

    @Override
    public void returnToDraft(String itemId) {
        LearningItem item = editable(itemId);
        if (item.status() != ItemStatus.DRAFT) {
            repos.items().setStatus(itemId, ItemStatus.DRAFT);
            changed(item);
        }
    }

    @Override
    public void delete(String itemId) {
        LearningItem item = editable(itemId);
        ServiceSupport.cancelJobsFor(jobs, repos, List.of(itemId));
        repos.items().delete(itemId);
        events.publish(new ItemsChanged(item.courseId(), item.moduleId(), Set.of(itemId), ChangeType.DELETED));
    }

    // ---- flags -------------------------------------------------------------------------------

    @Override
    public Flag addFlag(String itemId, FlagReason reason, String note) {
        Objects.requireNonNull(reason, "reason");
        LearningItem item = repos.items().get(itemId);
        String clean = ServiceSupport.clean(note);
        if (clean.length() > MAX_FLAG_NOTE) {
            throw new InvalidInputException(NOTE_TOO_LONG, "Flag note longer than " + MAX_FLAG_NOTE + " characters");
        }
        Flag flag = Flag.open(itemId, reason, clean, FlagSource.SELF, clock.millis());
        repos.flags().insert(flag);
        changed(item);
        return flag;
    }

    @Override
    public List<FlagSummary> flags(String courseId, boolean includeResolved) {
        repos.courses().get(courseId);
        List<Flag> flags = repos.flags().listByCourse(courseId, includeResolved);
        if (flags.isEmpty()) {
            return List.of();
        }
        Map<String, LearningItem> items = repos.items().listByIds(flags.stream().map(Flag::itemId).distinct().toList())
                .stream().collect(Collectors.toMap(LearningItem::id, Function.identity()));
        Map<String, String> moduleTitles = moduleTitles(courseId);
        List<FlagSummary> summaries = new ArrayList<>();
        for (Flag flag : flags) {
            LearningItem item = items.get(flag.itemId());
            if (item != null) {
                summaries.add(new FlagSummary(flag, item.id(), item.type(), item.prompt(),
                        item.moduleId() == null ? null : moduleTitles.get(item.moduleId())));
            }
        }
        return summaries;
    }

    @Override
    public void resolveFlag(String flagId) {
        Flag flag = repos.flags().find(flagId)
                .orElseThrow(() -> NotFoundException.of(UserMessage.NOT_AVAILABLE, "flag", flagId));
        if (flag.isOpen() && repos.flags().resolve(flagId, clock.millis())) {
            repos.items().find(flag.itemId()).ifPresent(this::changed);
        }
    }

    // ---- helpers -----------------------------------------------------------------------------

    /** The item, when its course was created in this profile. */
    private LearningItem editable(String itemId) {
        LearningItem item = repos.items().get(itemId);
        ServiceSupport.requireEditableCourse(repos, item.courseId());
        return item;
    }

    private void requireConcepts(String courseId, Set<String> conceptIds) {
        if (conceptIds.isEmpty()) {
            return;
        }
        List<Concept> found = repos.concepts().listByIds(conceptIds);
        if (found.size() != conceptIds.size() || found.stream().anyMatch(c -> !c.courseId().equals(courseId))) {
            throw new InvalidInputException(CONCEPT_NOT_IN_COURSE, "Concepts " + conceptIds + " not all in " + courseId);
        }
    }

    private void changed(LearningItem item) {
        events.publish(new ItemsChanged(item.courseId(), item.moduleId(), Set.of(item.id()), ChangeType.UPDATED));
    }

    /** Review cards for items of one course, in the order given. */
    private List<ItemDetail> details(String courseId, List<LearningItem> items) {
        if (items.isEmpty()) {
            return List.of();
        }
        Map<String, Concept> concepts = conceptsById(courseId);
        Map<String, List<String>> links = repos.items().conceptLinks(courseId);
        Map<String, ChunkWithTitle> sources = sources(items);
        Map<String, List<Flag>> flags = new HashMap<>();
        repos.flags().listByCourse(courseId, true)
                .forEach(f -> flags.computeIfAbsent(f.itemId(), k -> new ArrayList<>()).add(f));
        Map<String, ItemStats> itemStats = stats(courseId, items, flags);
        return items.stream().map(item -> {
            List<Concept> tagged = links.getOrDefault(item.id(), List.of()).stream()
                    .map(concepts::get).filter(Objects::nonNull).toList();
            ChunkWithTitle source = item.sourceChunkId() == null ? null : sources.get(item.sourceChunkId());
            return new ItemDetail(item, tagged, source == null ? null : passage(source),
                    flags.getOrDefault(item.id(), List.of()), itemStats.get(item.id()));
        }).toList();
    }

    private static SourcePassage passage(ChunkWithTitle source) {
        var chunk = source.chunk();
        return new SourcePassage(new SourceRef(chunk.documentId(), source.documentTitle(), chunk.pageStart(),
                chunk.pageEnd(), chunk.id()), chunk.sectionTitle(), chunk.text());
    }

    /** Statistics per item: from the source when there is one, otherwise from this profile's attempts. */
    private Map<String, ItemStats> stats(String courseId, List<LearningItem> items, Map<String, List<Flag>> flags) {
        Map<String, ItemStats> result = new HashMap<>();
        if (stats != null) {
            for (LearningItem item : items) {
                try {
                    ItemStats found = stats.statsFor(item.id());
                    if (found != null) {
                        result.put(item.id(), found);
                    }
                } catch (RuntimeException e) {
                    LOG.warn("Statistics of item {} are unavailable; using this profile's attempts", item.id(), e);
                }
            }
            if (result.size() == items.size()) {
                return result;
            }
        }
        Map<String, ItemAttemptStats> attempts = repos.sessions().itemStats(courseId);
        Map<String, ReviewState> states = repos.reviews().findAll(items.stream().map(LearningItem::id).toList());
        for (LearningItem item : items) {
            if (result.containsKey(item.id())) {
                continue;
            }
            ItemAttemptStats a = attempts.get(item.id());
            ReviewState s = states.get(item.id());
            int openFlags = (int) flags.getOrDefault(item.id(), List.of()).stream().filter(Flag::isOpen).count();
            result.put(item.id(), new ItemStats(item.id(), item.type(), item.prompt(), item.status(),
                    a == null ? 0 : a.attempts(), a == null ? 0 : a.correct(),
                    a == null || a.attempts() == 0 ? null : a.accuracy(),
                    a == null ? null : a.averageScore(), a == null ? null : a.averageDurationMs(),
                    a == null ? null : a.lastAttemptAt(),
                    s == null ? null : s.phase(), s == null ? null : s.dueAt(), s == null ? null : s.stability(),
                    s == null ? 0 : s.lapses(), openFlags, 0, null));
        }
        return result;
    }

    private Map<String, ChunkWithTitle> sources(List<LearningItem> items) {
        Set<String> chunkIds = items.stream().map(LearningItem::sourceChunkId).filter(Objects::nonNull)
                .collect(Collectors.toSet());
        Map<String, ChunkWithTitle> sources = new HashMap<>();
        repos.chunks().listWithTitles(chunkIds).forEach(c -> sources.put(c.chunk().id(), c));
        return sources;
    }

    private Map<String, Concept> conceptsById(String courseId) {
        Map<String, Concept> concepts = new HashMap<>();
        repos.concepts().listByCourse(courseId).forEach(c -> concepts.put(c.id(), c));
        return concepts;
    }

    private Map<String, String> moduleTitles(String courseId) {
        Map<String, String> titles = new HashMap<>();
        repos.modules().listByCourse(courseId).forEach((CourseModule m) -> titles.put(m.id(), m.title()));
        return titles;
    }
}
