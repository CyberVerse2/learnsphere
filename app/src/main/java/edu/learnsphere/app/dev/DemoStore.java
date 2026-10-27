package edu.learnsphere.app.dev;

import edu.learnsphere.core.error.NotFoundException;
import edu.learnsphere.core.error.UserMessage;
import edu.learnsphere.core.events.EventBus;
import edu.learnsphere.core.model.Attempt;
import edu.learnsphere.core.model.ChatMessage;
import edu.learnsphere.core.model.ChatThread;
import edu.learnsphere.core.model.ClassInsight;
import edu.learnsphere.core.model.Concept;
import edu.learnsphere.core.model.Course;
import edu.learnsphere.core.model.CourseModule;
import edu.learnsphere.core.model.CourseStatus;
import edu.learnsphere.core.model.CourseVersion;
import edu.learnsphere.core.model.Document;
import edu.learnsphere.core.model.DocumentPage;
import edu.learnsphere.core.model.DocumentStatus;
import edu.learnsphere.core.model.Flag;
import edu.learnsphere.core.model.ItemStatus;
import edu.learnsphere.core.model.LearningItem;
import edu.learnsphere.core.model.ReviewPhase;
import edu.learnsphere.core.model.ReviewState;
import edu.learnsphere.core.service.CourseSummary;
import edu.learnsphere.core.service.DocumentSummary;
import edu.learnsphere.core.service.SourcePassage;
import edu.learnsphere.core.service.SourceRef;
import edu.learnsphere.core.util.Ids;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Everything the demo profile holds, in memory: courses, modules, PDFs and their pages, passages, concepts,
 * questions, review states, attempts, conversations and flags. The demo services read and change it while
 * holding its monitor ({@code synchronized (store)}), so it is safe to use from any thread.
 */
final class DemoStore {

    /**
     * A passage of a PDF that questions and answers cite.
     *
     * @param page    the page it is on
     * @param section the heading it sits under
     */
    record Passage(String id, String documentId, String courseId, String moduleId, int page, String section,
                   String text) {
    }

    final Clock clock;
    final EventBus events;
    final String profileName;

    final Map<String, Course> courses = new LinkedHashMap<>();
    final Set<String> enrolled = new LinkedHashSet<>();
    final Map<String, List<CourseVersion>> versions = new HashMap<>();
    final Map<String, CourseModule> modules = new LinkedHashMap<>();
    final Map<String, Document> documents = new LinkedHashMap<>();
    final Map<String, Map<Integer, DocumentPage>> pages = new HashMap<>();
    final Map<String, Passage> passages = new LinkedHashMap<>();
    final Map<String, Concept> concepts = new LinkedHashMap<>();
    /** Concepts the study assistant found and nobody has renamed since. */
    final Set<String> extractedConcepts = new LinkedHashSet<>();
    final Map<String, LearningItem> items = new LinkedHashMap<>();
    final Map<String, Set<String>> itemConcepts = new HashMap<>();
    final Map<String, ReviewState> reviews = new HashMap<>();
    final List<Attempt> attempts = new ArrayList<>();
    final Map<String, ChatThread> threads = new LinkedHashMap<>();
    final Map<String, List<ChatMessage>> messages = new HashMap<>();
    final Map<String, Flag> flags = new LinkedHashMap<>();
    final Map<String, ClassInsight> insights = new LinkedHashMap<>();
    /** The class's results per question, by item id, once a class's progress is in ({@link DemoClassProgress}). */
    final Map<String, DemoClassProgress.ItemResult> classResults = new HashMap<>();
    /** Study time per day, for the progress screens. */
    final Map<LocalDate, Long> studyTime = new HashMap<>();

    DemoStore(Clock clock, EventBus events, String profileName) {
        this.clock = Objects.requireNonNull(clock, "clock");
        this.events = Objects.requireNonNull(events, "events");
        this.profileName = Objects.requireNonNull(profileName, "profileName");
    }

