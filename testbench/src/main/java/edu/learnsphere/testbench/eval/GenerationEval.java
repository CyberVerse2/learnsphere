package edu.learnsphere.testbench.eval;

import edu.learnsphere.core.model.Concept;
import edu.learnsphere.core.model.ItemType;
import edu.learnsphere.core.model.LearningItem;
import edu.learnsphere.core.model.SupportStatus;
import edu.learnsphere.core.service.GenerationRequest;
import edu.learnsphere.core.service.GenerationService;
import edu.learnsphere.core.service.ItemFilter;
import edu.learnsphere.core.service.ItemSummary;
import edu.learnsphere.core.service.JobUpdate;
import edu.learnsphere.core.service.impl.pipeline.GenerationServiceImpl.GenerationOutcome;
import edu.learnsphere.core.spi.Passage;
import edu.learnsphere.core.spi.SupportVerdict;
import edu.learnsphere.core.util.CancellationToken;
import edu.learnsphere.testbench.material.Material;
import edu.learnsphere.testbench.metrics.Stats;
import edu.learnsphere.testbench.metrics.TypeRules;
import edu.learnsphere.testbench.probe.ChatLog;
import edu.learnsphere.testbench.truth.EvaluationSet;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Runs the app's question writing end to end (concept extraction, then item generation for each module) and
 * scores what it stores: valid JSON from the model, the type rules, near-duplicates, the support check, and a
 * sample of the items. The support checker is also scored on its own against labelled pairs: each covered
 * question's reference answer (should be supported by its best passage) and its plausible wrong answer (should
 * not be).
 */
public final class GenerationEval {

    private static final Logger LOG = LoggerFactory.getLogger(GenerationEval.class);
    static final String CONCEPTS = "concepts";
    static final String GENERATION = "generation";
    static final String SUPPORT = "support";
    private static final Duration JOB_TIMEOUT = Duration.ofMinutes(40);
    /** Items asked for per module (operating systems, research methods). */
    static final Map<String, Integer> REQUESTED = Map.of(Material.OS, 22, Material.RESEARCH_METHODS, 14);
    /** Word overlap (Jaccard) at or above which two stored items count as near-duplicates here. */
    public static final double DUPLICATE_OVERLAP = 0.7;

    /** One labelled support-check pair. */
    public record SupportPair(String id, boolean expectedSupported, boolean supported, String note, long millis) {

        public boolean agrees() {
            return expectedSupported == supported;
        }
    }

    /** A stored item, for the report. */
    public record StoredItem(ItemType type, String prompt, String answer, List<String> distractors, String explanation,
                             String document, Integer page, SupportStatus support, String supportNote,
                             Optional<String> ruleViolation) {
    }

    /** Two stored items whose prompts share most of their words. */
    public record DuplicatePair(String first, String second, double overlap) {
    }

