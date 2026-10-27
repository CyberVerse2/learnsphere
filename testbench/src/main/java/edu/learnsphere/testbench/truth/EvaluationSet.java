package edu.learnsphere.testbench.truth;

import com.fasterxml.jackson.core.type.TypeReference;
import edu.learnsphere.core.spi.GradeResult;
import edu.learnsphere.core.spi.PageText;
import edu.learnsphere.core.spi.PdfTextExtractor;
import edu.learnsphere.core.util.CancellationToken;
import edu.learnsphere.core.util.Json;
import edu.learnsphere.testbench.material.Material;
import edu.learnsphere.testbench.metrics.TextAccuracy;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * The ground truth the testbench scores against ({@code evaluation-set.json}): questions the material answers,
 * with the pages that answer them; questions it does not cover; short-answer questions with graded student
 * answers; and which pages are scanned.
 *
 * @param scan         the pages of a chapter copied into the scanned PDF
 * @param covered      questions answered by the operating-systems and research-methods chapters
 * @param scanned      questions answered by the scanned copy (pages of the scanned PDF)
 * @param uncovered    questions the material does not answer
 * @param shortAnswers short-answer questions with a model answer and graded responses
 */
public record EvaluationSet(ScanPlan scan, List<Question> covered, List<Question> scanned, List<Uncovered> uncovered,
                            List<ShortAnswer> shortAnswers) {

    public EvaluationSet {
        Objects.requireNonNull(scan, "scan");
        covered = List.copyOf(covered);
        scanned = List.copyOf(scanned);
        uncovered = List.copyOf(uncovered);
        shortAnswers = List.copyOf(shortAnswers);
    }

    /** Which pages of which chapter the scanned PDF shows. */
    public record ScanPlan(String source, List<Integer> pages) {

        public ScanPlan {
            pages = List.copyOf(pages);
        }
    }

    /**
     * A question the material answers.
     *
     * @param document    {@code os}, {@code rm} or {@code scan}
     * @param pages       the pages that answer it
     * @param answer      a reference answer
     * @param wrongAnswer a plausible but false answer (for the support checker)
     * @param keyTerms    facts a correct answer mentions; {@code a|b} lists alternatives
     * @param evidence    a phrase that appears on one of {@code pages}
     */
    public record Question(String id, String document, List<Integer> pages, String question, String answer,
                           String wrongAnswer, List<String> keyTerms, String evidence) {

        public Question {
            pages = List.copyOf(pages);
            keyTerms = List.copyOf(keyTerms);
        }

        /** {@link #pages()} as a set. */
        public Set<Integer> pageSet() {
            return Set.copyOf(pages);
        }
    }

    /** A question the material does not answer, with why it is not covered. */
    public record Uncovered(String id, String question, String note) {
    }

    /** A short-answer question with its model answer and student responses graded by a person. */
    public record ShortAnswer(String id, String document, int page, String question, String modelAnswer,
                              List<Response> responses) {

        public ShortAnswer {
            responses = List.copyOf(responses);
        }
    }

    /** A student response and the verdict a teacher gave it. */
    public record Response(String label, GradeResult.Verdict expected, String text) {
    }

    /** The bundled evaluation set. */
    public static EvaluationSet load() {
        try (InputStream in = EvaluationSet.class.getResourceAsStream("/edu/learnsphere/testbench/evaluation-set.json")) {
            if (in == null) {
                throw new IllegalStateException("Missing evaluation-set.json");
            }
            return Json.mapper().readValue(new String(in.readAllBytes(), StandardCharsets.UTF_8),
                    new TypeReference<EvaluationSet>() { });
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Graded responses over every short-answer question. */
    public int responseCount() {
        return shortAnswers.stream().mapToInt(s -> s.responses().size()).sum();
    }

    /**
     * Checks the ground truth against the generated material: ids are unique, every page exists, and every
     * question's evidence phrase is on one of its pages in the text the PDF tools extract (for the scanned copy,
     * in the authored text of the source page). Returns the problems found; empty when the set is sound.
     */
    public List<String> validate(Material material, PdfTextExtractor extractor) throws IOException {
        List<String> problems = new ArrayList<>();
        Set<String> ids = new HashSet<>();
        Map<String, List<PageText>> extracted = new HashMap<>();
        for (Map.Entry<String, java.nio.file.Path> file : material.files().entrySet()) {
            extracted.put(file.getKey(), extractor.extractAll(file.getValue(), CancellationToken.NONE));
        }
        List<Question> all = new ArrayList<>(covered);
        all.addAll(scanned);
        for (Question question : all) {
            if (!ids.add(question.id())) {
                problems.add(question.id() + ": duplicate id");
            }
            for (int page : question.pages()) {
                String text = pageText(material, extracted, question.document(), page);
                if (text == null) {
                    problems.add(question.id() + ": " + question.document() + " has no page " + page);
                }
            }
            boolean found = question.pages().stream()
                    .map(page -> pageText(material, extracted, question.document(), page))
                    .anyMatch(text -> text != null && simplify(text).contains(simplify(question.evidence())));
            if (!found) {
                problems.add(question.id() + ": evidence \"" + question.evidence() + "\" is not on " + question.document()
                        + " page(s) " + question.pages());
            }
            if (question.keyTerms().isEmpty()) {
                problems.add(question.id() + ": no key terms");
            }
        }
        for (Uncovered question : uncovered) {
            if (!ids.add(question.id())) {
                problems.add(question.id() + ": duplicate id");
            }
        }
        for (ShortAnswer shortAnswer : shortAnswers) {
            if (!ids.add(shortAnswer.id())) {
                problems.add(shortAnswer.id() + ": duplicate id");
            }
            if (pageText(material, extracted, shortAnswer.document(), shortAnswer.page()) == null) {
                problems.add(shortAnswer.id() + ": " + shortAnswer.document() + " has no page " + shortAnswer.page());
            }
        }
        if (!scan.source().equals(material.scanSource()) || !scan.pages().equals(material.scannedPages())) {
            problems.add("scan plan " + scan + " does not match the generated scanned copy");
        }
        return problems;
    }

    private static String pageText(Material material, Map<String, List<PageText>> extracted, String document, int page) {
        if (document.equals(Material.SCANNED)) {
            return page >= 1 && page <= material.scannedPages().size() ? material.scannedReference(page) : null;
        }
        List<PageText> pages = extracted.get(document);
        if (pages == null || page < 1 || page > pages.size()) {
            return null;
        }
        return pages.get(page - 1).text();
    }

    private static String simplify(String text) {
        return TextAccuracy.normalize(text).toLowerCase(Locale.ROOT);
    }
}