    static String newId() {
        return Ids.newId();
    }

    long now() {
        return clock.millis();
    }

    ZoneId zone() {
        return clock.getZone();
    }

    LocalDate today() {
        return LocalDate.now(clock);
    }

    long startOfDay(LocalDate date) {
        return date.atStartOfDay(zone()).toInstant().toEpochMilli();
    }

    LocalDate dateOf(long millis) {
        return java.time.Instant.ofEpochMilli(millis).atZone(zone()).toLocalDate();
    }

    // ---------------------------------------------------------------------------------------------------
    // Lookups
    // ---------------------------------------------------------------------------------------------------

    Course course(String courseId) {
        Course course = courses.get(courseId);
        if (course == null) {
            throw new NotFoundException(UserMessage.COURSE_NOT_FOUND, "No course " + courseId);
        }
        return course;
    }

    CourseModule module(String moduleId) {
        CourseModule module = modules.get(moduleId);
        if (module == null) {
            throw new NotFoundException(UserMessage.MODULE_NOT_FOUND, "No module " + moduleId);
        }
        return module;
    }

    Document document(String documentId) {
        Document document = documents.get(documentId);
        if (document == null) {
            throw new NotFoundException(UserMessage.DOCUMENT_NOT_FOUND, "No document " + documentId);
        }
        return document;
    }

    LearningItem item(String itemId) {
        LearningItem item = items.get(itemId);
        if (item == null) {
            throw new NotFoundException(UserMessage.ITEM_NOT_FOUND, "No item " + itemId);
        }
        return item;
    }

    List<CourseModule> modulesOf(String courseId) {
        return modules.values().stream().filter(m -> m.courseId().equals(courseId))
                .sorted(Comparator.comparingInt(CourseModule::position)).toList();
    }

    List<Document> documentsOf(String courseId) {
        return documents.values().stream().filter(d -> d.courseId().equals(courseId)).toList();
    }

    List<LearningItem> itemsOf(String courseId) {
        return items.values().stream().filter(i -> i.courseId().equals(courseId)).toList();
    }

    List<LearningItem> studyItems(String courseId) {
        return items.values().stream()
                .filter(i -> courseId == null ? enrolled.contains(i.courseId()) : i.courseId().equals(courseId))
                .filter(i -> i.status() == ItemStatus.APPROVED).toList();
    }

    List<Concept> conceptsOf(String courseId) {
        return concepts.values().stream().filter(c -> c.courseId().equals(courseId)).toList();
    }

    List<Concept> conceptsOfItem(String itemId) {
        return itemConcepts.getOrDefault(itemId, Set.of()).stream().map(concepts::get).filter(Objects::nonNull).toList();
    }

    /** True when the item has been studied at least once. */
    boolean started(String itemId) {
        ReviewState state = reviews.get(itemId);
        return state != null && state.phase() != ReviewPhase.NEW;
    }

    boolean due(String itemId, long at) {
        ReviewState state = reviews.get(itemId);
        return state != null && state.phase() != ReviewPhase.NEW && state.dueAt() <= at;
    }

    String moduleTitle(String moduleId) {
        CourseModule module = moduleId == null ? null : modules.get(moduleId);
        return module == null ? null : module.title();
    }

    Passage passage(String passageId) {
        return passageId == null ? null : passages.get(passageId);
    }

    SourceRef sourceRef(Passage passage) {
        Document document = documents.get(passage.documentId());
        return new SourceRef(passage.documentId(), document == null ? "" : document.title(), passage.page(),
                passage.page(), passage.id());
    }

    SourcePassage sourcePassage(LearningItem item) {
        Passage passage = passage(item.sourceChunkId());
        return passage == null ? null : new SourcePassage(sourceRef(passage), passage.section(), passage.text());
    }

