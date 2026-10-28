package edu.learnsphere.app.dev;

import edu.learnsphere.ai.fake.FakeModuleSuggester;
import edu.learnsphere.core.error.ConflictException;
import edu.learnsphere.core.error.InvalidInputException;
import edu.learnsphere.core.error.UserMessage;
import edu.learnsphere.core.events.ChangeType;
import edu.learnsphere.core.events.CourseChanged;
import edu.learnsphere.core.events.ItemsChanged;
import edu.learnsphere.core.model.Course;
import edu.learnsphere.core.model.CourseModule;
import edu.learnsphere.core.model.CourseOrigin;
import edu.learnsphere.core.model.CourseStatus;
import edu.learnsphere.core.model.CourseVersion;
import edu.learnsphere.core.model.Document;
import edu.learnsphere.core.model.DocumentStatus;
import edu.learnsphere.core.model.ItemStatus;
import edu.learnsphere.core.model.LearningItem;
import edu.learnsphere.core.model.Visibility;
import edu.learnsphere.core.service.CourseDetail;
import edu.learnsphere.core.service.CourseDraft;
import edu.learnsphere.core.service.CourseFilter;
import edu.learnsphere.core.service.CourseScope;
import edu.learnsphere.core.service.CourseService;
import edu.learnsphere.core.service.CourseSummary;
import edu.learnsphere.core.service.CourseUpdate;
import edu.learnsphere.core.service.ModulePlan;
import edu.learnsphere.core.service.ModuleSummary;
import edu.learnsphere.core.spi.ModuleSuggester;
import edu.learnsphere.core.util.CancellationToken;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** {@link CourseService} over the {@link DemoStore}. */
final class DemoCourses implements CourseService {

    private static final UserMessage TITLE_NEEDED = UserMessage.of("Give the course a title.");
    private static final UserMessage MODULE_TITLE_NEEDED = UserMessage.of("Give the module a title.");

    private static final UserMessage HAS_MODULES = UserMessage.of("This course already has modules.");
    private static final UserMessage NO_READY_PDF = UserMessage.of("Add a PDF and wait until it's ready, then try again.");
    private static final UserMessage PDFS_CHANGED = UserMessage.of("The course's PDFs changed. Suggest modules again.");

    private final DemoStore store;
    private final DemoProgress progress;
    private final ModuleSuggester suggester = new FakeModuleSuggester();

    DemoCourses(DemoStore store, DemoProgress progress) {
        this.store = store;
        this.progress = progress;
    }

    @Override
    public List<CourseSummary> list(CourseFilter filter) {
        String search = filter.search() == null ? "" : filter.search().strip().toLowerCase(Locale.ROOT);
        synchronized (store) {
            return store.courses.values().stream()
                    .filter(c -> filter.includeArchived() || c.status() != CourseStatus.ARCHIVED)
                    .filter(c -> switch (filter.scope()) {
                        case STUDYING -> store.enrolled.contains(c.id());
                        case CREATING -> c.origin() == CourseOrigin.LOCAL;
                        case ALL -> true;
                    })
                    .filter(c -> search.isEmpty() || c.title().toLowerCase(Locale.ROOT).contains(search)
                            || c.authorName().toLowerCase(Locale.ROOT).contains(search))
                    .sorted(Comparator.comparing((Course c) -> c.lastOpenedAt() == null ? 0L : c.lastOpenedAt()).reversed())
                    .map(c -> store.summary(c.id()))
                    .toList();
        }
    }

    @Override
    public CourseSummary summary(String courseId) {
        synchronized (store) {
            return store.summary(courseId);
        }
    }

    @Override
    public CourseDetail get(String courseId) {
        synchronized (store) {
            Course course = store.course(courseId);
            List<CourseVersion> versions = store.versions.getOrDefault(courseId, List.of());
            List<ModuleSummary> modules = new ArrayList<>();
            long now = store.now();
            for (CourseModule module : store.modulesOf(courseId)) {
                List<LearningItem> items = store.itemsOf(courseId).stream()
                        .filter(i -> module.id().equals(i.moduleId())).toList();
                List<LearningItem> approved = items.stream().filter(i -> i.status() == ItemStatus.APPROVED).toList();
                int drafts = (int) items.stream().filter(i -> i.status() == ItemStatus.DRAFT).count();
                int docs = (int) store.documentsOf(courseId).stream().filter(d -> module.id().equals(d.moduleId())).count();
                int due = (int) approved.stream().filter(i -> store.due(i.id(), now)).count();
                double completion = approved.isEmpty() ? 0
                        : approved.stream().filter(i -> store.started(i.id())).count() / (double) approved.size();
                modules.add(new ModuleSummary(module, approved.size(), drafts, docs, due, completion));
            }
            return new CourseDetail(course, store.summary(courseId), versions.isEmpty() ? null : versions.getLast(),
                    modules, progress.mastery(courseId));
        }
    }