    /** Everything the generation evaluation measured. */
    public record Result(List<String> concepts, long conceptMs, int requested, int draftsWritten, int stored,
                         int duplicatesDropped, long generationMs, List<StoredItem> items, List<DuplicatePair> duplicates,
                         Map<String, Long> droppedReasons, int structuredReplies, int invalidReplies,
                         List<SupportPair> supportPairs, int unlabelledPairs) {

        public Result {
            concepts = List.copyOf(concepts);
            items = List.copyOf(items);
            duplicates = List.copyOf(duplicates);
            droppedReasons = Map.copyOf(droppedReasons);
            supportPairs = List.copyOf(supportPairs);
        }

        /** Share of JSON replies that parsed. */
        public double jsonValidity() {
            return structuredReplies == 0 ? 0 : 1 - invalidReplies / (double) structuredReplies;
        }

        /** Stored items that break their type's rules. */
        public long ruleViolations() {
            return items.stream().filter(i -> i.ruleViolation().isPresent()).count();
        }

        /** Share of stored items that keep their type's rules. */
        public double ruleCompliance() {
            return items.isEmpty() ? 0 : 1 - ruleViolations() / (double) items.size();
        }

        /** Stored items per type. */
        public Map<ItemType, Long> byType() {
            Map<ItemType, Long> counts = new EnumMap<>(ItemType.class);
            items.forEach(i -> counts.merge(i.type(), 1L, Long::sum));
            return counts;
        }

        /** Stored items per support status. */
        public Map<SupportStatus, Long> bySupport() {
            Map<SupportStatus, Long> counts = new EnumMap<>(SupportStatus.class);
            items.forEach(i -> counts.merge(i.support(), 1L, Long::sum));
            return counts;
        }

        /** Share of stored items in at least one near-duplicate pair. */
        public double duplicateRate() {
            Set<String> involved = new HashSet<>();
            duplicates.forEach(d -> {
                involved.add(d.first());
                involved.add(d.second());
            });
            return items.isEmpty() ? 0 : involved.size() / (double) items.size();
        }

        /** Share of labelled pairs where the support checker agreed with the label. */
        public double supportAgreement() {
            return Stats.share(supportPairs.stream().filter(SupportPair::agrees).count(), supportPairs.size());
        }

        /** Share of reference answers the checker called supported. */
        public double supportTruePositiveRate() {
            List<SupportPair> positives = supportPairs.stream().filter(SupportPair::expectedSupported).toList();
            return Stats.share(positives.stream().filter(SupportPair::supported).count(), positives.size());
        }

        /** Share of wrong answers the checker called unsupported. */
        public double supportTrueNegativeRate() {
            List<SupportPair> negatives = supportPairs.stream().filter(p -> !p.expectedSupported()).toList();
            return Stats.share(negatives.stream().filter(p -> !p.supported()).count(), negatives.size());
        }
    }

    private GenerationEval() {
    }

    /**
     * Extracts concepts and generates items for both chapters' modules, then checks the labelled support pairs.
     *
     * @param bestPassages each covered question's best relevant passage (from the retrieval evaluation)
     */
    public static Result run(Session session, Map<String, Passage> bestPassages) throws InterruptedException {
        GenerationService generation = session.workspace().generation();
        String course = session.mainCourseId();
        List<String> modules = List.of(Material.OS, Material.RESEARCH_METHODS);

        session.phase(CONCEPTS);
        long start = System.nanoTime();
        List<String> concepts = new ArrayList<>();
        for (String key : modules) {
            JobUpdate job = generation.extractConcepts(session.moduleId(key));
            session.awaitJob(job.jobId(), JOB_TIMEOUT);
            generation.concepts(course, session.moduleId(key)).stream().map(Concept::name).forEach(concepts::add);
        }
        long conceptMs = since(start);
        LOG.info("Extracted {} concepts in {} s", concepts.size(), conceptMs / 1000);

        session.phase(GENERATION);
        start = System.nanoTime();
        int requested = 0;
        int duplicates = 0;
        for (String key : modules) {
            int count = REQUESTED.get(key);
            requested += count;
            JobUpdate job = generation.generate(GenerationRequest.forModule(course, session.moduleId(key), count));
            session.awaitJob(job.jobId(), JOB_TIMEOUT);
            GenerationOutcome outcome = session.workspace().jobs().result(job.jobId(), GenerationOutcome.class)
                    .orElse(new GenerationOutcome(0, 0, 0));
            duplicates += outcome.duplicates();
            LOG.info("Module {}: {} of {} items stored, {} near-duplicates dropped", key, outcome.created(), count,
                    outcome.duplicates());
        }
        long generationMs = since(start);
        int drafts = session.recorder().generationCalls().stream().filter(c -> c.label().equals(GENERATION))
                .mapToInt(c -> c.drafts().size()).sum();
        List<StoredItem> items = session.workspace().items().list(ItemFilter.forCourse(course)).stream()
                .map(GenerationEval::stored).toList();
        Map<String, Long> dropped = session.chatLog().droppedDrafts().stream()
                .filter(d -> d.phase().equals(GENERATION))
                .collect(Collectors.groupingBy(GenerationEval::reasonKind, TreeMap::new, Collectors.counting()));

        session.phase(SUPPORT);
        List<SupportPair> pairs = new ArrayList<>();
        int unlabelled = 0;
        for (EvaluationSet.Question question : session.truth().covered()) {
            Passage passage = bestPassages.get(question.id());
            if (passage == null) {
                unlabelled++;
                continue;
            }
            pairs.add(check(session, question.id() + " reference", question.question(), question.answer(), passage, true));
            pairs.add(check(session, question.id() + " wrong", question.question(), question.wrongAnswer(), passage, false));
        }

        List<ChatLog.Completion> replies = new ArrayList<>();
        List<ChatLog.InvalidJson> invalid = new ArrayList<>();
        for (String phase : List.of(CONCEPTS, GENERATION, SUPPORT)) {
            replies.addAll(session.chatLog().completions(phase));
            invalid.addAll(session.chatLog().invalidJson(phase));
        }
        Result result = new Result(concepts, conceptMs, requested, drafts, items.size(), duplicates, generationMs, items,
                nearDuplicates(items), dropped, replies.size(), invalid.size(), pairs, unlabelled);
        LOG.info("Generation: {} items stored, JSON validity {}, support agreement {}", items.size(),
                String.format(Locale.ROOT, "%.3f", result.jsonValidity()),
                String.format(Locale.ROOT, "%.3f", result.supportAgreement()));
        return result;
    }

