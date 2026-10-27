package edu.learnsphere.app.dev;

import edu.learnsphere.core.error.InvalidInputException;
import edu.learnsphere.core.error.NotFoundException;
import edu.learnsphere.core.error.UserMessage;
import edu.learnsphere.core.events.ChangeType;
import edu.learnsphere.core.events.ItemsChanged;
import edu.learnsphere.core.model.Concept;
import edu.learnsphere.core.model.CourseModule;
import edu.learnsphere.core.model.ItemOrigin;
import edu.learnsphere.core.model.ItemStatus;
import edu.learnsphere.core.model.ItemType;
import edu.learnsphere.core.model.JobKind;
import edu.learnsphere.core.model.LearningItem;
import edu.learnsphere.core.model.SupportStatus;
import edu.learnsphere.core.service.GenerationRequest;
import edu.learnsphere.core.service.GenerationService;
import edu.learnsphere.core.service.JobQueue;
import edu.learnsphere.core.service.JobSpec;
import edu.learnsphere.core.service.JobUpdate;
import edu.learnsphere.core.util.Json;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * {@link GenerationService} over the {@link DemoStore}. Concept extraction and question generation run as
 * jobs on the real job queue ({@link DemoJobs} handles them), writing draft questions from the module's
 * passages with fixed templates in place of the study assistant.
 */
final class DemoGeneration implements GenerationService {

    private static final UserMessage NAME_NEEDED = UserMessage.of("Give the concept a name.");

    /** Payload of a generation job. */
    record GeneratePayload(String courseId, String moduleId, List<String> types, int count) {
    }

    private final DemoStore store;
    private final JobQueue jobs;

    DemoGeneration(DemoStore store, JobQueue jobs) {
        this.store = store;
        this.jobs = jobs;
    }

    @Override
    public List<Concept> concepts(String courseId, String moduleId) {
        synchronized (store) {
            return store.conceptsOf(courseId).stream()
                    .filter(c -> moduleId == null || moduleId.equals(c.moduleId())).toList();
        }
    }

    @Override
    public Set<String> extractedConceptIds(String courseId) {
        synchronized (store) {
            Set<String> ids = new LinkedHashSet<>();
            for (Concept concept : store.conceptsOf(courseId)) {
                if (store.extractedConcepts.contains(concept.id())) {
                    ids.add(concept.id());
                }
            }
            return Set.copyOf(ids);
        }
    }

    @Override
    public JobUpdate extractConcepts(String moduleId) {
        CourseModule module;
        synchronized (store) {
            module = store.module(moduleId);
        }
        return jobs.enqueue(new JobSpec(JobKind.EXTRACT_CONCEPTS, moduleId, Json.write(module.courseId()), 5, true));
    }

    @Override
    public Concept addConcept(String courseId, String moduleId, String name, String description) {
        requireName(name);
        synchronized (store) {
            store.course(courseId);
            long now = store.now();
            Concept concept = new Concept(DemoStore.newId(), courseId, moduleId, name.strip(), description, now, now);
            store.concepts.put(concept.id(), concept);
            return concept;
        }
    }

    @Override
    public Concept updateConcept(String conceptId, String name, String description) {
        requireName(name);
        synchronized (store) {
            Concept c = concept(conceptId);
            Concept next = new Concept(c.id(), c.courseId(), c.moduleId(), name.strip(), description, c.createdAt(),
                    store.now());
            store.concepts.put(conceptId, next);
            store.extractedConcepts.remove(conceptId);
            return next;
        }
    }

    @Override
    public void deleteConcept(String conceptId) {
        synchronized (store) {
            concept(conceptId);
            store.concepts.remove(conceptId);
            store.extractedConcepts.remove(conceptId);
            store.itemConcepts.values().forEach(set -> set.remove(conceptId));
        }
    }

    private Concept concept(String conceptId) {
        Concept concept = store.concepts.get(conceptId);
        if (concept == null) {
            throw new NotFoundException(UserMessage.NOT_AVAILABLE, "No concept " + conceptId);
        }
        return concept;
    }

    private static void requireName(String name) {
        if (name == null || name.isBlank()) {
            throw new InvalidInputException(NAME_NEEDED, "blank concept name");
        }
    }