    @Override
    public Course create(CourseDraft draft) {
        if (draft.title() == null || draft.title().isBlank()) {
            throw new InvalidInputException(TITLE_NEEDED, "blank title");
        }
        Course course;
        synchronized (store) {
            long now = store.now();
            String id = DemoStore.newId();
            int hue = draft.coverHue() != null ? draft.coverHue() : Math.floorMod(id.hashCode(), 360);
            String author = draft.authorName() == null || draft.authorName().isBlank() ? store.profileName : draft.authorName();
            course = new Course(id, draft.title().strip(), draft.subtitle(), draft.description(), author, CourseOrigin.LOCAL,
                    Visibility.PRIVATE, CourseStatus.DRAFT, hue, 0, null, "pkg-" + id.substring(0, 8), now, now, now);
            store.courses.put(id, course);
        }
        changed(course.id(), ChangeType.CREATED);
        return course;
    }

    @Override
    public Course update(String courseId, CourseUpdate update) {
        if (update.title() != null && update.title().isBlank()) {
            throw new InvalidInputException(TITLE_NEEDED, "blank title");
        }
        Course course = replace(courseId, c -> new Course(c.id(), pick(update.title(), c.title()),
                pick(update.subtitle(), c.subtitle()), pick(update.description(), c.description()),
                pick(update.authorName(), c.authorName()), c.origin(), c.visibility(), c.status(), c.coverHue(),
                c.versionNumber(), c.publishedAt(), c.packageId(), c.lastOpenedAt(), c.createdAt(), store.now()));
        changed(courseId, ChangeType.UPDATED);
        return course;
    }

    private static String pick(String value, String fallback) {
        return value == null ? fallback : value.strip();
    }

    @Override
    public void setCoverHue(String courseId, int hue) {
        replace(courseId, c -> new Course(c.id(), c.title(), c.subtitle(), c.description(), c.authorName(), c.origin(),
                c.visibility(), c.status(), Math.floorMod(hue, 360), c.versionNumber(), c.publishedAt(), c.packageId(),
                c.lastOpenedAt(), c.createdAt(), store.now()));
        changed(courseId, ChangeType.UPDATED);
    }

    @Override
    public void setVisibility(String courseId, Visibility visibility) {
        replace(courseId, c -> new Course(c.id(), c.title(), c.subtitle(), c.description(), c.authorName(), c.origin(),
                visibility, c.status(), c.coverHue(), c.versionNumber(), c.publishedAt(), c.packageId(),
                c.lastOpenedAt(), c.createdAt(), store.now()));
        changed(courseId, ChangeType.UPDATED);
    }

    @Override
    public void archive(String courseId) {
        setStatus(courseId, CourseStatus.ARCHIVED);
    }

    @Override
    public void unarchive(String courseId) {
        boolean published;
        synchronized (store) {
            published = store.course(courseId).versionNumber() > 0;
        }
        setStatus(courseId, published ? CourseStatus.PUBLISHED : CourseStatus.DRAFT);
    }

    private void setStatus(String courseId, CourseStatus status) {
        replace(courseId, c -> new Course(c.id(), c.title(), c.subtitle(), c.description(), c.authorName(), c.origin(),
                c.visibility(), status, c.coverHue(), c.versionNumber(), c.publishedAt(), c.packageId(),
                c.lastOpenedAt(), c.createdAt(), store.now()));
        changed(courseId, ChangeType.UPDATED);
    }

    @Override
    public void delete(String courseId) {
        synchronized (store) {
            store.course(courseId);
            store.courses.remove(courseId);
            store.enrolled.remove(courseId);
            store.modules.values().removeIf(m -> m.courseId().equals(courseId));
            store.documents.values().removeIf(d -> d.courseId().equals(courseId));
            store.items.values().removeIf(i -> i.courseId().equals(courseId));
            store.concepts.values().removeIf(c -> c.courseId().equals(courseId));
            store.threads.values().removeIf(t -> t.courseId().equals(courseId));
        }
        changed(courseId, ChangeType.DELETED);
    }