    private static SupportPair check(Session session, String id, String prompt, String answer, Passage passage,
                                     boolean expected) {
        long start = System.nanoTime();
        SupportVerdict verdict = session.capabilities().supportChecker().check(prompt, answer, passage, CancellationToken.NONE);
        return new SupportPair(id, expected, verdict.supported(), verdict.note(), since(start));
    }

    private static StoredItem stored(ItemSummary summary) {
        LearningItem item = summary.item();
        return new StoredItem(item.type(), item.prompt(), item.answer(), item.distractors(), item.explanation(),
                summary.sourceDocumentTitle(), item.sourcePage(), item.support(), item.supportNote(),
                TypeRules.violation(item.type(), item.prompt(), item.answer(), item.distractors()));
    }

    /** The reason a draft was dropped, without the details that differ from draft to draft. */
    private static String reasonKind(ChatLog.DroppedDraft draft) {
        String reason = draft.reason();
        int colon = reason.indexOf(':');
        return draft.type() + ": " + (colon > 0 ? reason.substring(0, colon) : reason);
    }

    /** Pairs of stored items whose prompts share at least {@value #DUPLICATE_OVERLAP} of their words. */
    static List<DuplicatePair> nearDuplicates(List<StoredItem> items) {
        List<DuplicatePair> pairs = new ArrayList<>();
        List<Set<String>> words = items.stream().map(i -> words(i.prompt() + " " + i.answer())).toList();
        for (int i = 0; i < items.size(); i++) {
            for (int j = i + 1; j < items.size(); j++) {
                double overlap = jaccard(words.get(i), words.get(j));
                if (overlap >= DUPLICATE_OVERLAP) {
                    pairs.add(new DuplicatePair(items.get(i).prompt(), items.get(j).prompt(), overlap));
                }
            }
        }
        return pairs;
    }

    static Set<String> words(String text) {
        return Arrays.stream(text.toLowerCase(Locale.ROOT).split("[^\\p{L}\\p{N}_]+"))
                .filter(w -> !w.isBlank()).collect(Collectors.toCollection(HashSet::new));
    }

    static double jaccard(Set<String> a, Set<String> b) {
        if (a.isEmpty() && b.isEmpty()) {
            return 1;
        }
        Set<String> union = new HashSet<>(a);
        union.addAll(b);
        Set<String> common = new HashSet<>(a);
        common.retainAll(b);
        return common.size() / (double) union.size();
    }

    private static long since(long start) {
        return (System.nanoTime() - start) / 1_000_000;
    }
}
