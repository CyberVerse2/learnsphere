package edu.learnsphere.app.dev;

import edu.learnsphere.core.events.ChangeType;
import edu.learnsphere.core.events.CourseChanged;
import edu.learnsphere.core.events.ItemsChanged;
import edu.learnsphere.core.model.Concept;
import edu.learnsphere.core.model.CourseModule;
import edu.learnsphere.core.model.Document;
import edu.learnsphere.core.model.DocumentStatus;
import edu.learnsphere.core.model.JobKind;
import edu.learnsphere.core.model.LearningItem;
import edu.learnsphere.core.service.JobContext;
import edu.learnsphere.core.service.JobQueue;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * The demo's job handlers on the real job queue: importing PDFs step by step, extracting concepts,
 * writing draft questions, rewriting one, and installing a course package. Each reports progress the way
 * the real pipelines do, at a pace a person can watch.
 */
final class DemoJobs {

    private final DemoStore store;
    private final DemoDocuments documents;
    private final DemoGeneration generation;
    private final DemoSharing sharing;
    private final Duration step;

    DemoJobs(DemoStore store, DemoDocuments documents, DemoGeneration generation, DemoSharing sharing, Duration step) {
        this.store = store;
        this.documents = documents;
        this.generation = generation;
        this.sharing = sharing;
        this.step = step;
    }

    void register(JobQueue jobs) {
        jobs.register(JobKind.INGEST_DOCUMENT, this::ingest);
        jobs.register(JobKind.EXTRACT_CONCEPTS, this::extractConcepts);
        jobs.register(JobKind.GENERATE_ITEMS, this::generate);
        jobs.register(JobKind.REGENERATE_ITEM, this::regenerate);
        jobs.register(JobKind.IMPORT_PACKAGE, this::importPackage);
    }

    private void ingest(JobContext context) throws InterruptedException {
        String documentId = context.job().subjectId();
        set(documentId, DocumentStatus.CHECKING, 0);
        context.progress(0.05, "Checking your PDF…");
        pause(4);
        int pages;
        synchronized (store) {
            pages = Math.max(1, store.document(documentId).pageCount());
        }
        int stride = Math.max(1, pages / 8);
        for (int page = Math.min(stride, pages); ; page = Math.min(pages, page + stride)) {
            if (context.isCancelled()) {
                return;
            }
            set(documentId, DocumentStatus.EXTRACTING, page / (double) pages);
            context.progress(0.1 + 0.7 * page / pages, "Reading your PDF… page " + page + " of " + pages);
            pause(2);
            if (page >= pages) {
                break;
            }
        }
        set(documentId, DocumentStatus.INDEXING, 0.5);
        context.progress(0.9, "Preparing for questions…");
        pause(5);
        set(documentId, DocumentStatus.READY, 1);
        context.progress(1, "Ready");
    }

    private void set(String documentId, DocumentStatus status, double progress) {
        Document next;
        synchronized (store) {
            Document d = store.documents.get(documentId);
            if (d == null) {
                return;
            }
            next = new Document(d.id(), d.courseId(), d.moduleId(), d.title(), d.fileName(), d.fileSha256(), d.byteSize(),
                    d.pageCount(), status, Math.clamp(progress, 0, 1), null, null, d.createdAt(), store.now());
            store.documents.put(documentId, next);
        }
        documents.publish(next, ChangeType.UPDATED);
    }

    private void extractConcepts(JobContext context) throws InterruptedException {
        String moduleId = context.job().subjectId();
        context.progress(0.2, "Finding the main ideas…");
        pause(6);
        String courseId;
        synchronized (store) {
            CourseModule module = store.module(moduleId);
            courseId = module.courseId();
            Set<String> known = store.conceptsOf(courseId).stream().map(c -> c.name().toLowerCase(java.util.Locale.ROOT))
                    .collect(Collectors.toSet());
            List<String> sections = store.passages.values().stream().filter(p -> moduleId.equals(p.moduleId()))
                    .map(DemoStore.Passage::section)
                    .filter(name -> !known.contains(name.toLowerCase(java.util.Locale.ROOT))).distinct().toList();
            long now = store.now();
            for (String name : sections) {
                Concept concept = new Concept(DemoStore.newId(), courseId, moduleId, name,
                        "A main idea of " + module.title() + ".", now, now);
                store.concepts.put(concept.id(), concept);
            }
        }
        context.progress(1, "Done");
        store.events.publish(new CourseChanged(courseId, ChangeType.UPDATED));
    }

    private void generate(JobContext context) throws InterruptedException {
        DemoGeneration.GeneratePayload payload = context.payload(DemoGeneration.GeneratePayload.class);
        for (int i = 1; i <= payload.count(); i++) {
            if (context.isCancelled()) {
                return;
            }
            context.progress(i / (double) (payload.count() + 1), "Writing question " + i + " of " + payload.count() + "…");
            pause(3);
        }
        List<LearningItem> created;
        synchronized (store) {
            created = generation.draftFrom(payload);
        }
        context.setResult(created.size());
        context.progress(1, "Done");
        generation.published(payload.courseId(), payload.moduleId(),
                created.stream().map(LearningItem::id).collect(Collectors.toSet()));
    }

    private void regenerate(JobContext context) throws InterruptedException {
        String itemId = context.job().subjectId();
        context.progress(0.3, "Rewriting the question…");
        pause(5);
        LearningItem next;
        synchronized (store) {
            LearningItem old = store.item(itemId);
            DemoStore.Passage passage = store.passage(old.sourceChunkId());
            if (passage == null) {
                return;
            }
            LearningItem fresh = generation.template(passage, old.type());
            next = new LearningItem(old.id(), old.courseId(), old.moduleId(), old.type(), fresh.prompt(), fresh.answer(),
                    fresh.explanation(), fresh.distractors(), old.difficulty(), old.sourceChunkId(), old.sourcePage(),
                    fresh.origin(), edu.learnsphere.core.model.ItemStatus.DRAFT, fresh.support(), null, old.createdAt(),
                    store.now());
            store.items.put(itemId, next);
        }
        context.progress(1, "Done");
        store.events.publish(new ItemsChanged(next.courseId(), next.moduleId(), Set.of(itemId), ChangeType.UPDATED));
    }

    private void importPackage(JobContext context) throws InterruptedException {
        DemoSharing.ImportPayload payload = context.payload(DemoSharing.ImportPayload.class);
        context.progress(0.2, "Opening the course…");
        pause(4);
        context.progress(0.6, "Adding modules and questions…");
        pause(4);
        context.setResult(sharing.install(Path.of(payload.file())));
        context.progress(1, "Done");
    }

    private void pause(int steps) throws InterruptedException {
        if (!step.isZero()) {
            Thread.sleep(step.multipliedBy(steps));
        }
    }
}