    @Override
    public JobUpdate generate(GenerationRequest request) {
        synchronized (store) {
            store.course(request.courseId());
        }
        List<String> types = request.types().stream().map(Enum::name).sorted().toList();
        GeneratePayload payload = new GeneratePayload(request.courseId(), request.moduleId(), types, request.count());
        String subject = request.moduleId() != null ? request.moduleId() : request.courseId();
        return jobs.enqueue(new JobSpec(JobKind.GENERATE_ITEMS, subject, Json.write(payload), 5, false));
    }

    @Override
    public JobUpdate regenerate(String itemId) {
        synchronized (store) {
            store.item(itemId);
        }
        return jobs.enqueue(new JobSpec(JobKind.REGENERATE_ITEM, itemId, Json.write(itemId), 5, true));
    }

    /** Writes up to {@code count} draft questions from the passages of a module (or course). Holds the store. */
    List<LearningItem> draftFrom(GeneratePayload payload) {
        List<DemoStore.Passage> passages = store.passages.values().stream()
                .filter(p -> p.courseId().equals(payload.courseId()))
                .filter(p -> payload.moduleId() == null || payload.moduleId().equals(p.moduleId())).toList();
        List<ItemType> types = payload.types().isEmpty() ? List.of(ItemType.MCQ, ItemType.TRUE_FALSE, ItemType.FLASHCARD)
                : payload.types().stream().map(ItemType::valueOf).toList();
        List<LearningItem> created = new ArrayList<>();
        for (int i = 0; i < payload.count() && !passages.isEmpty(); i++) {
            DemoStore.Passage passage = passages.get(i % passages.size());
            ItemType type = types.get(i % types.size());
            LearningItem item = template(passage, type);
            store.items.put(item.id(), item);
            Set<String> concepts = new LinkedHashSet<>();
            store.conceptsOf(payload.courseId()).stream()
                    .filter(c -> passage.moduleId().equals(c.moduleId())).findFirst()
                    .ifPresent(c -> concepts.add(c.id()));
            store.itemConcepts.put(item.id(), concepts);
            created.add(item);
        }
        return created;
    }

    /** A draft question about {@code passage} in the shape of {@code type}. */
    LearningItem template(DemoStore.Passage passage, ItemType type) {
        String first = firstSentence(passage.text());
        long now = store.now();
        String prompt;
        String answer;
        List<String> distractors = List.of();
        switch (type) {
            case MCQ -> {
                prompt = "Which statement about " + passage.section().toLowerCase(java.util.Locale.ROOT)
                        + " matches the material?";
                answer = first;
                distractors = List.of("It only applies to systems with a single user.",
                        "It was replaced by a newer approach and is no longer used.",
                        "It has no effect on how the system behaves.");
            }
            case TRUE_FALSE -> {
                prompt = first;
                answer = "True";
            }
            case CLOZE -> {
                String[] words = first.split(" ");
                int pick = Math.min(words.length - 1, Math.max(2, words.length / 2));
                answer = words[pick].replaceAll("[^A-Za-z0-9–-]", "");
                words[pick] = ItemType.CLOZE_BLANK;
                prompt = String.join(" ", words);
            }
            case SHORT_ANSWER -> {
                prompt = "Explain " + passage.section().toLowerCase(java.util.Locale.ROOT) + " in your own words.";
                answer = first;
            }
            default -> {
                prompt = passage.section();
                answer = first;
            }
        }
        return new LearningItem(DemoStore.newId(), passage.courseId(), passage.moduleId(), type, prompt, answer,
                "From the section \"" + passage.section() + "\".", distractors,
                edu.learnsphere.core.model.Difficulty.MEDIUM, passage.id(), passage.page(), ItemOrigin.AI,
                ItemStatus.DRAFT, SupportStatus.SUPPORTED, null, now, now);
    }

    static String firstSentence(String text) {
        int end = text.indexOf(". ");
        return end < 0 ? text : text.substring(0, end + 1);
    }

    void published(String courseId, String moduleId, Set<String> ids) {
        store.events.publish(new ItemsChanged(courseId, moduleId, ids, ChangeType.CREATED));
    }
}