    int streakDays() {
        LocalDate day = today();
        Set<LocalDate> active = new java.util.HashSet<>();
        for (Attempt attempt : attempts) {
            active.add(dateOf(attempt.createdAt()));
        }
        if (!active.contains(day)) {
            day = day.minusDays(1);
        }
        int streak = 0;
        while (active.contains(day)) {
            streak++;
            day = day.minusDays(1);
        }
        return streak;
    }

    int reviewsOn(LocalDate date) {
        int count = 0;
        for (Attempt attempt : attempts) {
            if (dateOf(attempt.createdAt()).equals(date)) {
                count++;
            }
        }
        return count;
    }

    // ---------------------------------------------------------------------------------------------------
    // Summaries
    // ---------------------------------------------------------------------------------------------------

    CourseSummary summary(String courseId) {
        Course course = course(courseId);
        long now = now();
        List<Document> docs = documentsOf(courseId);
        int processing = (int) docs.stream().filter(d -> isProcessing(d.status())).count();
        int attention = (int) docs.stream()
                .filter(d -> d.status() == DocumentStatus.NEEDS_REVIEW || d.status() == DocumentStatus.FAILED).count();
        List<LearningItem> all = itemsOf(courseId);
        List<LearningItem> approved = all.stream().filter(i -> i.status() == ItemStatus.APPROVED).toList();
        int drafts = (int) all.stream().filter(i -> i.status() == ItemStatus.DRAFT).count();
        int fresh = (int) approved.stream().filter(i -> !started(i.id())).count();
        int due = (int) approved.stream().filter(i -> due(i.id(), now)).count();
        int openFlags = (int) flags.values().stream()
                .filter(f -> f.resolvedAt() == null && items.containsKey(f.itemId())
                        && items.get(f.itemId()).courseId().equals(courseId)).count();
        double progress = approved.isEmpty() ? 0 : (approved.size() - fresh) / (double) approved.size();
        return new CourseSummary(course.id(), course.title(), course.subtitle(), course.authorName(), course.coverHue(),
                course.origin(), course.status(), course.visibility(), course.versionNumber(),
                course.origin() == edu.learnsphere.core.model.CourseOrigin.LOCAL, enrolled.contains(courseId),
                modulesOf(courseId).size(), docs.size(), processing, attention, approved.size(), drafts, fresh, due,
                openFlags, progress, course.lastOpenedAt(), course.updatedAt());
    }

    static boolean isProcessing(DocumentStatus status) {
        return switch (status) {
            case QUEUED, CHECKING, EXTRACTING, READING, INDEXING -> true;
            default -> false;
        };
    }

    DocumentSummary documentSummary(Document document) {
        int[] steps = steps(document);
        int needingReview = (int) pages.getOrDefault(document.id(), Map.of()).values().stream()
                .filter(p -> p.source() == edu.learnsphere.core.model.PageSource.AI_READ && !p.reviewed()).count();
        return new DocumentSummary(document.id(), document.courseId(), document.moduleId(),
                moduleTitle(document.moduleId()), document.title(), document.fileName(), document.byteSize(),
                document.pageCount(), document.status(), document.progress(), steps[0], steps[1],
                document.errorCode() == null ? null : document.errorCode().userMessage(), needingReview,
                document.createdAt(), document.updatedAt());
    }

    /** Step counters for the status line: pages read so far of the total. */
    private static int[] steps(Document document) {
        return switch (document.status()) {
            case EXTRACTING -> new int[] {(int) Math.round(document.progress() * document.pageCount()), document.pageCount()};
            case READING -> {
                int scanned = Math.max(1, document.pageCount() / 10);
                yield new int[] {(int) Math.round(document.progress() * scanned), scanned};
            }
            default -> new int[] {0, 0};
        };
    }

    boolean isPublished(String courseId) {
        Course course = course(courseId);
        return course.status() == CourseStatus.PUBLISHED || course.versionNumber() > 0;
    }
}
