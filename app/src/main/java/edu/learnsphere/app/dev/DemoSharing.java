package edu.learnsphere.app.dev;

import edu.learnsphere.core.error.InvalidInputException;
import edu.learnsphere.core.error.NotFoundException;
import edu.learnsphere.core.error.UserMessage;
import edu.learnsphere.core.events.ChangeType;
import edu.learnsphere.core.events.CourseChanged;
import edu.learnsphere.core.model.ClassInsight;
import edu.learnsphere.core.model.Course;
import edu.learnsphere.core.model.CourseModule;
import edu.learnsphere.core.model.CourseOrigin;
import edu.learnsphere.core.model.CourseStatus;
import edu.learnsphere.core.model.Difficulty;
import edu.learnsphere.core.model.Flag;
import edu.learnsphere.core.model.FlagReason;
import edu.learnsphere.core.model.FlagSource;
import edu.learnsphere.core.model.ItemOrigin;
import edu.learnsphere.core.model.ItemStatus;
import edu.learnsphere.core.model.ItemType;
import edu.learnsphere.core.model.JobKind;
import edu.learnsphere.core.model.LearningItem;
import edu.learnsphere.core.model.SupportStatus;
import edu.learnsphere.core.model.Visibility;
import edu.learnsphere.core.service.ClassInsightSummary;
import edu.learnsphere.core.service.ConceptInsight;
import edu.learnsphere.core.service.ConceptMastery;
import edu.learnsphere.core.service.ExportOptions;
import edu.learnsphere.core.service.ImportOutcome;
import edu.learnsphere.core.service.ImportResult;
import edu.learnsphere.core.service.ItemInsight;
import edu.learnsphere.core.service.JobQueue;
import edu.learnsphere.core.service.JobSpec;
import edu.learnsphere.core.service.JobUpdate;
import edu.learnsphere.core.service.LearnerInsight;
import edu.learnsphere.core.service.PackageInfo;
import edu.learnsphere.core.service.SharingService;
import edu.learnsphere.core.util.Json;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/**
 * {@link SharingService} over the {@link DemoStore}. Course packages are small zip files with a manifest and
 * the course's modules and approved questions; importing one runs as an {@code IMPORT_PACKAGE} job. Class
 * insights are worked out from the demo profile's own attempts, standing in for imported learners.
 */
final class DemoSharing implements SharingService {

    private static final UserMessage NOT_A_PACKAGE = UserMessage.of("This file isn't a LearnSphere course.");
    private static final UserMessage CANNOT_WRITE = UserMessage.of("The file couldn't be saved there. Choose another folder.");

    record Manifest(String packageId, int formatVersion, String title, String subtitle, String authorName, int coverHue,
                    int versionNumber, long createdAt, int moduleCount, int itemCount, int documentCount,
                    boolean protectedByCode) {
    }

    record PackedItem(String module, String type, String prompt, String answer, String explanation,
                      List<String> distractors) {
    }

    record Packed(String description, List<String> modules, List<PackedItem> items) {
    }

    record ImportPayload(String file, boolean coded) {
    }

    private final DemoStore store;
    private final JobQueue jobs;
    private final DemoProgress progress;

    DemoSharing(DemoStore store, JobQueue jobs, DemoProgress progress) {
        this.store = store;
        this.jobs = jobs;
        this.progress = progress;
    }

