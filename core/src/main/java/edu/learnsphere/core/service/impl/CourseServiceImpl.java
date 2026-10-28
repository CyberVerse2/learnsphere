package edu.learnsphere.core.service.impl;

import edu.learnsphere.core.db.ConceptRepository.ConceptCounts;
import edu.learnsphere.core.db.CourseRepository.CourseCounts;
import edu.learnsphere.core.db.CourseRepository.CourseRow;
import edu.learnsphere.core.db.ItemQuery;
import edu.learnsphere.core.db.ModuleRepository.ModuleCounts;
import edu.learnsphere.core.db.Repositories;
import edu.learnsphere.core.db.StudyScope;
import edu.learnsphere.core.error.ConflictException;
import edu.learnsphere.core.error.InvalidInputException;
import edu.learnsphere.core.error.NotFoundException;
import edu.learnsphere.core.error.UserMessage;
import edu.learnsphere.core.events.ChangeType;
import edu.learnsphere.core.events.CourseChanged;
import edu.learnsphere.core.events.DocumentChanged;
import edu.learnsphere.core.events.EventBus;
import edu.learnsphere.core.events.ItemsChanged;
import edu.learnsphere.core.events.ProgressChanged;
import edu.learnsphere.core.model.Concept;
import edu.learnsphere.core.model.Course;
import edu.learnsphere.core.model.CourseModule;
import edu.learnsphere.core.model.CourseOrigin;
import edu.learnsphere.core.model.CourseStatus;
import edu.learnsphere.core.model.CourseVersion;
import edu.learnsphere.core.model.Document;
import edu.learnsphere.core.model.EntityType;
import edu.learnsphere.core.model.ItemStatus;
import edu.learnsphere.core.model.JobKind;
import edu.learnsphere.core.model.LearningItem;
import edu.learnsphere.core.model.Visibility;
import edu.learnsphere.core.service.ConceptMastery;
import edu.learnsphere.core.service.CourseDetail;
import edu.learnsphere.core.service.CourseDraft;
import edu.learnsphere.core.service.CourseFilter;
import edu.learnsphere.core.service.CourseScope;
import edu.learnsphere.core.service.CourseService;
import edu.learnsphere.core.service.CourseSummary;
import edu.learnsphere.core.service.CourseUpdate;
import edu.learnsphere.core.service.JobQueue;
import edu.learnsphere.core.service.ModulePlan;
import edu.learnsphere.core.service.ModuleSummary;
import edu.learnsphere.core.service.impl.pipeline.CourseOutline;
import edu.learnsphere.core.spi.ModuleSuggester;
import edu.learnsphere.core.spi.SearchIndex;
import edu.learnsphere.core.util.CancellationToken;
import edu.learnsphere.core.util.Ids;
import java.time.Clock;
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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * {@link CourseService} over the profile database.
 *
 * <p>Course cards are built from one counting query plus the due counts of {@link StudyScope#enrolled()}:
 * only a course the learner studies (active enrolment, not archived) has anything due. Progress is the share
 * of approved items studied at least once.
 *
 * <p>Publishing freezes the approved items into a new {@code course_versions} row (version 1, 2, …) in one
 * transaction with its audit entry; archived courses stay archived. Deleting a course cancels its
 * background work, deletes it with everything in it (documents, pages, passages, items, progress, threads),
 * removes the stored PDFs and page images nothing else uses and its passages from the search index.
 *
 * <p>Courses imported from a package can be studied, archived, recoloured and deleted, but not edited:
 * those changes are refused with a {@link edu.learnsphere.core.error.ConflictException}. Thread-safe.
 */
public final class CourseServiceImpl implements CourseService {

    private static final Logger LOG = LoggerFactory.getLogger(CourseServiceImpl.class);

    static final int MAX_TITLE = 200;
    static final int MAX_SUBTITLE = 300;
    static final int MAX_DESCRIPTION = 5_000;
    static final int MAX_AUTHOR = 120;
    static final int MAX_NOTES = 2_000;
    static final int MAX_MODULE_SUMMARY = 2_000;
    /** Most modules kept from a suggestion. */
    static final int MAX_SUGGESTED_MODULES = 12;

    /** Hues offered for new courses, in the order they are handed out. */
    static final int[] COVER_HUES = {210, 150, 28, 262, 340, 190, 95, 45, 300, 120, 0, 235};

    static final UserMessage TITLE_REQUIRED = UserMessage.of("Enter a title for your course.");
    static final UserMessage MODULE_TITLE_REQUIRED = UserMessage.of("Enter a title for the module.");
    static final UserMessage TEXT_TOO_LONG = UserMessage.of("This text is too long. Shorten it and try again.");
    static final UserMessage HUE_INVALID = UserMessage.of("Pick one of the cover colours.");
    static final UserMessage NOTHING_TO_PUBLISH = UserMessage.of("Approve at least one question before publishing.");
    static final UserMessage HAS_MODULES = UserMessage.of("This course already has modules.");
    static final UserMessage NO_READY_PDF = UserMessage.of("Add a PDF and wait until it's ready, then try again.");
    static final UserMessage PDFS_CHANGED = UserMessage.of("The course's PDFs changed. Suggest modules again.");

    /**
     * Mastery per concept of a course, as {@code ProgressService.conceptMastery} computes it. Without one,
     * the course page lists its concepts with their counts and no mastery yet.
     */
    @FunctionalInterface
    public interface ConceptMasterySource {

        /** Mastery per concept of the course, in any order. */
        List<ConceptMastery> conceptMastery(String courseId);
    }

    private final Repositories repos;
    private final EventBus events;
    private final Clock clock;
    private final JobQueue jobs;
    private final IndexUpdates index;
    private final PdfStore pdfs;
    private final ConceptMasterySource mastery;
    private final ModuleSuggester suggester;

    /** A course service whose course page shows concepts without mastery and that can't suggest modules. */
    public CourseServiceImpl(Repositories repos, EventBus events, Clock clock, JobQueue jobs, SearchIndex index,
                             PdfStore pdfs) {
        this(repos, events, clock, jobs, index, pdfs, null, ModuleSuggester.NONE);
    }

    /** A course service that can't suggest modules. */
    public CourseServiceImpl(Repositories repos, EventBus events, Clock clock, JobQueue jobs, SearchIndex index,
                             PdfStore pdfs, ConceptMasterySource mastery) {
        this(repos, events, clock, jobs, index, pdfs, mastery, ModuleSuggester.NONE);
    }

    /**
     * @param index   the profile's search index (a deleted course's passages are removed)
     * @param pdfs    the profile's stored PDFs, shared with {@link DocumentServiceImpl}
     * @param mastery   concept mastery for the course page, or null to show concepts without mastery
     * @param suggester groups a course's sections into modules
     */
    public CourseServiceImpl(Repositories repos, EventBus events, Clock clock, JobQueue jobs, SearchIndex index,
                             PdfStore pdfs, ConceptMasterySource mastery, ModuleSuggester suggester) {
        this.repos = Objects.requireNonNull(repos, "repos");
        this.events = Objects.requireNonNull(events, "events");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.jobs = Objects.requireNonNull(jobs, "jobs");
        this.index = new IndexUpdates(Objects.requireNonNull(index, "index"), repos, jobs);
        this.pdfs = Objects.requireNonNull(pdfs, "pdfs");
        this.mastery = mastery;
        this.suggester = Objects.requireNonNull(suggester, "suggester");
    }

    // ---- reading -----------------------------------------------------------------------------

    @Override
    public List<CourseSummary> list(CourseFilter filter) {
        Objects.requireNonNull(filter, "filter");
        long now = clock.millis();
        CourseOrigin origin = filter.scope() == CourseScope.CREATING ? CourseOrigin.LOCAL : null;
        boolean enrolledOnly = filter.scope() == CourseScope.STUDYING;
        List<CourseRow> rows = repos.courses().listRows(origin, enrolledOnly, filter.search(), filter.includeArchived(), now);
        Map<String, Integer> due = repos.reviews().countDueByCourse(StudyScope.enrolled(), now);
        return rows.stream().map(row -> summary(row, due.getOrDefault(row.course().id(), 0))).toList();
    }

    @Override
    public CourseSummary summary(String courseId) {
        return summary(requireRow(courseId));
    }

    @Override
    public CourseDetail get(String courseId) {
        CourseRow row = requireRow(courseId);
        long now = clock.millis();
        List<ModuleSummary> modules = repos.modules().listWithCounts(courseId, StudyScope.enrolled(), now).stream()
                .map(CourseServiceImpl::moduleSummary)
                .toList();
        CourseVersion latest = repos.courses().latestVersion(courseId).orElse(null);
        return new CourseDetail(row.course(), summary(row), latest, modules, concepts(courseId));
    }

    @Override
    public List<CourseVersion> versions(String courseId) {
        repos.courses().get(courseId);
        return repos.courses().listVersions(courseId);
    }

    @Override
    public List<CourseModule> modules(String courseId) {
        repos.courses().get(courseId);
        return repos.modules().listByCourse(courseId);
    }

    // ---- course changes ----------------------------------------------------------------------

    @Override
    public Course create(CourseDraft draft) {
        Objects.requireNonNull(draft, "draft");
        String title = requireTitle(draft.title());
        String subtitle = optionalText(draft.subtitle(), MAX_SUBTITLE, "subtitle");
        String description = optionalText(draft.description(), MAX_DESCRIPTION, "description");
        String author = optionalText(draft.authorName(), MAX_AUTHOR, "author");
        int hue = draft.coverHue() == null ? pickHue(repos.courses().list().stream().map(Course::coverHue).toList())
                : requireHue(draft.coverHue());
        long now = clock.millis();
        Course course = new Course(Ids.newId(), title, subtitle, description, author, CourseOrigin.LOCAL, Visibility.PRIVATE,
                CourseStatus.DRAFT, hue, 0, null, Ids.newId(), null, now, now);
        repos.courses().insert(course);
        events.publish(new CourseChanged(course.id(), ChangeType.CREATED));
        return course;
    }

    @Override
    public Course update(String courseId, CourseUpdate update) {
        Objects.requireNonNull(update, "update");
        ServiceSupport.requireEditableCourse(repos, courseId);
        String title = requireTitle(update.title());
        String subtitle = optionalText(update.subtitle(), MAX_SUBTITLE, "subtitle");
        String description = optionalText(update.description(), MAX_DESCRIPTION, "description");
        String author = optionalText(update.authorName(), MAX_AUTHOR, "author");
        repos.courses().updateDetails(courseId, title, subtitle, description, author);
        changed(courseId);
        return repos.courses().get(courseId);
    }

    @Override
    public void setCoverHue(String courseId, int hue) {
        requireHue(hue);
        Course course = repos.courses().get(courseId);
        if (course.coverHue() != hue) {
            repos.courses().setCoverHue(courseId, hue);
            changed(courseId);
        }
    }

    @Override
    public void setVisibility(String courseId, Visibility visibility) {
        Objects.requireNonNull(visibility, "visibility");
        Course course = ServiceSupport.requireEditableCourse(repos, courseId);
        if (course.visibility() != visibility) {
            repos.courses().setVisibility(courseId, visibility);
            changed(courseId);
        }
    }

    @Override
    public void archive(String courseId) {
        Course course = repos.courses().get(courseId);
        if (course.status() != CourseStatus.ARCHIVED) {
            repos.courses().setStatus(courseId, CourseStatus.ARCHIVED);
            changedWithProgress(courseId);
        }
    }

    @Override
    public void unarchive(String courseId) {
        Course course = repos.courses().get(courseId);
        if (course.status() == CourseStatus.ARCHIVED) {
            repos.courses().setStatus(courseId, course.isPublished() ? CourseStatus.PUBLISHED : CourseStatus.DRAFT);
            changedWithProgress(courseId);
        }
    }

    @Override
    public void delete(String courseId) {
        Course course = repos.courses().get(courseId);
        List<Document> documents = repos.documents().listByCourse(courseId);
        Set<String> subjects = new HashSet<>();
        subjects.add(courseId);
        repos.modules().listByCourse(courseId).forEach(m -> subjects.add(m.id()));
        documents.forEach(d -> subjects.add(d.id()));
        ServiceSupport.cancelJobs(jobs, repos, job -> job.subjectId() != null && (subjects.contains(job.subjectId())
                || (job.kind() == JobKind.REGENERATE_ITEM && repos.items().find(job.subjectId())
                        .map(LearningItem::courseId).filter(courseId::equals).isPresent())));

        List<String> hashes = documents.stream().map(Document::fileSha256).distinct().toList();
        List<String> documentIds = documents.stream().map(Document::id).toList();
        pdfs.delete(hashes, documentIds, () -> {
            repos.database().useTransaction(h -> {
                repos.courses().delete(courseId);
                AuditAction.DELETE.record(repos.audit(), EntityType.COURSE, courseId, course.title());
            });
            return null;
        });
        index.removeCourse(courseId);
        events.publish(new CourseChanged(courseId, ChangeType.DELETED));
        events.publish(new ProgressChanged(courseId));
    }

    @Override
    public CourseVersion publish(String courseId, String notes) {
        ServiceSupport.requireEditableCourse(repos, courseId);
        String cleanNotes = optionalText(notes, MAX_NOTES, "version notes");
        CourseVersion version = repos.database().inTransaction(h -> {
            Course current = repos.courses().get(courseId);
            List<String> approved = repos.items().list(ItemQuery.forCourse(courseId).withStatuses(ItemStatus.APPROVED))
                    .stream().map(LearningItem::id).toList();
            if (approved.isEmpty()) {
                throw new InvalidInputException(NOTHING_TO_PUBLISH, "Course " + courseId + " has no approved items");
            }
            CourseVersion published = repos.courses().publishVersion(courseId, cleanNotes, approved, clock.millis());
            if (current.status() == CourseStatus.ARCHIVED) {
                repos.courses().setStatus(courseId, CourseStatus.ARCHIVED);
            }
            AuditAction.PUBLISH.record(repos.audit(), EntityType.COURSE, courseId,
                    "version " + published.number() + ", " + approved.size() + " items");
            return published;
        });
        changed(courseId);
        return version;
    }

    // ---- modules -----------------------------------------------------------------------------

    @Override
    public CourseModule addModule(String courseId, String title) {
        ServiceSupport.requireEditableCourse(repos, courseId);
        String clean = requireModuleTitle(title);
        CourseModule module = repos.modules().append(courseId, clean);
        changed(courseId);
        return module;
    }

    @Override
    public CourseModule renameModule(String moduleId, String title) {
        CourseModule module = editableModule(moduleId);
        String clean = requireModuleTitle(title);
        if (!clean.equals(module.title())) {
            repos.modules().rename(moduleId, clean);
            changed(module.courseId());
        }
        return repos.modules().get(moduleId);
    }

    @Override
    public CourseModule setModuleSummary(String moduleId, String summary) {
        CourseModule module = editableModule(moduleId);
        String clean = optionalText(summary, MAX_MODULE_SUMMARY, "module summary");
        if (!clean.equals(module.summary())) {
            repos.modules().updateSummary(moduleId, clean);
            changed(module.courseId());
        }
        return repos.modules().get(moduleId);
    }

    @Override
    public void reorderModules(String courseId, List<String> orderedModuleIds) {
        Objects.requireNonNull(orderedModuleIds, "orderedModuleIds");
        ServiceSupport.requireEditableCourse(repos, courseId);
        repos.modules().reorder(courseId, orderedModuleIds);
        changed(courseId);
    }

    @Override
    public ModulePlan suggestModules(String courseId) {
        Course course = ServiceSupport.requireEditableCourse(repos, courseId);
        requireNoModules(courseId);
        List<CourseOutline.Section> outline = CourseOutline.of(repos, courseId);
        if (outline.isEmpty()) {
            throw new InvalidInputException(NO_READY_PDF, "course " + courseId + " has no ready document");
        }
        List<ModuleSuggester.Section> sections = outline.stream()
                .map(s -> new ModuleSuggester.Section(s.documentTitle(), s.heading())).toList();
        List<ModuleSuggester.Suggestion> suggested = suggester.suggest(course.title(), sections, CancellationToken.NONE);
        List<String> headings = outline.stream().map(CourseOutline.Section::heading).toList();
        return new ModulePlan(headings, units(suggested, headings.size(), course.title()));
    }

    /**
     * The suggestions that make sense as a plan: in range, titled, one per starting section, in order, at most
     * {@link #MAX_SUGGESTED_MODULES}, the first starting at the first section. Without any, one module named after
     * the course.
     */
    static List<ModulePlan.Unit> units(List<ModuleSuggester.Suggestion> suggested, int sections, String course) {
        Map<Integer, ModulePlan.Unit> byStart = new LinkedHashMap<>();
        suggested.stream()
                .filter(s -> s.firstSection() >= 0 && s.firstSection() < sections)
                .sorted(Comparator.comparingInt(ModuleSuggester.Suggestion::firstSection))
                .forEach(s -> {
                    String title = clip(s.title(), MAX_TITLE);
                    if (!title.isEmpty()) {
                        byStart.putIfAbsent(s.firstSection(),
                                new ModulePlan.Unit(title, clip(s.summary(), MAX_MODULE_SUMMARY), s.firstSection()));
                    }
                });
        List<ModulePlan.Unit> units = new ArrayList<>(byStart.values().stream().limit(MAX_SUGGESTED_MODULES).toList());
        if (units.isEmpty()) {
            return List.of(new ModulePlan.Unit(clip(course, MAX_TITLE), "", 0));
        }
        ModulePlan.Unit first = units.getFirst();
        units.set(0, new ModulePlan.Unit(first.title(), first.summary(), 0));
        return units;
    }

    private static String clip(String text, int max) {
        String clean = text.strip().replaceAll("\\s+", " ");
        return clean.length() <= max ? clean : clean.substring(0, max - 1).strip() + "…";
    }

    @Override
    public List<CourseModule> applyModules(String courseId, ModulePlan plan) {
        Objects.requireNonNull(plan, "plan");
        ServiceSupport.requireEditableCourse(repos, courseId);
        requireNoModules(courseId);
        List<CourseOutline.Section> outline = CourseOutline.of(repos, courseId);
        if (!outline.stream().map(CourseOutline.Section::heading).toList().equals(plan.sections())) {
            throw new ConflictException(PDFS_CHANGED, "outline of course " + courseId + " differs from the plan");
        }
        List<String> titles = plan.units().stream().map(u -> requireModuleTitle(u.title())).toList();
        List<String> summaries = plan.units().stream()
                .map(u -> optionalText(u.summary(), MAX_MODULE_SUMMARY, "module summary")).toList();
        Map<String, Set<Integer>> unitsByDocument = new LinkedHashMap<>();
        for (int i = 0; i < outline.size(); i++) {
            unitsByDocument.computeIfAbsent(outline.get(i).documentId(), k -> new HashSet<>()).add(plan.unitOf(i));
        }
        List<String> moved = new ArrayList<>();
        List<CourseModule> modules = repos.database().inTransaction(h -> {
            List<CourseModule> created = new ArrayList<>();
            for (int i = 0; i < titles.size(); i++) {
                CourseModule module = repos.modules().append(courseId, titles.get(i));
                if (!summaries.get(i).isEmpty()) {
                    repos.modules().updateSummary(module.id(), summaries.get(i));
                }
                created.add(module);
            }
            for (int i = 0; i < outline.size(); i++) {
                repos.chunks().setModule(outline.get(i).chunkIds(), created.get(plan.unitOf(i)).id());
            }
            unitsByDocument.forEach((documentId, units) -> {
                if (units.size() == 1) {
                    repos.documents().setModule(documentId, created.get(units.iterator().next()).id());
                    moved.add(documentId);
                }
            });
            repos.items().fileUnderSourceModules(courseId);
            repos.concepts().fileUnderItemModules(courseId);
            return created.stream().map(m -> repos.modules().get(m.id())).toList();
        });
        unitsByDocument.keySet().forEach(index::refreshDocument);
        changed(courseId);
        for (String documentId : moved) {
            Document d = repos.documents().get(documentId);
            events.publish(new DocumentChanged(d.id(), courseId, d.status(), d.progress(), 0, 0, null, ChangeType.UPDATED));
        }
        events.publish(new ItemsChanged(courseId, null, Set.of(), ChangeType.UPDATED));
        LOG.info("Created {} suggested modules for course {}", modules.size(), courseId);
        return modules;
    }

    private void requireNoModules(String courseId) {
        if (!repos.modules().listByCourse(courseId).isEmpty()) {
            throw new ConflictException(HAS_MODULES, "course " + courseId + " already has modules");
        }
    }

    @Override
    public void deleteModule(String moduleId) {
        CourseModule module = editableModule(moduleId);
        ServiceSupport.cancelJobsFor(jobs, repos, List.of(moduleId));
        repos.modules().delete(moduleId);
        changed(module.courseId());
    }

    // ---- studying ----------------------------------------------------------------------------

    @Override
    public void enrol(String courseId) {
        repos.courses().get(courseId);
        boolean wasEnrolled = repos.courses().isEnrolled(courseId);
        if (!wasEnrolled) {
            repos.courses().enrol(courseId, clock.millis());
            changedWithProgress(courseId);
        }
    }

    @Override
    public void leave(String courseId) {
        repos.courses().get(courseId);
        if (repos.courses().isEnrolled(courseId)) {
            repos.courses().leave(courseId);
            changedWithProgress(courseId);
        }
    }

    @Override
    public void markOpened(String courseId) {
        repos.courses().get(courseId);
        repos.courses().markOpened(courseId, clock.millis());
        changed(courseId);
    }

    // ---- helpers -----------------------------------------------------------------------------

    private CourseRow requireRow(String courseId) {
        return repos.courses().findRow(courseId, clock.millis())
                .orElseThrow(() -> NotFoundException.of(UserMessage.COURSE_NOT_FOUND, "course", courseId));
    }

    /** The card of one course; due items count only while the learner studies it. */
    private CourseSummary summary(CourseRow row) {
        Course course = row.course();
        int due = row.enrolled() && course.status() != CourseStatus.ARCHIVED
                ? repos.reviews().countDue(StudyScope.course(course.id()), clock.millis())
                : 0;
        return summary(row, due);
    }

    static CourseSummary summary(CourseRow row, int dueCount) {
        Course c = row.course();
        CourseCounts counts = row.counts();
        double progress = counts.approvedItems() == 0 ? 0
                : Math.min(1, (double) counts.startedItems() / counts.approvedItems());
        return new CourseSummary(c.id(), c.title(), c.subtitle(), c.authorName(), c.coverHue(), c.origin(), c.status(),
                c.visibility(), c.versionNumber(), c.isCreatedHere(), row.enrolled(), counts.moduleCount(),
                counts.documentCount(), counts.documentsProcessing(), counts.documentsNeedingAttention(),
                counts.approvedItems(), counts.draftItems(), Math.max(0, counts.newItems()), dueCount, counts.openFlags(),
                progress, c.lastOpenedAt(), c.updatedAt());
    }

    private static ModuleSummary moduleSummary(ModuleCounts counts) {
        return new ModuleSummary(counts.module(), counts.approvedItems(), counts.draftItems(), counts.documents(),
                counts.dueItems(), counts.completion());
    }

    /** Concept chips in module order, with mastery when a source is available. */
    private List<ConceptMastery> concepts(String courseId) {
        List<Concept> concepts = repos.concepts().listByCourse(courseId);
        if (concepts.isEmpty()) {
            return List.of();
        }
        Map<String, ConceptMastery> known = new HashMap<>();
        if (mastery != null) {
            try {
                mastery.conceptMastery(courseId).forEach(m -> known.put(m.conceptId(), m));
            } catch (RuntimeException e) {
                LOG.warn("Concept mastery of course {} is unavailable; showing concepts without it", courseId, e);
                known.clear();
            }
        }
        Map<String, ConceptCounts> counts = known.size() >= concepts.size() ? Map.of() : repos.concepts().studyCounts(courseId);
        return concepts.stream().map(concept -> {
            ConceptMastery found = known.get(concept.id());
            if (found != null) {
                return found;
            }
            ConceptCounts c = counts.get(concept.id());
            return new ConceptMastery(concept.id(), concept.name(), concept.moduleId(), null, null,
                    c == null ? 0 : c.approvedItems(), c == null ? 0 : c.reviewedItems(), c == null ? 0 : c.attempts());
        }).toList();
    }

    private CourseModule editableModule(String moduleId) {
        CourseModule module = repos.modules().get(moduleId);
        ServiceSupport.requireEditableCourse(repos, module.courseId());
        return module;
    }

    private static String requireTitle(String title) {
        return ServiceSupport.requireText(title, MAX_TITLE, TITLE_REQUIRED, TEXT_TOO_LONG, "course title");
    }

    private static String requireModuleTitle(String title) {
        return ServiceSupport.requireText(title, MAX_TITLE, MODULE_TITLE_REQUIRED, TEXT_TOO_LONG, "module title");
    }

    private static String optionalText(String value, int maxLength, String what) {
        String text = ServiceSupport.clean(value);
        if (text.length() > maxLength) {
            throw new InvalidInputException(TEXT_TOO_LONG, what + " longer than " + maxLength + " characters");
        }
        return text;
    }

    private static int requireHue(int hue) {
        if (hue < 0 || hue > 359) {
            throw new InvalidInputException(HUE_INVALID, "Hue out of range: " + hue);
        }
        return hue;
    }

    /**
     * The offered hue farthest (around the colour wheel) from every hue already in use, so courses side by
     * side look different; the earliest offered hue wins a tie.
     */
    static int pickHue(Collection<Integer> used) {
        int best = COVER_HUES[0];
        int bestDistance = -1;
        for (int hue : COVER_HUES) {
            int distance = 360;
            for (int other : used) {
                int d = Math.abs(hue - other) % 360;
                distance = Math.min(distance, Math.min(d, 360 - d));
            }
            if (distance > bestDistance) {
                best = hue;
                bestDistance = distance;
            }
        }
        return best;
    }

    private void changed(String courseId) {
        events.publish(new CourseChanged(courseId, ChangeType.UPDATED));
    }

    /** A change that also changes what is due or studied (enrolment, archive). */
    private void changedWithProgress(String courseId) {
        changed(courseId);
        events.publish(new ProgressChanged(courseId));
    }
}
