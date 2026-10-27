package edu.learnsphere.core.service.impl.pipeline;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import edu.learnsphere.core.error.InvalidInputException;
import edu.learnsphere.core.error.LearnSphereException;
import edu.learnsphere.core.error.UnavailableException;
import edu.learnsphere.core.events.ChangeType;
import edu.learnsphere.core.events.CourseChanged;
import edu.learnsphere.core.events.ItemsChanged;
import edu.learnsphere.core.model.Chunk;
import edu.learnsphere.core.model.Concept;
import edu.learnsphere.core.model.Course;
import edu.learnsphere.core.model.CourseModule;
import edu.learnsphere.core.model.CourseOrigin;
import edu.learnsphere.core.model.CourseStatus;
import edu.learnsphere.core.model.Difficulty;
import edu.learnsphere.core.model.ItemOrigin;
import edu.learnsphere.core.model.ItemStatus;
import edu.learnsphere.core.model.ItemType;
import edu.learnsphere.core.model.Job;
import edu.learnsphere.core.model.JobStatus;
import edu.learnsphere.core.model.LearningItem;
import edu.learnsphere.core.model.SupportStatus;
import edu.learnsphere.core.model.Visibility;
import edu.learnsphere.core.service.GenerationRequest;
import edu.learnsphere.core.service.JobUpdate;
import edu.learnsphere.core.service.impl.pipeline.GenerationServiceImpl.ConceptOutcome;
import edu.learnsphere.core.service.impl.pipeline.GenerationServiceImpl.GenerationOutcome;
import edu.learnsphere.core.service.impl.pipeline.GenerationServiceImpl.RegenerationOutcome;
import edu.learnsphere.core.spi.AiStatus;
import edu.learnsphere.core.spi.ConceptDraft;
import edu.learnsphere.core.spi.ConceptExtractor;
import edu.learnsphere.core.spi.ItemDraft;
import edu.learnsphere.core.spi.ItemGenerationRequest;
import edu.learnsphere.core.spi.ItemGenerator;
import edu.learnsphere.core.spi.SupportChecker;
import edu.learnsphere.core.util.CancellationToken;
import edu.learnsphere.core.util.Ids;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class GenerationServiceImplTest {

    private static final String MITOCHONDRIA = "# Mitochondria\nMitochondria release energy. Mitochondria use glucose and oxygen.";
    private static final String RIBOSOMES = "# Ribosomes\nRibosomes build proteins. Ribosomes read messenger RNA.";
    private static final String MEMBRANE = "# Membranes\nThe membrane controls transport. The membrane is made of lipids.";
    private static final String NUCLEUS = "# Nucleus\nThe nucleus stores DNA. The nucleus controls the cell.";

    @TempDir
    Path dir;

    private PipelineFixture f;
    private PipelineFixture.Material material;
    private Course course;
    private CourseModule module;
    private GenerationServiceImpl generation;

    @BeforeEach
    void setUp() {
        f = new PipelineFixture(dir);
        material = f.readyMaterial(MITOCHONDRIA, RIBOSOMES, MEMBRANE, NUCLEUS);
        course = material.course();
        module = material.module();
        generation = f.generation();
        f.register(generation);
    }

    @AfterEach
    void tearDown() {
        f.close();
    }

    private GenerationServiceImpl generationWith(ConceptExtractor extractor, ItemGenerator generator, SupportChecker checker) {
        GenerationServiceImpl service = new GenerationServiceImpl(f.repos, f.events, f.queue, f.assistant, f.index,
                f.ai.embedder(), extractor, generator, checker);
        f.register(service);
        return service;
    }

    private Job await(JobUpdate update) {
        return f.awaitJob(update.jobId());
    }

    private <T> T result(Job job, Class<T> type) {
        assertThat(job.status()).as("job status (message: %s)", job.message()).isEqualTo(JobStatus.DONE);
        return f.queue.result(job.id(), type).orElseThrow();
    }

    private List<LearningItem> items() {
        return f.repos.items().listByModule(module.id());
    }

    private Set<String> names(List<Concept> concepts) {
        return Set.copyOf(concepts.stream().map(Concept::name).toList());
    }

    private Chunk chunkOnPage(int page) {
        return f.repos.chunks().listByDocument(material.document().id()).get(page - 1);
    }

    // ---- concepts ------------------------------------------------------------------------------------

    @Test
    void extractsTheKeyConceptsOfAModule() {
        ConceptOutcome outcome = result(await(generation.extractConcepts(module.id())), ConceptOutcome.class);

        assertThat(outcome).isEqualTo(new ConceptOutcome(module.id(), 5, 5, 0));
        List<Concept> concepts = generation.concepts(course.id(), module.id());
        assertThat(names(concepts)).containsExactlyInAnyOrder("Controls", "Membrane", "Mitochondria", "Nucleus", "Ribosomes");
        assertThat(concepts).allSatisfy(concept -> {
            assertThat(concept.moduleId()).isEqualTo(module.id());
            assertThat(concept.description()).isNotBlank();
        });
        assertThat(f.repos.concepts().extractedIds(module.id())).hasSize(5);
        assertThat(generation.concepts(course.id(), null)).hasSize(5);
        assertThat(f.events(CourseChanged.class)).extracting(CourseChanged::courseId).contains(course.id());
    }

    @Test
    void extractingAgainReplacesOnlyUnusedExtractedConcepts() {
        result(await(generation.extractConcepts(module.id())), ConceptOutcome.class);
        Map<String, Concept> before = byName(generation.concepts(course.id(), module.id()));
        Concept manual = generation.addConcept(course.id(), module.id(), "Osmosis", "Water moving across a membrane.");
        Concept edited = generation.updateConcept(before.get("Nucleus").id(), "Nucleus", "Where the cell keeps its DNA.");
        LearningItem tagged = f.t.item(course, module, ItemType.FLASHCARD, ItemStatus.DRAFT, "What controls transport?");
        f.repos.items().setConcepts(tagged.id(), List.of(before.get("Controls").id()));
        ConceptExtractor different = (title, passages, token) -> List.of(
                new ConceptDraft("ribosomes", "Builders of proteins."), new ConceptDraft("Photosynthesis", ""),
                new ConceptDraft("  Ribosomes ", "A repeat."), new ConceptDraft(" ", "No name."));
        GenerationServiceImpl service = generationWith(different, f.ai.itemGenerator(), f.ai.supportChecker());

        ConceptOutcome outcome = result(await(service.extractConcepts(module.id())), ConceptOutcome.class);

        assertThat(outcome).isEqualTo(new ConceptOutcome(module.id(), 2, 1, 2));
        Map<String, Concept> after = byName(service.concepts(course.id(), module.id()));
        assertThat(after.keySet()).containsExactlyInAnyOrder("Controls", "Nucleus", "Osmosis", "Photosynthesis", "Ribosomes");
        assertThat(after.get("Ribosomes").id()).isEqualTo(before.get("Ribosomes").id());
        assertThat(after.get("Ribosomes").description()).isEqualTo("Builders of proteins.");
        assertThat(after.get("Nucleus").description()).isEqualTo(edited.description());
        assertThat(after.get("Osmosis").id()).isEqualTo(manual.id());
        assertThat(f.repos.items().conceptIds(tagged.id())).containsExactly(before.get("Controls").id());
    }

    @Test
    void conceptExtractionNeedsAReadyPdfAndAnAvailableAssistant() {
        CourseModule empty = f.t.module(course, "Genetics");
        assertThatThrownBy(() -> generation.extractConcepts(empty.id()))
                .isInstanceOfSatisfying(InvalidInputException.class,
                        e -> assertThat(e.userMessage()).isEqualTo(GenerationServiceImpl.NO_READY_PDF_IN_MODULE));

        f.ai.runtime().setStatus(new AiStatus(AiStatus.State.UNAVAILABLE, null, "no runtime"));
        assertThatThrownBy(() -> generation.extractConcepts(module.id()))
                .isInstanceOfSatisfying(UnavailableException.class,
                        e -> assertThat(e.userMessage().text()).isEqualTo("Your study assistant can't run on this computer."));
    }

    @Test
    void anExtractionThatFindsNothingFailsWithAMessage() {
        GenerationServiceImpl service = generationWith((title, passages, token) -> List.of(), f.ai.itemGenerator(),
                f.ai.supportChecker());

        Job job = await(service.extractConcepts(module.id()));

        assertThat(job.status()).isEqualTo(JobStatus.FAILED);
        assertThat(job.message()).isEqualTo(GenerationServiceImpl.NO_CONCEPTS_FOUND.text());
    }

    @Test
    void addsEditsAndDeletesConceptsByHand() {
        Concept added = generation.addConcept(course.id(), module.id(), "  Cell   wall ", null);
        assertThat(added.name()).isEqualTo("Cell wall");
        assertThat(added.description()).isEmpty();
        assertThatThrownBy(() -> generation.addConcept(course.id(), module.id(), "cell WALL", ""))
                .isInstanceOfSatisfying(InvalidInputException.class,
                        e -> assertThat(e.userMessage()).isEqualTo(GenerationServiceImpl.CONCEPT_TAKEN));
        assertThatThrownBy(() -> generation.addConcept(course.id(), module.id(), "  ", ""))
                .isInstanceOfSatisfying(InvalidInputException.class,
                        e -> assertThat(e.userMessage()).isEqualTo(GenerationServiceImpl.BLANK_CONCEPT));

        Concept other = generation.addConcept(course.id(), module.id(), "Chloroplast", "");
        assertThatThrownBy(() -> generation.updateConcept(other.id(), "Cell wall", ""))
                .isInstanceOf(InvalidInputException.class);
        Concept renamed = generation.updateConcept(other.id(), "Chloroplasts", "Where photosynthesis happens.");
        assertThat(renamed.name()).isEqualTo("Chloroplasts");
        assertThat(renamed.description()).isEqualTo("Where photosynthesis happens.");

        LearningItem item = f.t.item(course, module, ItemType.FLASHCARD, ItemStatus.DRAFT, "Plant cells");
        f.repos.items().setConcepts(item.id(), List.of(added.id()));
        generation.deleteConcept(added.id());
        assertThat(names(generation.concepts(course.id(), module.id()))).containsExactly("Chloroplasts");
        assertThat(f.repos.items().conceptIds(item.id())).isEmpty();
    }

    @Test
    void onlyCoursesCreatedHereCanBeEdited() {
        Course imported = new Course(Ids.newId(), "Chemistry", "", "", "Grace", CourseOrigin.IMPORTED, Visibility.PRIVATE,
                CourseStatus.PUBLISHED, 30, 1, f.t.now(), Ids.newId(), null, f.t.now(), f.t.now());
        f.repos.courses().insert(imported);

        assertThatThrownBy(() -> generation.addConcept(imported.id(), null, "Bonds", ""))
                .isInstanceOfSatisfying(InvalidInputException.class,
                        e -> assertThat(e.userMessage()).isEqualTo(GenerationServiceImpl.NOT_CREATED_HERE));
        assertThatThrownBy(() -> generation.generate(new GenerationRequest(imported.id(), null, Set.of(), 5, null, Set.of())))
                .isInstanceOf(InvalidInputException.class);
    }

    // ---- generating items ----------------------------------------------------------------------------

    @Test
    void writesDraftsWithSourcesSupportVerdictsAndEmbeddings() {
        GenerationOutcome outcome = result(await(generation.generate(GenerationRequest.forModule(course.id(), module.id(), 5))),
                GenerationOutcome.class);

        assertThat(outcome).isEqualTo(new GenerationOutcome(5, 0, 1));
        List<LearningItem> items = items();
        assertThat(items).hasSize(5).allSatisfy(item -> {
            assertThat(item.status()).isEqualTo(ItemStatus.DRAFT);
            assertThat(item.origin()).isEqualTo(ItemOrigin.AI);
            assertThat(item.courseId()).isEqualTo(course.id());
            assertThat(item.moduleId()).isEqualTo(module.id());
            Chunk source = f.repos.chunks().find(item.sourceChunkId()).orElseThrow();
            assertThat(item.sourcePage()).isEqualTo(source.pageStart());
        });
        assertThat(items).extracting(LearningItem::type).containsExactlyInAnyOrder(ItemType.values());
        LearningItem mcq = only(items, ItemType.MCQ);
        assertThat(mcq.sourceChunkId()).isEqualTo(chunkOnPage(1).id());
        assertThat(mcq.answer()).isEqualTo("Mitochondria release energy.");
        assertThat(mcq.distractors()).hasSize(3);
        assertThat(mcq.support()).isEqualTo(SupportStatus.SUPPORTED);
        assertThat(mcq.supportNote()).isEqualTo("The source passage on p. 1 backs this answer.");
        LearningItem trueFalse = only(items, ItemType.TRUE_FALSE);
        assertThat(trueFalse.sourcePage()).isEqualTo(2);
        assertThat(trueFalse.support()).isEqualTo(SupportStatus.UNSUPPORTED);
        assertThat(only(items, ItemType.CLOZE).prompt()).contains(ItemType.CLOZE_BLANK);
        assertThat(f.repos.items().embeddingsForModule(module.id(), f.ai.embedder().modelName()).keySet())
                .containsExactlyInAnyOrderElementsOf(items.stream().map(LearningItem::id).toList());
        assertThat(f.events(ItemsChanged.class)).filteredOn(e -> e.type() == ChangeType.CREATED)
                .flatExtracting(ItemsChanged::itemIds)
                .containsExactlyInAnyOrderElementsOf(items.stream().map(LearningItem::id).toList());
    }

    @Test
    void reportsHowManyQuestionsAreWritten() {
        Job job = await(generation.generate(GenerationRequest.forModule(course.id(), module.id(), 10)));

        assertThat(job.status()).isEqualTo(JobStatus.DONE);
        assertThat(f.jobMessages(job.id())).contains("Writing questions… 0 of 10", "Checking questions against your material…",
                "Writing questions… 5 of 10");
    }

    @Test
    void sharesTheCountOverConceptsAndTagsEachDraft() {
        result(await(generation.extractConcepts(module.id())), ConceptOutcome.class);
        Set<String> conceptIds = Set.copyOf(generation.concepts(course.id(), module.id()).stream().map(Concept::id).toList());
        RecordingGenerator recording = new RecordingGenerator(f.ai.itemGenerator());
        GenerationServiceImpl service = generationWith(f.ai.conceptExtractor(), recording, f.ai.supportChecker());

        GenerationOutcome outcome = result(await(service.generate(
                new GenerationRequest(course.id(), module.id(), Set.of(ItemType.FLASHCARD, ItemType.SHORT_ANSWER), 10,
                        Difficulty.HARD, Set.of()))), GenerationOutcome.class);

        assertThat(recording.requests).hasSize(5).allSatisfy(request -> {
            assertThat(request.count()).isEqualTo(2);
            assertThat(request.conceptNames()).hasSize(1);
            assertThat(request.types()).containsExactlyInAnyOrder(ItemType.FLASHCARD, ItemType.SHORT_ANSWER);
            assertThat(request.difficulty()).isEqualTo(Difficulty.HARD);
            assertThat(request.passages()).hasSizeBetween(1, GenerationServiceImpl.MAX_GROUP_PASSAGES);
        });
        assertThat(recording.requests).flatExtracting(ItemGenerationRequest::conceptNames)
                .containsExactlyInAnyOrder("Controls", "Membrane", "Mitochondria", "Nucleus", "Ribosomes");
        List<LearningItem> items = items();
        assertThat(items).hasSize(outcome.created());
        assertThat(outcome.created() + outcome.duplicates()).isEqualTo(10);
        assertThat(items).allSatisfy(item -> {
            assertThat(f.repos.items().conceptIds(item.id())).hasSize(1).first().isIn(conceptIds);
            assertThat(item.difficulty()).isEqualTo(Difficulty.HARD);
            assertThat(item.type()).isIn(ItemType.FLASHCARD, ItemType.SHORT_ANSWER);
        });
        assertNoNearDuplicates(items);
    }

    @Test
    void focusesOnTheChosenConcepts() {
        result(await(generation.extractConcepts(module.id())), ConceptOutcome.class);
        Concept ribosomes = byName(generation.concepts(course.id(), module.id())).get("Ribosomes");
        RecordingGenerator recording = new RecordingGenerator(f.ai.itemGenerator());
        GenerationServiceImpl service = generationWith(f.ai.conceptExtractor(), recording, f.ai.supportChecker());

        result(await(service.generate(new GenerationRequest(course.id(), module.id(), Set.of(ItemType.FLASHCARD), 2, null,
                Set.of(ribosomes.id())))), GenerationOutcome.class);

        assertThat(recording.requests).singleElement().satisfies(request -> {
            assertThat(request.conceptNames()).containsExactly("Ribosomes");
            assertThat(request.passages().getFirst().text()).contains("Ribosomes build proteins.");
        });
        assertThat(items()).allSatisfy(item -> assertThat(f.repos.items().conceptIds(item.id())).containsExactly(ribosomes.id()));
    }

    @Test
    void dropsDraftsThatRepeatExistingItems() {
        GenerationRequest request = GenerationRequest.forModule(course.id(), module.id(), 5);
        result(await(generation.generate(request)), GenerationOutcome.class);

        GenerationOutcome again = result(await(generation.generate(request)), GenerationOutcome.class);

        assertThat(again).isEqualTo(new GenerationOutcome(0, 5, 0));
        assertThat(items()).hasSize(5);
    }

    @Test
    void comparesWithItemsThatHaveNoStoredEmbeddingAndStoresOne() {
        GenerationRequest request = GenerationRequest.forModule(course.id(), module.id(), 5);
        result(await(generation.generate(request)), GenerationOutcome.class);
        f.t.db.useHandle(h -> h.execute("UPDATE learning_items SET embedding = NULL, embedding_model = NULL"));
        String first = items().getFirst().id();
        f.t.db.useHandle(h -> h.execute("UPDATE learning_items SET embedding_model = 'older-embedder' WHERE id = ?", first));
        f.t.db.useHandle(h -> h.execute("UPDATE learning_items SET embedding = X'00' WHERE id = ?", first));

        GenerationOutcome again = result(await(generation.generate(request)), GenerationOutcome.class);

        assertThat(again.created()).isZero();
        assertThat(again.duplicates()).isEqualTo(5);
        assertThat(f.repos.items().listMissingEmbedding(module.id(), f.ai.embedder().modelName(), 10)).isEmpty();
    }

    @Test
    void dropsDraftsThatRepeatAnApprovedItemWrittenByHand() {
        LearningItem written = LearningItem.draft(course.id(), module.id(), ItemType.FLASHCARD, "Mitochondria",
                "Mitochondria release energy.", "", List.of(), Difficulty.MEDIUM, null, null, ItemOrigin.MANUAL, f.t.now())
                .withStatus(ItemStatus.APPROVED, f.t.now());
        f.repos.items().insert(written);

        GenerationOutcome outcome = result(await(generation.generate(new GenerationRequest(course.id(), module.id(),
                Set.of(ItemType.FLASHCARD), 4, null, Set.of()))), GenerationOutcome.class);

        assertThat(outcome.duplicates()).isEqualTo(1);
        assertThat(items()).filteredOn(item -> item.origin() == ItemOrigin.AI).hasSize(3)
                .extracting(LearningItem::sourcePage).containsExactlyInAnyOrder(2, 3, 4);
    }

    @Test
    void dropsRepeatsWithinOneBatchAndDraftsThatBreakTheirTypesRules() {
        ItemGenerator repetitive = (request, token) -> List.of(
                new ItemDraft(ItemType.FLASHCARD, "Ribosomes", "Ribosomes build proteins.", "", List.of(), null, 1, List.of()),
                new ItemDraft(ItemType.FLASHCARD, "Ribosomes ", " Ribosomes build proteins.", "", List.of(), null, 1, List.of()),
                new ItemDraft(ItemType.CLOZE, "No blank here", "x", "", List.of(), null, 1, List.of()),
                new ItemDraft(ItemType.TRUE_FALSE, "Ribosomes read RNA.", "maybe", "", List.of(), null, 1, List.of()),
                new ItemDraft(ItemType.MCQ, "Which?", "A", "", List.of("a", "A "), null, 1, List.of()),
                new ItemDraft(ItemType.FLASHCARD, "Nucleus", "The nucleus stores DNA.", "", List.of(), null, 9, List.of()),
                new ItemDraft(ItemType.TRUE_FALSE, "Ribosomes read messenger RNA.", " TRUE ", "", List.of(), null, 1,
                        List.of()));
        GenerationServiceImpl service = generationWith(f.ai.conceptExtractor(), repetitive, f.ai.supportChecker());

        GenerationOutcome outcome = result(await(service.generate(GenerationRequest.forModule(course.id(), module.id(), 5))),
                GenerationOutcome.class);

        assertThat(outcome.created()).isEqualTo(2);
        assertThat(outcome.duplicates()).isEqualTo(1);
        assertThat(items()).extracting(LearningItem::answer).containsExactlyInAnyOrder("Ribosomes build proteins.", "true");
    }

    @Test
    void keepsDraftsUncheckedWhenTheSupportCheckFails() {
        SupportChecker failing = (prompt, answer, passage, token) -> {
            throw new LearnSphereException(edu.learnsphere.core.error.UserMessage.SOMETHING_WENT_WRONG, "bad JSON");
        };
        GenerationServiceImpl service = generationWith(f.ai.conceptExtractor(), f.ai.itemGenerator(), failing);

        GenerationOutcome outcome = result(await(service.generate(GenerationRequest.forModule(course.id(), module.id(), 3))),
                GenerationOutcome.class);

        assertThat(outcome.created()).isEqualTo(3);
        assertThat(items()).extracting(LearningItem::support).containsOnly(SupportStatus.UNCHECKED);
    }

    @Test
    void generationNeedsAReadyPdfInTheScope() {
        CourseModule empty = f.t.module(course, "Genetics");
        assertThatThrownBy(() -> generation.generate(GenerationRequest.forModule(course.id(), empty.id(), 5)))
                .isInstanceOfSatisfying(InvalidInputException.class,
                        e -> assertThat(e.userMessage()).isEqualTo(GenerationServiceImpl.NO_READY_PDF_IN_MODULE));
        Course other = f.t.course("Physics");
        assertThatThrownBy(() -> generation.generate(new GenerationRequest(other.id(), null, Set.of(), 5, null, Set.of())))
                .isInstanceOfSatisfying(InvalidInputException.class,
                        e -> assertThat(e.userMessage()).isEqualTo(GenerationServiceImpl.NO_READY_PDF_IN_COURSE));
    }

    @Test
    void generatesForAWholeCourseIntoTheModulesOfTheSources() {
        GenerationOutcome outcome = result(await(generation.generate(
                new GenerationRequest(course.id(), null, Set.of(ItemType.FLASHCARD), 3, null, Set.of()))),
                GenerationOutcome.class);

        assertThat(outcome.created()).isEqualTo(3);
        assertThat(f.repos.items().listByCourse(course.id())).extracting(LearningItem::moduleId).containsOnly(module.id());
    }

    // ---- cancellation and resume ---------------------------------------------------------------------

    @Test
    void aCancelledGenerationStopsWithoutStoringTheBatchInProgress() {
        Gate gate = new Gate(1);
        ItemGenerator gated = (request, token) -> {
            gate.pass(token);
            return f.ai.itemGenerator().generate(request, token);
        };
        GenerationServiceImpl service = generationWith(f.ai.conceptExtractor(), gated, f.ai.supportChecker());
        JobUpdate job = service.generate(GenerationRequest.forModule(course.id(), module.id(), 5));
        gate.awaitReached();

        f.queue.cancel(job.jobId());

        assertThat(f.awaitJob(job.jobId()).status()).isEqualTo(JobStatus.CANCELLED);
        assertThat(items()).isEmpty();
    }

    @Test
    void anInterruptedGenerationContinuesWithTheRemainingCount() {
        Gate gate = new Gate(2);
        ItemGenerator gated = (request, token) -> {
            gate.pass(token);
            return f.ai.itemGenerator().generate(request, token);
        };
        GenerationServiceImpl first = generationWith(f.ai.conceptExtractor(), gated, f.ai.supportChecker());
        JobUpdate job = first.generate(GenerationRequest.forModule(course.id(), module.id(), 10));
        gate.awaitReached();

        f.queue.close();

        Job interrupted = f.repos.jobs().find(job.jobId()).orElseThrow();
        assertThat(interrupted.status()).isEqualTo(JobStatus.QUEUED);
        assertThat(interrupted.progress()).isEqualTo(0.5);
        assertThat(items()).hasSize(5);

        f.queue = f.newQueue();
        RecordingGenerator recording = new RecordingGenerator(f.ai.itemGenerator());
        generationWith(f.ai.conceptExtractor(), recording, f.ai.supportChecker());
        f.queue.resumeUnfinished();

        GenerationOutcome outcome = result(f.awaitJob(job.jobId()), GenerationOutcome.class);
        assertThat(recording.requests).extracting(ItemGenerationRequest::count).containsExactly(5);
        assertThat(items()).hasSize(5 + outcome.created());
        assertNoNearDuplicates(items());
    }

    // ---- regenerating --------------------------------------------------------------------------------

    @Test
    void replacesADraftWithANewVersionFromItsPassageOrANeighbour() {
        result(await(generation.generate(GenerationRequest.forModule(course.id(), module.id(), 5))), GenerationOutcome.class);
        LearningItem mcq = only(items(), ItemType.MCQ);

        RegenerationOutcome outcome = result(await(generation.regenerate(mcq.id())), RegenerationOutcome.class);

        assertThat(outcome.itemId()).isEqualTo(mcq.id());
        LearningItem replaced = f.repos.items().get(mcq.id());
        assertThat(replaced.type()).isEqualTo(ItemType.MCQ);
        assertThat(replaced.status()).isEqualTo(ItemStatus.DRAFT);
        assertThat(replaced.prompt()).isNotEqualTo(mcq.prompt());
        assertThat(replaced.sourceChunkId()).isEqualTo(chunkOnPage(2).id());
        assertThat(replaced.sourcePage()).isEqualTo(2);
        assertThat(replaced.support()).isEqualTo(outcome.support()).isEqualTo(SupportStatus.SUPPORTED);
        assertThat(replaced.createdAt()).isEqualTo(mcq.createdAt());
        assertThat(items()).hasSize(5);
        assertThat(f.repos.items().embeddingsForModule(module.id(), f.ai.embedder().modelName())).containsKey(mcq.id());
        assertThat(f.events(ItemsChanged.class)).filteredOn(e -> e.type() == ChangeType.UPDATED)
                .singleElement().satisfies(e -> assertThat(e.itemIds()).containsExactly(mcq.id()));
    }

    @Test
    void prefersANewVersionFromTheSamePassage() {
        LearningItem draft = LearningItem.draft(course.id(), module.id(), ItemType.FLASHCARD, "Energy",
                "Something else entirely.", "", List.of(), Difficulty.MEDIUM, chunkOnPage(1).id(), 1, ItemOrigin.AI, f.t.now());
        f.repos.items().insert(draft);

        result(await(generation.regenerate(draft.id())), RegenerationOutcome.class);

        LearningItem replaced = f.repos.items().get(draft.id());
        assertThat(replaced.sourceChunkId()).isEqualTo(chunkOnPage(1).id());
        assertThat(replaced.answer()).isEqualTo("Mitochondria release energy.");
    }

    @Test
    void failsWhenEveryNewVersionRepeatsAnExistingItem() {
        result(await(generation.generate(GenerationRequest.forModule(course.id(), module.id(), 5))), GenerationOutcome.class);
        LearningItem mcq = only(items(), ItemType.MCQ);
        ItemGenerator same = (request, token) -> List.of(new ItemDraft(mcq.type(), mcq.prompt(), mcq.answer(),
                mcq.explanation(), mcq.distractors(), mcq.difficulty(), 1, List.of()));
        GenerationServiceImpl service = generationWith(f.ai.conceptExtractor(), same, f.ai.supportChecker());

        Job job = await(service.regenerate(mcq.id()));

        assertThat(job.status()).isEqualTo(JobStatus.FAILED);
        assertThat(job.message()).isEqualTo(GenerationServiceImpl.NO_NEW_VERSION.text());
        assertThat(f.repos.items().get(mcq.id())).isEqualTo(mcq);
    }

    @Test
    void onlyDraftsWithASourceCanBeRegenerated() {
        LearningItem approved = f.t.item(course, module, ItemType.FLASHCARD, ItemStatus.APPROVED, "Approved");
        LearningItem sourceless = f.t.item(course, module, ItemType.FLASHCARD, ItemStatus.DRAFT, "Written by hand");

        assertThatThrownBy(() -> generation.regenerate(approved.id()))
                .isInstanceOfSatisfying(InvalidInputException.class,
                        e -> assertThat(e.userMessage()).isEqualTo(GenerationServiceImpl.ONLY_DRAFTS));
        assertThatThrownBy(() -> generation.regenerate(sourceless.id()))
                .isInstanceOfSatisfying(InvalidInputException.class,
                        e -> assertThat(e.userMessage()).isEqualTo(GenerationServiceImpl.NO_SOURCE));
    }

    // ---- helpers ---------------------------------------------------------------------------------------

    private static Map<String, Concept> byName(List<Concept> concepts) {
        Map<String, Concept> map = new java.util.HashMap<>();
        concepts.forEach(concept -> map.put(concept.name(), concept));
        return map;
    }

    private static LearningItem only(List<LearningItem> items, ItemType type) {
        List<LearningItem> matching = items.stream().filter(item -> item.type() == type).toList();
        assertThat(matching).as("items of type %s", type).hasSize(1);
        return matching.getFirst();
    }

    private void assertNoNearDuplicates(List<LearningItem> items) {
        List<float[]> vectors = new ArrayList<>();
        for (LearningItem item : items) {
            vectors.add(f.ai.embedder().vector(GenerationServiceImpl.itemText(item.prompt(), item.answer())));
        }
        for (int i = 0; i < vectors.size(); i++) {
            for (int j = i + 1; j < vectors.size(); j++) {
                assertThat(PipelineText.cosine(vectors.get(i), vectors.get(j)))
                        .as("similarity of %s and %s", items.get(i).prompt(), items.get(j).prompt())
                        .isLessThan(GenerationServiceImpl.NEAR_DUPLICATE);
            }
        }
    }

    /** Records each request it is given. */
    private static final class RecordingGenerator implements ItemGenerator {

        final List<ItemGenerationRequest> requests = new CopyOnWriteArrayList<>();
        private final ItemGenerator delegate;

        RecordingGenerator(ItemGenerator delegate) {
            this.delegate = delegate;
        }

        @Override
        public List<ItemDraft> generate(ItemGenerationRequest request, CancellationToken token) {
            requests.add(request);
            return delegate.generate(request, token);
        }
    }
}
