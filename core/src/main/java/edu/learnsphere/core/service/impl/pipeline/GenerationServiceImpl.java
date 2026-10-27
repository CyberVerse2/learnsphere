package edu.learnsphere.core.service.impl.pipeline;

import edu.learnsphere.core.db.Repositories;
import edu.learnsphere.core.error.InvalidInputException;
import edu.learnsphere.core.error.LearnSphereException;
import edu.learnsphere.core.error.NotFoundException;
import edu.learnsphere.core.error.UnavailableException;
import edu.learnsphere.core.error.UserMessage;
import edu.learnsphere.core.events.ChangeType;
import edu.learnsphere.core.events.CourseChanged;
import edu.learnsphere.core.events.EventBus;
import edu.learnsphere.core.events.ItemsChanged;
import edu.learnsphere.core.model.Chunk;
import edu.learnsphere.core.model.Concept;
import edu.learnsphere.core.model.Course;
import edu.learnsphere.core.model.CourseModule;
import edu.learnsphere.core.model.Document;
import edu.learnsphere.core.model.DocumentStatus;
import edu.learnsphere.core.model.ItemOrigin;
import edu.learnsphere.core.model.ItemStatus;
import edu.learnsphere.core.model.JobKind;
import edu.learnsphere.core.model.LearningItem;
import edu.learnsphere.core.model.SupportStatus;
import edu.learnsphere.core.service.AssistantService;
import edu.learnsphere.core.service.AssistantState;
import edu.learnsphere.core.service.AssistantStatus;
import edu.learnsphere.core.service.GenerationRequest;
import edu.learnsphere.core.service.GenerationService;
import edu.learnsphere.core.service.JobContext;
import edu.learnsphere.core.service.JobQueue;
import edu.learnsphere.core.service.JobSpec;
import edu.learnsphere.core.service.JobUpdate;
import edu.learnsphere.core.service.ProfileContext;
import edu.learnsphere.core.service.impl.pipeline.ItemDrafts.Candidate;
import edu.learnsphere.core.spi.Capabilities;
import edu.learnsphere.core.spi.ConceptDraft;
import edu.learnsphere.core.spi.ConceptExtractor;
import edu.learnsphere.core.spi.EmbedKind;
import edu.learnsphere.core.spi.Embedder;
import edu.learnsphere.core.spi.ItemGenerationRequest;
import edu.learnsphere.core.spi.ItemGenerator;
import edu.learnsphere.core.spi.Passage;
import edu.learnsphere.core.spi.SearchHit;
import edu.learnsphere.core.spi.SearchIndex;
import edu.learnsphere.core.spi.SearchQuery;
import edu.learnsphere.core.spi.SupportChecker;
import edu.learnsphere.core.spi.SupportVerdict;
import edu.learnsphere.core.util.CancellationToken;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Collection;
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
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * {@link GenerationService}: concepts and draft learning items written by the study assistant from a course's
 * ready PDFs. The long work runs as jobs; register the handlers with
 *
 * <pre>{@code
 * jobs.register(JobKind.EXTRACT_CONCEPTS, generation::runConceptExtraction);
 * jobs.register(JobKind.GENERATE_ITEMS, generation::runItemGeneration);
 * jobs.register(JobKind.REGENERATE_ITEM, generation::runRegeneration);
 * }</pre>
 *
 * <ul>
 *   <li><b>Concepts</b> ({@link JobKind#EXTRACT_CONCEPTS}, subject: module): up to {@value #MAX_CONCEPT_PASSAGES}
 *       passages spread over the module's ready PDFs go to the {@link ConceptExtractor}. Extracted concepts are
 *       stored with origin {@code ai}; running it again keeps concepts with the same name, removes the earlier
 *       AI concepts that no item uses, and never touches concepts the creator added or edited.</li>
 *   <li><b>Items</b> ({@link JobKind#GENERATE_ITEMS}, subject: course): the request's concepts (or every concept
 *       of the scope, those with the fewest items first) share the wanted count; each concept's passages come
 *       from a hybrid search for its name and description. The {@link ItemGenerator} drafts items, which are
 *       checked against their type's rules, mapped from passage numbers to {@code source_chunk_id} and
 *       {@code source_page}, dropped when their embedding has cosine similarity of at least
 *       {@value #NEAR_DUPLICATE} with an existing draft or approved item of the scope (stored embeddings,
 *       or fresh ones stored for next time), checked by the {@link SupportChecker}, and stored as drafts with
 *       their concept tags. Without concepts, passages are spread over the material.</li>
 *   <li><b>Regenerate</b> ({@link JobKind#REGENERATE_ITEM}, subject: item): a new version of a draft from its
 *       source passage (or, when that only yields the same item, a neighbouring passage of the same PDF),
 *       replacing the draft's content in place.</li>
 * </ul>
 *
 * <p>The wanted count is a target: drafts that break their type's rules or repeat an existing item are dropped,
 * so fewer may be stored. Drafts are stored after each concept together with the job's progress (the share of
 * the wanted count stored, written in the same transaction), so a job interrupted by a restart continues with
 * the remaining count. Study-assistant requests
 * wait while the assistant is starting; generation, support checks and concept extraction run at
 * {@link edu.learnsphere.core.spi.ChatPriority#USER_WAITING}, which their implementations use, and the jobs are
 * queued with {@link JobSpec#USER_PRIORITY}. Thread-safe.
 */
public final class GenerationServiceImpl implements GenerationService {

    private static final Logger LOG = LoggerFactory.getLogger(GenerationServiceImpl.class);

    /** Cosine similarity at or above which a draft counts as a near-duplicate of an existing item. */
    public static final double NEAR_DUPLICATE = 0.92;

    /** Most passages given to the concept extractor. */
    public static final int MAX_CONCEPT_PASSAGES = 24;

    /** Most concepts kept from one extraction. */
    public static final int MAX_CONCEPTS = 15;

    static final int MAX_CONCEPT_NAME = 80;
    static final int MIN_GROUP_PASSAGES = 3;
    static final int MAX_GROUP_PASSAGES = 6;
    static final int ITEMS_PER_MATERIAL_GROUP = 5;
    static final int REGENERATION_CANDIDATES = 3;
    private static final int EMBED_BATCH = 16;
    private static final int MISSING_EMBEDDINGS_PAGE = 64;

    static final UserMessage NO_READY_PDF_IN_MODULE = UserMessage.of("Add a PDF to this module and wait until it's ready.");
    static final UserMessage NO_READY_PDF_IN_COURSE = UserMessage.of("Add a PDF to this course and wait until it's ready.");
    static final UserMessage NOT_CREATED_HERE = UserMessage.of("Only courses you created can be edited here.");
    static final UserMessage CONCEPT_NOT_FOUND = UserMessage.of("This concept isn't in the course.");
    static final UserMessage BLANK_CONCEPT = UserMessage.of("Give the concept a name.");
    static final UserMessage CONCEPT_TAKEN = UserMessage.of("There's already a concept with that name here.");
    static final UserMessage ONLY_DRAFTS = UserMessage.of("Only drafts can be replaced with a new version.");
    static final UserMessage NO_SOURCE = UserMessage.of("This question has no source passage to write a new version from.");
    static final UserMessage NO_NEW_VERSION =
            UserMessage.of("A different version couldn't be written from this passage. Edit it yourself or try again.");
    static final UserMessage NO_CONCEPTS_FOUND =
            UserMessage.of("No key concepts were found in this module. Try again, or add them yourself.");

    /** Payload of {@link JobKind#EXTRACT_CONCEPTS}. */
    public record ConceptJob(String moduleId) {
    }

    /** Payload of {@link JobKind#REGENERATE_ITEM}. */
    public record RegenerationJob(String itemId) {
    }

    /**
     * Result of {@link JobKind#EXTRACT_CONCEPTS}.
     *
     * @param concepts concepts the extraction named
     * @param added    concepts stored that were not there before
     * @param removed  earlier AI concepts that no item used and the extraction did not name again
     */
    public record ConceptOutcome(String moduleId, int concepts, int added, int removed) {
    }

    /**
     * Result of {@link JobKind#GENERATE_ITEMS}.
     *
     * @param created     drafts stored by the job
     * @param duplicates  drafts dropped as near-duplicates of existing items
     * @param unsupported stored drafts whose source passage does not support the answer
     */
    public record GenerationOutcome(int created, int duplicates, int unsupported) {
    }

    /** Result of {@link JobKind#REGENERATE_ITEM}: the draft that holds the new version. */
    public record RegenerationOutcome(String itemId, SupportStatus support) {
    }

    private final Repositories repos;
    private final EventBus events;
    private final Clock clock;
    private final JobQueue jobs;
    private final AssistantService assistant;
    private final BackgroundAi ai;
    private final SearchIndex index;
    private final Embedder embedder;
    private final ConceptExtractor conceptExtractor;
    private final ItemGenerator itemGenerator;
    private final SupportChecker supportChecker;

    public GenerationServiceImpl(Repositories repos, EventBus events, JobQueue jobs, AssistantService assistant,
                                 SearchIndex index, Embedder embedder, ConceptExtractor conceptExtractor,
                                 ItemGenerator itemGenerator, SupportChecker supportChecker) {
        this(repos, events, jobs, assistant, new BackgroundAi(assistant), index, embedder, conceptExtractor, itemGenerator,
                supportChecker);
    }

    GenerationServiceImpl(Repositories repos, EventBus events, JobQueue jobs, AssistantService assistant, BackgroundAi ai,
                          SearchIndex index, Embedder embedder, ConceptExtractor conceptExtractor,
                          ItemGenerator itemGenerator, SupportChecker supportChecker) {
        this.repos = Objects.requireNonNull(repos, "repos");
        this.events = Objects.requireNonNull(events, "events");
        this.clock = repos.clock();
        this.jobs = Objects.requireNonNull(jobs, "jobs");
        this.assistant = Objects.requireNonNull(assistant, "assistant");
        this.ai = Objects.requireNonNull(ai, "ai");
        this.index = Objects.requireNonNull(index, "index");
        this.embedder = Objects.requireNonNull(embedder, "embedder");
        this.conceptExtractor = Objects.requireNonNull(conceptExtractor, "conceptExtractor");
        this.itemGenerator = Objects.requireNonNull(itemGenerator, "itemGenerator");
        this.supportChecker = Objects.requireNonNull(supportChecker, "supportChecker");
    }

    /** The service of an opened profile, from the workspace's capabilities, index, job queue and assistant. */
    public static GenerationServiceImpl create(ProfileContext context, Capabilities capabilities, SearchIndex index,
                                               JobQueue jobs, AssistantService assistant) {
        return new GenerationServiceImpl(context.repositories(), context.events(), jobs, assistant, index,
                capabilities.embedder(), capabilities.conceptExtractor(), capabilities.itemGenerator(),
                capabilities.supportChecker());
    }

    // ---- concepts ------------------------------------------------------------------------------------

    @Override
    public List<Concept> concepts(String courseId, String moduleId) {
        repos.courses().get(courseId);
        if (moduleId == null) {
            return repos.concepts().listByCourse(courseId);
        }
        moduleOf(courseId, moduleId);
        return repos.concepts().listByModule(moduleId);
    }

    @Override
    public Set<String> extractedConceptIds(String courseId) {
        repos.courses().get(courseId);
        Set<String> ids = new HashSet<>();
        for (CourseModule module : repos.modules().listByCourse(courseId)) {
            ids.addAll(repos.concepts().extractedIds(module.id()));
        }
        return Set.copyOf(ids);
    }

    @Override
    public JobUpdate extractConcepts(String moduleId) {
        CourseModule module = repos.modules().get(moduleId);
        requireCreatedHere(repos.courses().get(module.courseId()));
        if (!hasReadyPdf(module.courseId(), moduleId)) {
            throw new InvalidInputException(NO_READY_PDF_IN_MODULE, "Module " + moduleId + " has no ready PDF");
        }
        requireAssistant();
        return jobs.enqueue(JobSpec.of(JobKind.EXTRACT_CONCEPTS, moduleId, new ConceptJob(moduleId)));
    }

    @Override
    public Concept addConcept(String courseId, String moduleId, String name, String description) {
        requireCreatedHere(repos.courses().get(courseId));
        if (moduleId != null) {
            moduleOf(courseId, moduleId);
        }
        String cleanName = conceptName(name);
        String cleanDescription = description == null ? "" : description.strip();
        Concept concept = repos.database().inTransaction(h -> {
            if (repos.concepts().findByName(courseId, moduleId, cleanName).isPresent()) {
                throw new InvalidInputException(CONCEPT_TAKEN, "Concept name taken: " + cleanName);
            }
            Concept created = Concept.create(courseId, moduleId, cleanName, cleanDescription, clock.millis());
            repos.concepts().insert(created);
            return created;
        });
        events.publish(new CourseChanged(courseId, ChangeType.UPDATED));
        return concept;
    }

    @Override
    public Concept updateConcept(String conceptId, String name, String description) {
        Concept concept = concept(conceptId);
        requireCreatedHere(repos.courses().get(concept.courseId()));
        String cleanName = conceptName(name);
        String cleanDescription = description == null ? "" : description.strip();
        Concept updated = repos.database().inTransaction(h -> {
            Optional<Concept> sameName = repos.concepts().findByName(concept.courseId(), concept.moduleId(), cleanName);
            if (sameName.isPresent() && !sameName.get().id().equals(conceptId)) {
                throw new InvalidInputException(CONCEPT_TAKEN, "Concept name taken: " + cleanName);
            }
            repos.concepts().update(conceptId, cleanName, cleanDescription);
            repos.concepts().setOrigin(List.of(conceptId), ItemOrigin.MANUAL);
            return concept(conceptId);
        });
        events.publish(new CourseChanged(concept.courseId(), ChangeType.UPDATED));
        return updated;
    }

    @Override
    public void deleteConcept(String conceptId) {
        Concept concept = concept(conceptId);
        requireCreatedHere(repos.courses().get(concept.courseId()));
        repos.concepts().delete(conceptId);
        events.publish(new CourseChanged(concept.courseId(), ChangeType.UPDATED));
    }

    // ---- items ---------------------------------------------------------------------------------------

    @Override
    public JobUpdate generate(GenerationRequest request) {
        Objects.requireNonNull(request, "request");
        requireCreatedHere(repos.courses().get(request.courseId()));
        if (request.moduleId() != null) {
            moduleOf(request.courseId(), request.moduleId());
        }
        if (!hasReadyPdf(request.courseId(), request.moduleId())) {
            throw new InvalidInputException(noReadyPdf(request.moduleId()), "No ready PDF for generation");
        }
        requireAssistant();
        return jobs.enqueue(JobSpec.of(JobKind.GENERATE_ITEMS, request.courseId(), request).allowingDuplicates());
    }

    @Override
    public JobUpdate regenerate(String itemId) {
        LearningItem item = repos.items().get(itemId);
        requireCreatedHere(repos.courses().get(item.courseId()));
        if (item.status() != ItemStatus.DRAFT) {
            throw new InvalidInputException(ONLY_DRAFTS, "Item " + itemId + " is " + item.status().code());
        }
        if (item.sourceChunkId() == null || repos.chunks().find(item.sourceChunkId()).isEmpty()) {
            throw new InvalidInputException(NO_SOURCE, "Item " + itemId + " has no source passage");
        }
        requireAssistant();
        return jobs.enqueue(JobSpec.of(JobKind.REGENERATE_ITEM, itemId, new RegenerationJob(itemId)));
    }

    // ---- job: concepts -------------------------------------------------------------------------------

    /** Handler of {@link JobKind#EXTRACT_CONCEPTS}. */
    public void runConceptExtraction(JobContext context) {
        CancellationToken token = context.token();
        ConceptJob payload = context.payload(ConceptJob.class);
        String moduleId = payload.moduleId() != null ? payload.moduleId() : context.job().subjectId();
        Optional<CourseModule> found = moduleId == null ? Optional.empty() : repos.modules().find(moduleId);
        if (found.isEmpty()) {
            LOG.info("Module {} was removed before its concepts were extracted", moduleId);
            return;
        }
        CourseModule module = found.get();
        context.progress(0.05, "Reading your material…");
        List<Chunk> material = repos.chunks().listByModule(moduleId);
        if (material.isEmpty()) {
            throw new InvalidInputException(NO_READY_PDF_IN_MODULE, "Module " + moduleId + " has no ready passages");
        }
        Map<String, String> titles = documentTitles(module.courseId());
        List<Passage> passages = numbered(PipelineText.evenlySpaced(material, MAX_CONCEPT_PASSAGES), titles);
        context.progress(0.15, "Finding key concepts…");
        List<ConceptDraft> drafts = cleanConcepts(ai.call(
                () -> conceptExtractor.extract(module.title(), passages, token), token));
        token.throwIfCancelled();
        if (drafts.isEmpty()) {
            throw new LearnSphereException(NO_CONCEPTS_FOUND, "No concepts extracted for module " + moduleId);
        }
        context.progress(0.9, "Saving concepts…");
        ConceptOutcome outcome = repos.database().inTransaction(h -> saveConcepts(module, drafts));
        events.publish(new CourseChanged(module.courseId(), ChangeType.UPDATED));
        context.setResult(outcome);
        LOG.info("Extracted {} concepts for module {} ({} new, {} replaced)", outcome.concepts(), moduleId,
                outcome.added(), outcome.removed());
    }

    private ConceptOutcome saveConcepts(CourseModule module, List<ConceptDraft> drafts) {
        Map<String, Concept> existing = new HashMap<>();
        for (Concept concept : repos.concepts().listByModule(module.id())) {
            existing.putIfAbsent(key(concept.name()), concept);
        }
        Set<String> extracted = repos.concepts().extractedIds(module.id());
        Set<String> keep = new HashSet<>();
        List<Concept> added = new ArrayList<>();
        long now = clock.millis();
        for (ConceptDraft draft : drafts) {
            Concept match = existing.get(key(draft.name()));
            if (match == null) {
                Concept concept = Concept.create(module.courseId(), module.id(), draft.name(), draft.description(), now);
                added.add(concept);
                keep.add(concept.id());
            } else {
                keep.add(match.id());
                if (extracted.contains(match.id()) && !draft.description().isBlank()
                        && !draft.description().equals(match.description())) {
                    repos.concepts().update(match.id(), match.name(), draft.description());
                }
            }
        }
        repos.concepts().insertAll(added);
        repos.concepts().setOrigin(added.stream().map(Concept::id).toList(), ItemOrigin.AI);
        int removed = repos.concepts().deleteUnusedExtracted(module.id(), keep);
        return new ConceptOutcome(module.id(), drafts.size(), added.size(), removed);
    }

    /** Trimmed, de-duplicated (ignoring case) concepts with sensible names, at most {@value #MAX_CONCEPTS}. */
    private static List<ConceptDraft> cleanConcepts(List<ConceptDraft> drafts) {
        Map<String, ConceptDraft> unique = new LinkedHashMap<>();
        for (ConceptDraft draft : drafts == null ? List.<ConceptDraft>of() : drafts) {
            String name = draft.name().replaceAll("\\s+", " ").strip();
            if (name.isEmpty() || name.length() > MAX_CONCEPT_NAME) {
                continue;
            }
            unique.putIfAbsent(key(name), new ConceptDraft(name, draft.description().strip()));
            if (unique.size() == MAX_CONCEPTS) {
                break;
            }
        }
        return List.copyOf(unique.values());
    }

    // ---- job: items ----------------------------------------------------------------------------------

    /** Handler of {@link JobKind#GENERATE_ITEMS}; its payload is the {@link GenerationRequest}. */
    public void runItemGeneration(JobContext context) {
        CancellationToken token = context.token();
        GenerationRequest request = context.payload(GenerationRequest.class);
        String courseId = request.courseId();
        String moduleId = request.moduleId();
        if (repos.courses().find(courseId).isEmpty()
                || (moduleId != null && repos.modules().find(moduleId).filter(m -> m.courseId().equals(courseId)).isEmpty())) {
            LOG.info("Course {} or module {} was removed before items were generated", courseId, moduleId);
            return;
        }
        List<Chunk> material = material(courseId, moduleId);
        if (material.isEmpty()) {
            throw new InvalidInputException(noReadyPdf(moduleId), "No ready passages for generation");
        }
        int count = request.count();
        int saved = Math.max(0, Math.min(count, (int) Math.round(context.job().progress() * count)));
        if (saved >= count) {
            return;
        }
        int resumedFrom = saved;
        context.progress(saved / (double) count, writingLine(saved, count));

        Map<String, String> titles = documentTitles(courseId);
        List<Concept> scopeConcepts = moduleId != null
                ? repos.concepts().listByModule(moduleId) : repos.concepts().listByCourse(courseId);
        List<Concept> focus = request.conceptIds().isEmpty() ? scopeConcepts
                : scopeConcepts.stream().filter(c -> request.conceptIds().contains(c.id())).toList();
        Map<String, Concept> conceptsByName = new HashMap<>();
        scopeConcepts.forEach(c -> conceptsByName.putIfAbsent(key(c.name()), c));

        NearDuplicates known = knownItems(courseId, moduleId, token);
        List<Group> groups = plan(courseId, moduleId, focus, material, count - saved, token);
        int duplicates = 0;
        int unsupported = 0;
        for (Group group : groups) {
            token.throwIfCancelled();
            if (saved >= count) {
                break;
            }
            int target = Math.min(group.target(), count - saved);
            List<Passage> passages = numbered(group.chunks(), titles);
            ItemGenerationRequest generation = new ItemGenerationRequest(passages,
                    group.concept() == null ? List.of() : List.of(group.concept().name()), request.types(), target,
                    request.difficulty());
            List<Candidate> candidates = ItemDrafts.candidates(
                    ai.call(() -> itemGenerator.generate(generation, token), token), passages, group.chunks(),
                    generation.types());
            List<float[]> vectors = embedItems(candidates.stream().map(c -> itemText(c.prompt(), c.answer())).toList(), token);
            List<Candidate> fresh = new ArrayList<>();
            List<float[]> freshVectors = new ArrayList<>();
            for (int i = 0; i < candidates.size() && fresh.size() < target; i++) {
                if (known.isDuplicate(vectors.get(i))) {
                    duplicates++;
                    continue;
                }
                known.add(vectors.get(i));
                fresh.add(candidates.get(i));
                freshVectors.add(vectors.get(i));
            }
            if (fresh.isEmpty()) {
                continue;
            }
            context.progress(saved / (double) count, "Checking questions against your material…");
            List<LearningItem> items = new ArrayList<>();
            Map<String, float[]> embeddings = new LinkedHashMap<>();
            Map<String, List<String>> tags = new LinkedHashMap<>();
            for (int i = 0; i < fresh.size(); i++) {
                Candidate candidate = fresh.get(i);
                SupportVerdict verdict = check(candidate, token);
                LearningItem item = draftItem(courseId, moduleId != null ? moduleId : candidate.chunk().moduleId(),
                        candidate, verdict);
                if (item.support() == SupportStatus.UNSUPPORTED) {
                    unsupported++;
                }
                items.add(item);
                embeddings.put(item.id(), freshVectors.get(i));
                tags.put(item.id(), conceptTags(candidate, conceptsByName, group.concept()));
            }
            token.throwIfCancelled();
            int savedAfter = saved + items.size();
            // The job's progress is written in the same transaction as the drafts (the job queue shares the
            // profile database), so a resumed job knows exactly how many were stored.
            store(items, embeddings, tags,
                    () -> context.progress(savedAfter / (double) count, writingLine(savedAfter, count)));
            saved = savedAfter;
            events.publish(new ItemsChanged(courseId, moduleId,
                    items.stream().map(LearningItem::id).collect(Collectors.toSet()), ChangeType.CREATED));
        }
        context.setResult(new GenerationOutcome(saved - resumedFrom, duplicates, unsupported));
        LOG.info("Generated {} drafts for course {} ({} near-duplicates dropped, {} unsupported)", saved - resumedFrom,
                courseId, duplicates, unsupported);
    }

    private static String writingLine(int done, int total) {
        return "Writing questions… " + done + " of " + total;
    }

    /** Splits the wanted count over concepts (fewest existing items first) or, without concepts, over the material. */
    private List<Group> plan(String courseId, String moduleId, List<Concept> concepts, List<Chunk> material, int wanted,
                             CancellationToken token) {
        if (concepts.isEmpty()) {
            return materialGroups(material, wanted);
        }
        Map<String, Integer> tagged = new HashMap<>();
        repos.items().conceptLinks(courseId).values().forEach(ids -> ids.forEach(id -> tagged.merge(id, 1, Integer::sum)));
        List<Concept> ordered = new ArrayList<>(concepts);
        ordered.sort(Comparator.comparingInt(c -> tagged.getOrDefault(c.id(), 0)));
        List<Concept> used = ordered.subList(0, Math.min(wanted, ordered.size()));
        int base = wanted / used.size();
        int extra = wanted % used.size();
        List<Group> groups = new ArrayList<>();
        for (int i = 0; i < used.size(); i++) {
            int target = base + (i < extra ? 1 : 0);
            int passages = Math.max(MIN_GROUP_PASSAGES, Math.min(MAX_GROUP_PASSAGES, target));
            groups.add(new Group(used.get(i), passagesFor(used.get(i), courseId, moduleId, material, passages, token), target));
        }
        return groups;
    }

    private static List<Group> materialGroups(List<Chunk> material, int wanted) {
        int groupCount = Math.max(1, Math.min(material.size(),
                (wanted + ITEMS_PER_MATERIAL_GROUP - 1) / ITEMS_PER_MATERIAL_GROUP));
        int base = wanted / groupCount;
        int extra = wanted % groupCount;
        List<Group> groups = new ArrayList<>();
        for (int i = 0; i < groupCount; i++) {
            int from = (int) ((long) material.size() * i / groupCount);
            int to = (int) ((long) material.size() * (i + 1) / groupCount);
            int target = base + (i < extra ? 1 : 0);
            if (target > 0) {
                groups.add(new Group(null, PipelineText.evenlySpaced(material.subList(from, to), MAX_GROUP_PASSAGES - 2),
                        target));
            }
        }
        return groups;
    }

    /** Passages about a concept: hybrid search for its name and description, else passages naming it, else a spread. */
    private List<Chunk> passagesFor(Concept concept, String courseId, String moduleId, List<Chunk> material, int limit,
                                    CancellationToken token) {
        Map<String, Chunk> byId = new HashMap<>();
        material.forEach(chunk -> byId.put(chunk.id(), chunk));
        String query = concept.description().isBlank() ? concept.name() : concept.name() + ". " + concept.description();
        float[] vector = ai.call(() -> embedder.embed(List.of(query), EmbedKind.QUERY, token), token).getFirst();
        SearchQuery search = SearchQuery.forCourse(courseId, query, vector).withLimit(limit * 3);
        if (moduleId != null) {
            search = search.inModules(Set.of(moduleId));
        }
        Set<Chunk> found = new LinkedHashSet<>();
        List<SearchHit> hits;
        try {
            hits = index.search(search);
        } catch (RuntimeException e) {
            if (e instanceof CancellationException) {
                throw e;
            }
            LOG.warn("Search for concept {} failed; picking passages by its name", concept.id(), e);
            hits = List.of();
        }
        for (SearchHit hit : hits) {
            Chunk chunk = byId.get(hit.chunkId());
            if (chunk != null) {
                found.add(chunk);
                if (found.size() == limit) {
                    break;
                }
            }
        }
        if (found.isEmpty()) {
            String needle = concept.name().toLowerCase(Locale.ROOT);
            material.stream().filter(chunk -> chunk.text().toLowerCase(Locale.ROOT).contains(needle)).limit(limit)
                    .forEach(found::add);
        }
        if (found.isEmpty()) {
            found.addAll(PipelineText.evenlySpaced(material, limit));
        }
        return List.copyOf(found);
    }

    private SupportVerdict check(Candidate candidate, CancellationToken token) {
        try {
            return ai.call(() -> supportChecker.check(candidate.prompt(), candidate.answer(), candidate.passage(), token),
                    token);
        } catch (LearnSphereException e) {
            LOG.warn("Support check failed; the draft stays unchecked", e);
            return null;
        }
    }

    private LearningItem draftItem(String courseId, String moduleId, Candidate candidate, SupportVerdict verdict) {
        long now = clock.millis();
        LearningItem item = LearningItem.draft(courseId, moduleId, candidate.type(), candidate.prompt(), candidate.answer(),
                candidate.explanation(), candidate.distractors(), candidate.difficulty(), candidate.chunk().id(),
                candidate.passage().pageStart(), ItemOrigin.AI, now);
        return verdict == null ? item : item.withSupport(verdict.status(), blankToNull(verdict.note()), now);
    }

    private static List<String> conceptTags(Candidate candidate, Map<String, Concept> conceptsByName, Concept groupConcept) {
        Set<String> ids = new LinkedHashSet<>();
        for (String name : candidate.conceptNames()) {
            Concept concept = conceptsByName.get(key(name));
            if (concept != null) {
                ids.add(concept.id());
            }
        }
        if (ids.isEmpty() && groupConcept != null) {
            ids.add(groupConcept.id());
        }
        return List.copyOf(ids);
    }

    private void store(List<LearningItem> items, Map<String, float[]> embeddings, Map<String, List<String>> tags,
                       Runnable recordProgress) {
        repos.database().useTransaction(h -> {
            repos.items().insertAll(items);
            repos.items().updateEmbeddings(embeddings, embedder.modelName());
            tags.forEach((itemId, conceptIds) -> {
                if (!conceptIds.isEmpty()) {
                    repos.items().setConcepts(itemId, conceptIds);
                }
            });
            recordProgress.run();
        });
    }

    // ---- job: regenerate -----------------------------------------------------------------------------

    /** Handler of {@link JobKind#REGENERATE_ITEM}. */
    public void runRegeneration(JobContext context) {
        CancellationToken token = context.token();
        RegenerationJob payload = context.payload(RegenerationJob.class);
        String itemId = payload.itemId() != null ? payload.itemId() : context.job().subjectId();
        Optional<LearningItem> found = itemId == null ? Optional.empty() : repos.items().find(itemId);
        if (found.isEmpty() || found.get().status() != ItemStatus.DRAFT) {
            LOG.info("Item {} is gone or no longer a draft; nothing to replace", itemId);
            return;
        }
        LearningItem item = found.get();
        Chunk source = item.sourceChunkId() == null ? null : repos.chunks().find(item.sourceChunkId()).orElse(null);
        if (source == null) {
            throw new InvalidInputException(NO_SOURCE, "Item " + itemId + " has no source passage");
        }
        List<Chunk> chunks = withNeighbours(source);
        List<Passage> passages = numbered(chunks, documentTitles(item.courseId()));
        List<Concept> itemConcepts = repos.concepts().listByIds(repos.items().conceptIds(itemId));
        context.progress(0.1, "Writing a new version…");
        ItemGenerationRequest generation = new ItemGenerationRequest(passages,
                itemConcepts.stream().map(Concept::name).toList(), Set.of(item.type()), REGENERATION_CANDIDATES,
                item.difficulty());
        List<Candidate> candidates = ItemDrafts.candidates(ai.call(() -> itemGenerator.generate(generation, token), token),
                passages, chunks, Set.of(item.type()));
        NearDuplicates known = knownItems(item.courseId(), item.moduleId(), token);
        List<float[]> vectors = embedItems(candidates.stream().map(c -> itemText(c.prompt(), c.answer())).toList(), token);
        int chosen = -1;
        for (int pass = 0; pass < 2 && chosen < 0; pass++) {
            for (int i = 0; i < candidates.size(); i++) {
                boolean fromSource = candidates.get(i).passage().number() == 1;
                if ((pass == 1 || fromSource) && !known.isDuplicate(vectors.get(i))) {
                    chosen = i;
                    break;
                }
            }
        }
        if (chosen < 0) {
            throw new LearnSphereException(NO_NEW_VERSION, "Every new draft for item " + itemId + " repeats an existing item");
        }
        Candidate candidate = candidates.get(chosen);
        context.progress(0.6, "Checking the new version against your material…");
        SupportVerdict verdict = check(candidate, token);
        token.throwIfCancelled();

        Map<String, Concept> conceptsByName = new HashMap<>();
        (item.moduleId() != null ? repos.concepts().listByModule(item.moduleId()) : repos.concepts().listByCourse(item.courseId()))
                .forEach(c -> conceptsByName.putIfAbsent(key(c.name()), c));
        List<String> tags = conceptTags(candidate, conceptsByName, null);
        long now = clock.millis();
        LearningItem replacement = new LearningItem(item.id(), item.courseId(), item.moduleId(), candidate.type(),
                candidate.prompt(), candidate.answer(), candidate.explanation(), candidate.distractors(),
                candidate.difficulty(), candidate.chunk().id(), candidate.passage().pageStart(), ItemOrigin.AI,
                ItemStatus.DRAFT, verdict == null ? SupportStatus.UNCHECKED : verdict.status(),
                verdict == null ? null : blankToNull(verdict.note()), item.createdAt(), now);
        float[] vector = vectors.get(chosen);
        boolean replaced = repos.database().inTransaction(h -> {
            Optional<LearningItem> current = repos.items().find(itemId);
            if (current.isEmpty() || current.get().status() != ItemStatus.DRAFT) {
                return false;
            }
            repos.items().update(replacement);
            repos.items().updateEmbeddings(Map.of(itemId, vector), embedder.modelName());
            if (!tags.isEmpty()) {
                repos.items().setConcepts(itemId, tags);
            }
            return true;
        });
        if (!replaced) {
            LOG.info("Item {} changed while a new version was written; leaving it as it is", itemId);
            return;
        }
        events.publish(new ItemsChanged(item.courseId(), item.moduleId(), Set.of(itemId), ChangeType.UPDATED));
        context.setResult(new RegenerationOutcome(itemId, replacement.support()));
    }

    /** The passage, then the next and the previous passage of the same PDF (when they exist). */
    private List<Chunk> withNeighbours(Chunk source) {
        List<Chunk> all = repos.chunks().listByDocument(source.documentId());
        List<Chunk> result = new ArrayList<>();
        result.add(source);
        for (int i = 0; i < all.size(); i++) {
            if (all.get(i).id().equals(source.id())) {
                if (i + 1 < all.size()) {
                    result.add(all.get(i + 1));
                }
                if (i > 0) {
                    result.add(all.get(i - 1));
                }
                break;
            }
        }
        return result;
    }

    // ---- near-duplicates -----------------------------------------------------------------------------

    /**
     * Embeddings of the draft and approved items of the scope (a module, or the whole course): stored ones by
     * the current embedder, plus fresh ones for items without, which are stored for next time.
     */
    private NearDuplicates knownItems(String courseId, String moduleId, CancellationToken token) {
        NearDuplicates known = new NearDuplicates();
        String model = embedder.modelName();
        List<String> modules = moduleId != null ? List.of(moduleId)
                : repos.modules().listByCourse(courseId).stream().map(CourseModule::id).toList();
        for (String module : modules) {
            known.addAll(repos.items().embeddingsForModule(module, model).values());
            Set<String> attempted = new HashSet<>();
            while (true) {
                List<LearningItem> missing = repos.items().listMissingEmbedding(module, model, MISSING_EMBEDDINGS_PAGE)
                        .stream().filter(item -> attempted.add(item.id())).toList();
                if (missing.isEmpty()) {
                    break;
                }
                known.addAll(embedAndStore(missing, token));
            }
        }
        if (moduleId == null) {
            List<LearningItem> loose = repos.items().listByCourse(courseId, ItemStatus.DRAFT, ItemStatus.APPROVED).stream()
                    .filter(item -> item.moduleId() == null).toList();
            known.addAll(embedAndStore(loose, token));
        }
        return known;
    }

    private Collection<float[]> embedAndStore(List<LearningItem> items, CancellationToken token) {
        if (items.isEmpty()) {
            return List.of();
        }
        List<float[]> vectors = embedItems(items.stream().map(i -> itemText(i.prompt(), i.answer())).toList(), token);
        Map<String, float[]> byId = new LinkedHashMap<>();
        for (int i = 0; i < items.size(); i++) {
            byId.put(items.get(i).id(), vectors.get(i));
        }
        repos.items().updateEmbeddings(byId, embedder.modelName());
        return byId.values();
    }

    /** Embeds item texts as passages, in batches. */
    private List<float[]> embedItems(List<String> texts, CancellationToken token) {
        List<float[]> vectors = new ArrayList<>(texts.size());
        for (int from = 0; from < texts.size(); from += EMBED_BATCH) {
            List<String> batch = texts.subList(from, Math.min(texts.size(), from + EMBED_BATCH));
            List<float[]> result = ai.call(() -> embedder.embed(batch, EmbedKind.DOCUMENT, token), token);
            if (result.size() != batch.size()) {
                throw LearnSphereException.unexpected("Expected " + batch.size() + " vectors, got " + result.size(), null);
            }
            vectors.addAll(result);
        }
        return vectors;
    }

    /** The text an item's embedding is made from: its prompt and answer. */
    static String itemText(String prompt, String answer) {
        return prompt.strip() + "\n" + answer.strip();
    }

    /** Vectors of known items; a vector is a near-duplicate when it is at least {@link #NEAR_DUPLICATE} similar to one. */
    static final class NearDuplicates {

        private final List<float[]> vectors = new ArrayList<>();

        boolean isDuplicate(float[] vector) {
            for (float[] known : vectors) {
                if (PipelineText.cosine(known, vector) >= NEAR_DUPLICATE) {
                    return true;
                }
            }
            return false;
        }

        void add(float[] vector) {
            vectors.add(vector);
        }

        void addAll(Collection<float[]> more) {
            vectors.addAll(more);
        }
    }

    // ---- helpers -------------------------------------------------------------------------------------

    /** Passages for the AI, numbered from 1 in order, titled with their PDF's current title. */
    private static List<Passage> numbered(List<Chunk> chunks, Map<String, String> titles) {
        List<Passage> passages = new ArrayList<>(chunks.size());
        for (Chunk chunk : chunks) {
            passages.add(new Passage(passages.size() + 1, chunk.id(), chunk.documentId(),
                    titles.getOrDefault(chunk.documentId(), ""), chunk.pageStart(), chunk.pageEnd(), chunk.sectionTitle(),
                    chunk.text(), 0));
        }
        return passages;
    }

    /** Passages of ready PDFs in a module, or in the whole course when {@code moduleId} is null, in reading order. */
    private List<Chunk> material(String courseId, String moduleId) {
        if (moduleId != null) {
            return repos.chunks().listByModule(moduleId);
        }
        Set<String> ready = new HashSet<>();
        for (Document document : repos.documents().listByCourse(courseId)) {
            if (document.status() == DocumentStatus.READY) {
                ready.add(document.id());
            }
        }
        return repos.chunks().listByCourse(courseId).stream().filter(chunk -> ready.contains(chunk.documentId())).toList();
    }

    private boolean hasReadyPdf(String courseId, String moduleId) {
        List<Document> documents = moduleId != null
                ? repos.documents().listByModule(moduleId) : repos.documents().listByCourse(courseId);
        return documents.stream().anyMatch(document -> document.status() == DocumentStatus.READY);
    }

    private Map<String, String> documentTitles(String courseId) {
        Map<String, String> titles = new HashMap<>();
        repos.documents().listByCourse(courseId).forEach(document -> titles.put(document.id(), document.title()));
        return titles;
    }

    private CourseModule moduleOf(String courseId, String moduleId) {
        CourseModule module = repos.modules().get(moduleId);
        if (!module.courseId().equals(courseId)) {
            throw NotFoundException.of(UserMessage.MODULE_NOT_FOUND, "module of course " + courseId, moduleId);
        }
        return module;
    }

    private Concept concept(String conceptId) {
        return repos.concepts().find(conceptId)
                .orElseThrow(() -> NotFoundException.of(CONCEPT_NOT_FOUND, "concept", conceptId));
    }

    private static void requireCreatedHere(Course course) {
        if (!course.isCreatedHere()) {
            throw new InvalidInputException(NOT_CREATED_HERE, "Course " + course.id() + " was imported");
        }
    }

    /** Refuses work the assistant cannot do at all; while it is starting, jobs wait for it. */
    private void requireAssistant() {
        AssistantStatus status = assistant.status();
        if (status.state() == AssistantState.UNAVAILABLE || status.state() == AssistantState.FAILED) {
            throw new UnavailableException(status.message(), "Study assistant is " + status.state());
        }
    }

    private static UserMessage noReadyPdf(String moduleId) {
        return moduleId != null ? NO_READY_PDF_IN_MODULE : NO_READY_PDF_IN_COURSE;
    }

    private static String conceptName(String name) {
        String cleaned = name == null ? "" : name.replaceAll("\\s+", " ").strip();
        if (cleaned.isEmpty()) {
            throw new InvalidInputException(BLANK_CONCEPT, "Blank concept name");
        }
        return cleaned;
    }

    private static String key(String name) {
        return name.replaceAll("\\s+", " ").strip().toLowerCase(Locale.ROOT);
    }

    private static String blankToNull(String text) {
        return text == null || text.isBlank() ? null : text.strip();
    }

    /** Passages and target count for one generation request: one concept, or a stretch of the material. */
    private record Group(Concept concept, List<Chunk> chunks, int target) {
    }
}