    @Override
    public CourseVersion publish(String courseId, String notes) {
        CourseVersion version;
        synchronized (store) {
            Course c = store.course(courseId);
            long now = store.now();
            int number = c.versionNumber() + 1;
            version = new CourseVersion(DemoStore.newId(), courseId, number, notes, now, now);
            store.versions.computeIfAbsent(courseId, k -> new ArrayList<>()).add(version);
            store.courses.put(courseId, new Course(c.id(), c.title(), c.subtitle(), c.description(), c.authorName(),
                    c.origin(), c.visibility() == Visibility.PRIVATE ? Visibility.SHAREABLE : c.visibility(),
                    CourseStatus.PUBLISHED, c.coverHue(), number, now, c.packageId(), c.lastOpenedAt(), c.createdAt(), now));
        }
        changed(courseId, ChangeType.UPDATED);
        return version;
    }

    @Override
    public List<CourseVersion> versions(String courseId) {
        synchronized (store) {
            store.course(courseId);
            return List.copyOf(store.versions.getOrDefault(courseId, List.of()));
        }
    }

    @Override
    public List<CourseModule> modules(String courseId) {
        synchronized (store) {
            store.course(courseId);
            return store.modulesOf(courseId);
        }
    }

    @Override
    public CourseModule addModule(String courseId, String title) {
        requireTitle(title);
        CourseModule module;
        synchronized (store) {
            store.course(courseId);
            long now = store.now();
            module = new CourseModule(DemoStore.newId(), courseId, title.strip(), "", store.modulesOf(courseId).size(),
                    now, now);
            store.modules.put(module.id(), module);
        }
        changed(courseId, ChangeType.UPDATED);
        return module;
    }

    @Override
    public CourseModule renameModule(String moduleId, String title) {
        requireTitle(title);
        return replaceModule(moduleId, m -> new CourseModule(m.id(), m.courseId(), title.strip(), m.summary(),
                m.position(), m.createdAt(), store.now()));
    }

    @Override
    public CourseModule setModuleSummary(String moduleId, String summary) {
        return replaceModule(moduleId, m -> new CourseModule(m.id(), m.courseId(), m.title(), summary, m.position(),
                m.createdAt(), store.now()));
    }

    @Override
    public void reorderModules(String courseId, List<String> orderedModuleIds) {
        synchronized (store) {
            store.course(courseId);
            for (int i = 0; i < orderedModuleIds.size(); i++) {
                CourseModule m = store.module(orderedModuleIds.get(i));
                store.modules.put(m.id(), new CourseModule(m.id(), m.courseId(), m.title(), m.summary(), i,
                        m.createdAt(), store.now()));
            }
        }
        changed(courseId, ChangeType.UPDATED);
    }

    @Override
    public ModulePlan suggestModules(String courseId) {
        List<Section> outline;
        String title;
        synchronized (store) {
            title = store.course(courseId).title();
            if (!store.modulesOf(courseId).isEmpty()) {
                throw new ConflictException(HAS_MODULES, "course has modules");
            }
            outline = outline(courseId);
        }
        if (outline.isEmpty()) {
            throw new InvalidInputException(NO_READY_PDF, "no ready document");
        }
        List<ModulePlan.Unit> units = new ArrayList<>();
        for (ModuleSuggester.Suggestion s : suggester.suggest(title, outline.stream()
                .map(section -> new ModuleSuggester.Section(section.documentTitle(), section.heading())).toList(),
                CancellationToken.NONE)) {
            units.add(new ModulePlan.Unit(s.title(), s.summary(), units.isEmpty() ? 0 : s.firstSection()));
        }
        return new ModulePlan(outline.stream().map(Section::heading).toList(), units);
    }