    @Override
    public PackageInfo exportCourse(String courseId, Path target, ExportOptions options) {
        Manifest manifest;
        Packed packed;
        synchronized (store) {
            Course course = store.course(courseId);
            List<CourseModule> modules = store.modulesOf(courseId);
            List<PackedItem> items = store.itemsOf(courseId).stream().filter(i -> i.status() == ItemStatus.APPROVED)
                    .map(i -> new PackedItem(store.moduleTitle(i.moduleId()), i.type().name(), i.prompt(), i.answer(),
                            i.explanation(), i.distractors())).toList();
            boolean coded = options.protectionCode() != null && options.protectionCode().length > 0;
            manifest = new Manifest(course.packageId(), 1, course.title(), course.subtitle(), course.authorName(),
                    course.coverHue(), Math.max(1, course.versionNumber()), store.now(), modules.size(), items.size(),
                    options.includeDocuments() ? store.documentsOf(courseId).size() : 0, coded);
            packed = new Packed(course.description(), modules.stream().map(CourseModule::title).toList(), items);
            if (options.protectionCode() != null) {
                Arrays.fill(options.protectionCode(), '\0');
            }
        }
        try (OutputStream file = Files.newOutputStream(target); ZipOutputStream zip = new ZipOutputStream(file)) {
            zip.putNextEntry(new ZipEntry("manifest.json"));
            zip.write(Json.writePretty(manifest).getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
            zip.putNextEntry(new ZipEntry("course.json"));
            zip.write(Json.writePretty(packed).getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        } catch (IOException e) {
            throw new InvalidInputException(CANNOT_WRITE, "Cannot write " + target);
        }
        return info(manifest, target);
    }

    @Override
    public PackageInfo inspect(Path packageFile) {
        return info(read(packageFile, "manifest.json", Manifest.class), packageFile);
    }

    private PackageInfo info(Manifest m, Path file) {
        long size;
        try {
            size = Files.size(file);
        } catch (IOException e) {
            size = 0;
        }
        synchronized (store) {
            Course existing = store.courses.values().stream().filter(c -> c.packageId().equals(m.packageId()))
                    .findFirst().orElse(null);
            return new PackageInfo(m.packageId(), m.formatVersion(), m.title(), m.subtitle(), m.authorName(), m.coverHue(),
                    m.versionNumber(), m.createdAt(), m.moduleCount(), m.itemCount(), m.documentCount(), size,
                    m.protectedByCode(), existing == null ? null : existing.id(),
                    existing == null ? null : existing.versionNumber(),
                    existing != null && existing.origin() == CourseOrigin.LOCAL);
        }
    }

    static <T> T read(Path file, String entry, Class<T> type) {
        try (InputStream in = Files.newInputStream(file); ZipInputStream zip = new ZipInputStream(in)) {
            for (ZipEntry e = zip.getNextEntry(); e != null; e = zip.getNextEntry()) {
                if (e.getName().equals(entry)) {
                    return Json.read(new String(zip.readAllBytes(), StandardCharsets.UTF_8), type);
                }
            }
        } catch (IOException | RuntimeException e) {
            throw new InvalidInputException(NOT_A_PACKAGE, "Cannot read " + file + ": " + e);
        }
        throw new InvalidInputException(NOT_A_PACKAGE, "No " + entry + " in " + file);
    }

    @Override
    public JobUpdate importCourse(Path packageFile, char[] code) {
        Manifest manifest = read(packageFile, "manifest.json", Manifest.class);
        if (manifest.protectedByCode() && (code == null || code.length == 0)) {
            throw new InvalidInputException(UserMessage.WRONG_COURSE_CODE, "missing code");
        }
        if (code != null) {
            Arrays.fill(code, '\0');
        }
        return jobs.enqueue(new JobSpec(JobKind.IMPORT_PACKAGE, manifest.packageId(),
                Json.write(new ImportPayload(packageFile.toAbsolutePath().toString(), manifest.protectedByCode())), 5, true));
    }

    /** Installs a package (the import job's work). Returns what changed. */
    ImportResult install(Path file) {
        Manifest m = read(file, "manifest.json", Manifest.class);
        Packed packed = read(file, "course.json", Packed.class);
        ImportResult result;
        synchronized (store) {
            long now = store.now();
            Course existing = store.courses.values().stream().filter(c -> c.packageId().equals(m.packageId()))
                    .findFirst().orElse(null);
            if (existing != null && existing.versionNumber() >= m.versionNumber()) {
                return new ImportResult(ImportOutcome.ALREADY_CURRENT, existing.id(), existing.title(),
                        existing.versionNumber(), 0, 0, 0);
            }
            String courseId = existing == null ? DemoStore.newId() : existing.id();
            store.courses.put(courseId, new Course(courseId, m.title(), m.subtitle(), packed.description(), m.authorName(),
                    CourseOrigin.IMPORTED, Visibility.SHAREABLE, CourseStatus.PUBLISHED, m.coverHue(), m.versionNumber(),
                    now, m.packageId(), now, existing == null ? now : existing.createdAt(), now));
            store.enrolled.add(courseId);
            List<String> moduleIds = new ArrayList<>();
            if (existing == null) {
                for (int i = 0; i < packed.modules().size(); i++) {
                    CourseModule module = new CourseModule(DemoStore.newId(), courseId, packed.modules().get(i), "", i,
                            now, now);
                    store.modules.put(module.id(), module);
                    moduleIds.add(module.id());
                }
            } else {
                store.modulesOf(courseId).forEach(module -> moduleIds.add(module.id()));
            }
            int added = 0;
            for (PackedItem p : packed.items()) {
                int index = Math.max(0, packed.modules().indexOf(p.module()));
                String moduleId = moduleIds.isEmpty() ? null : moduleIds.get(Math.min(index, moduleIds.size() - 1));
                boolean known = store.itemsOf(courseId).stream().anyMatch(i -> i.prompt().equals(p.prompt()));
                if (!known) {
                    LearningItem item = new LearningItem(DemoStore.newId(), courseId, moduleId, ItemType.valueOf(p.type()),
                            p.prompt(), p.answer(), p.explanation(), p.distractors(), Difficulty.MEDIUM, null, null,
                            ItemOrigin.MANUAL, ItemStatus.APPROVED, SupportStatus.UNCHECKED, null, now, now);
                    store.items.put(item.id(), item);
                    added++;
                }
            }
            result = new ImportResult(existing == null ? ImportOutcome.CREATED : ImportOutcome.UPDATED, courseId, m.title(),
                    m.versionNumber(), added, 0, 0);
        }
        store.events.publish(new CourseChanged(result.courseId(),
                result.outcome() == ImportOutcome.CREATED ? ChangeType.CREATED : ChangeType.UPDATED));
        return result;
    }

    @Override
    public Optional<ImportResult> importResult(String jobId) {
        return jobs.result(jobId, ImportResult.class);
    }

    @Override
    public void exportProgress(String courseId, String learnerLabel, Path target) {
        List<ConceptMastery> mastery;
        synchronized (store) {
            store.course(courseId);
            mastery = progress.mastery(courseId);
        }
        write(target, Json.writePretty(new ProgressFile(courseId, learnerLabel, mastery)));
    }

    record ProgressFile(String courseId, String learner, List<ConceptMastery> concepts) {
    }

    @Override
    public ClassInsightSummary importClassProgress(String courseId, List<Path> files) {
        synchronized (store) {
            store.course(courseId);
            long now = store.now();
            for (Path file : files) {
                String label = file.getFileName().toString().replaceFirst("\\.[^.]+$", "");
                ClassInsight insight = new ClassInsight(DemoStore.newId(), courseId, label, "{}", now, now);
                store.insights.put(insight.id(), insight);
            }
            return insights(courseId);
        }
    }

    @Override
    public ClassInsightSummary classInsights(String courseId) {
        synchronized (store) {
            store.course(courseId);
            return insights(courseId);
        }
    }

    private ClassInsightSummary insights(String courseId) {
        List<ClassInsight> learners = store.insights.values().stream().filter(i -> i.courseId().equals(courseId)).toList();
        List<LearnerInsight> people = new ArrayList<>();
        for (int i = 0; i < learners.size(); i++) {
            ClassInsight insight = learners.get(i);
            people.add(new LearnerInsight(insight.id(), insight.learnerLabel(), insight.importedAt(), 40 + i * 7,
                    0.55 + (i % 4) * 0.1));
        }
        List<LearningItem> approved = store.itemsOf(courseId).stream().filter(i -> i.status() == ItemStatus.APPROVED)
                .toList();
        if (approved.stream().anyMatch(i -> store.classResults.containsKey(i.id()))) {
            return classSummary(courseId, people, approved);
        }
        List<ConceptInsight> concepts = progress.mastery(courseId).stream()
                .map(c -> new ConceptInsight(c.conceptId(), c.name(), c.accuracy(), learners.size(),
                        c.mastery() != null && c.mastery() < ConceptMastery.WEAK_THRESHOLD ? Math.max(1, learners.size() / 2) : 0))
                .toList();
        List<ItemInsight> items = approved.stream()
                .map(i -> {
                    var stats = progress.stats(i);
                    return new ItemInsight(i.id(), i.prompt(), stats.attempts(), stats.accuracy(), learners.size());
                }).toList();
        return new ClassInsightSummary(courseId, people, concepts, items);
    }

    /** The summary from the class's own results ({@link DemoClassProgress}), concepts weakest first. */
    private ClassInsightSummary classSummary(String courseId, List<LearnerInsight> people, List<LearningItem> approved) {
        List<ItemInsight> items = new ArrayList<>();
        for (LearningItem item : approved) {
            DemoClassProgress.ItemResult result = store.classResults.get(item.id());
            if (result != null) {
                items.add(new ItemInsight(item.id(), item.prompt(), result.attempts(), result.accuracy(), result.learners()));
            }
        }
        List<ConceptInsight> concepts = new ArrayList<>();
        for (ConceptMastery concept : progress.mastery(courseId)) {
            int attempts = 0;
            double correct = 0;
            int learners = 0;
            for (LearningItem item : approved) {
                DemoClassProgress.ItemResult result = store.classResults.get(item.id());
                if (result != null && store.itemConcepts.getOrDefault(item.id(), java.util.Set.of()).contains(concept.conceptId())) {
                    attempts += result.attempts();
                    correct += result.attempts() * result.accuracy();
                    learners = Math.max(learners, result.learners());
                }
            }
            Double accuracy = attempts == 0 ? null : correct / attempts;
            int struggling = accuracy == null ? 0
                    : (int) Math.min(learners, Math.round(learners * Math.max(0, 0.8 - accuracy) * 2));
            concepts.add(new ConceptInsight(concept.conceptId(), concept.name(), accuracy, learners, struggling));
        }
        concepts.sort(java.util.Comparator.comparing(ConceptInsight::accuracy,
                java.util.Comparator.nullsLast(java.util.Comparator.naturalOrder())));
        return new ClassInsightSummary(courseId, people, concepts, items);
    }

    @Override
    public void removeClassInsight(String insightId) {
        synchronized (store) {
            if (store.insights.remove(insightId) == null) {
                throw new NotFoundException(UserMessage.NOT_AVAILABLE, "No insight " + insightId);
            }
        }
    }

    record FeedbackFile(String packageId, List<FeedbackEntry> flags) {
    }

    record FeedbackEntry(String prompt, String reason, String note) {
    }

    @Override
    public void exportFeedback(String courseId, Path target) {
        FeedbackFile content;
        synchronized (store) {
            Course course = store.course(courseId);
            List<FeedbackEntry> entries = store.flags.values().stream()
                    .filter(f -> store.items.containsKey(f.itemId()) && store.items.get(f.itemId()).courseId().equals(courseId))
                    .map(f -> new FeedbackEntry(store.items.get(f.itemId()).prompt(), f.reason().name(), f.note()))
                    .toList();
            content = new FeedbackFile(course.packageId(), entries);
        }
        write(target, Json.writePretty(content));
    }

    @Override
    public ImportResult importFeedback(Path file) {
        FeedbackFile content;
        try {
            content = Json.read(Files.readString(file), FeedbackFile.class);
        } catch (IOException | RuntimeException e) {
            throw new InvalidInputException(UserMessage.of("This file isn't course feedback."), "Cannot read " + file);
        }
        synchronized (store) {
            Course course = store.courses.values().stream().filter(c -> c.packageId().equals(content.packageId()))
                    .findFirst().orElseThrow(() -> new NotFoundException(UserMessage.COURSE_NOT_FOUND, "No package"));
            int added = 0;
            long now = store.now();
            for (FeedbackEntry entry : content.flags()) {
                Optional<LearningItem> item = store.itemsOf(course.id()).stream()
                        .filter(i -> i.prompt().equals(entry.prompt())).findFirst();
                if (item.isPresent()) {
                    Flag flag = new Flag(DemoStore.newId(), item.get().id(), FlagReason.valueOf(entry.reason()),
                            entry.note(), FlagSource.LEARNER_FEEDBACK, null, now, now);
                    store.flags.put(flag.id(), flag);
                    added++;
                }
            }
            return new ImportResult(ImportOutcome.UPDATED, course.id(), course.title(), course.versionNumber(), added, 0, 0);
        }
    }

    private static void write(Path target, String json) {
        try {
            Files.writeString(target, json, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new InvalidInputException(CANNOT_WRITE, "Cannot write " + target);
        }
    }
}