    @Override
    public List<CourseModule> applyModules(String courseId, ModulePlan plan) {
        plan.units().forEach(unit -> requireTitle(unit.title()));
        List<CourseModule> created = new ArrayList<>();
        synchronized (store) {
            store.course(courseId);
            if (!store.modulesOf(courseId).isEmpty()) {
                throw new ConflictException(HAS_MODULES, "course has modules");
            }
            List<Section> outline = outline(courseId);
            if (!outline.stream().map(Section::heading).toList().equals(plan.sections())) {
                throw new ConflictException(PDFS_CHANGED, "outline changed");
            }
            long now = store.now();
            for (ModulePlan.Unit unit : plan.units()) {
                CourseModule module = new CourseModule(DemoStore.newId(), courseId, unit.title().strip(), unit.summary(),
                        created.size(), now, now);
                store.modules.put(module.id(), module);
                created.add(module);
            }
            Map<String, String> moduleOfPassage = new HashMap<>();
            Map<String, Set<String>> modulesOfDocument = new HashMap<>();
            for (int i = 0; i < outline.size(); i++) {
                String moduleId = created.get(plan.unitOf(i)).id();
                Section section = outline.get(i);
                section.passageIds().forEach(id -> moduleOfPassage.put(id, moduleId));
                modulesOfDocument.computeIfAbsent(section.documentId(), k -> new HashSet<>()).add(moduleId);
            }
            moduleOfPassage.forEach((id, moduleId) -> {
                DemoStore.Passage p = store.passages.get(id);
                store.passages.put(id, new DemoStore.Passage(p.id(), p.documentId(), p.courseId(), moduleId, p.page(),
                        p.section(), p.text()));
            });
            modulesOfDocument.forEach((id, modules) -> {
                Document d = store.documents.get(id);
                String moduleId = modules.size() == 1 ? modules.iterator().next() : null;
                store.documents.put(id, new Document(d.id(), d.courseId(), moduleId, d.title(), d.fileName(),
                        d.fileSha256(), d.byteSize(), d.pageCount(), d.status(), d.progress(), d.statusDetail(),
                        d.errorCode(), d.createdAt(), now));
            });
            store.items.replaceAll((id, i) -> i.courseId().equals(courseId) && moduleOfPassage.containsKey(i.sourceChunkId())
                    ? new LearningItem(i.id(), i.courseId(), moduleOfPassage.get(i.sourceChunkId()), i.type(), i.prompt(),
                            i.answer(), i.explanation(), i.distractors(), i.difficulty(), i.sourceChunkId(),
                            i.sourcePage(), i.origin(), i.status(), i.support(), i.supportNote(), i.createdAt(), now)
                    : i);
        }
        changed(courseId, ChangeType.UPDATED);
        store.events.publish(new ItemsChanged(courseId, null, Set.of(), ChangeType.UPDATED));
        return created;
    }

    /** One run of passages under a heading in a ready PDF, the way the course service outlines a course. */
    private record Section(String documentId, String documentTitle, String heading, List<String> passageIds) {
    }

    private List<Section> outline(String courseId) {
        List<Section> sections = new ArrayList<>();
        for (Document document : store.documentsOf(courseId)) {
            if (document.status() != DocumentStatus.READY) {
                continue;
            }
            for (DemoStore.Passage passage : store.passages.values()) {
                if (!passage.documentId().equals(document.id())) {
                    continue;
                }
                Section last = sections.isEmpty() ? null : sections.getLast();
                if (last != null && last.documentId().equals(document.id()) && last.heading().equals(passage.section())) {
                    last.passageIds().add(passage.id());
                } else {
                    sections.add(new Section(document.id(), document.title(), passage.section(),
                            new ArrayList<>(List.of(passage.id()))));
                }
            }
        }
        return sections;
    }

    @Override
    public void deleteModule(String moduleId) {
        String courseId;
        synchronized (store) {
            CourseModule module = store.module(moduleId);
            courseId = module.courseId();
            store.modules.remove(moduleId);
        }
        changed(courseId, ChangeType.UPDATED);
    }

    @Override
    public void enrol(String courseId) {
        synchronized (store) {
            store.course(courseId);
            store.enrolled.add(courseId);
        }
        changed(courseId, ChangeType.UPDATED);
    }

    @Override
    public void leave(String courseId) {
        synchronized (store) {
            store.course(courseId);
            store.enrolled.remove(courseId);
        }
        changed(courseId, ChangeType.UPDATED);
    }

    @Override
    public void markOpened(String courseId) {
        synchronized (store) {
            Course c = store.course(courseId);
            store.courses.put(courseId, new Course(c.id(), c.title(), c.subtitle(), c.description(), c.authorName(),
                    c.origin(), c.visibility(), c.status(), c.coverHue(), c.versionNumber(), c.publishedAt(),
                    c.packageId(), store.now(), c.createdAt(), c.updatedAt()));
        }
    }

    private static void requireTitle(String title) {
        if (title == null || title.isBlank()) {
            throw new InvalidInputException(MODULE_TITLE_NEEDED, "blank module title");
        }
    }

    private Course replace(String courseId, java.util.function.UnaryOperator<Course> change) {
        synchronized (store) {
            Course next = change.apply(store.course(courseId));
            store.courses.put(courseId, next);
            return next;
        }
    }

    private CourseModule replaceModule(String moduleId, java.util.function.UnaryOperator<CourseModule> change) {
        CourseModule next;
        synchronized (store) {
            next = change.apply(store.module(moduleId));
            store.modules.put(moduleId, next);
        }
        changed(next.courseId(), ChangeType.UPDATED);
        return next;
    }

    private void changed(String courseId, ChangeType type) {
        store.events.publish(new CourseChanged(courseId, type));
    }

    static CourseScope scopeOf(CourseFilter filter) {
        return filter.scope();
    }
}
