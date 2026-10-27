package edu.learnsphere.testbench.report;

import edu.learnsphere.ai.runtime.ServerRole;
import edu.learnsphere.core.model.ItemType;
import edu.learnsphere.core.model.SupportStatus;
import edu.learnsphere.core.spi.GradeResult;
import edu.learnsphere.testbench.eval.AnswerEval;
import edu.learnsphere.testbench.eval.GenerationEval;
import edu.learnsphere.testbench.eval.GradingEval;
import edu.learnsphere.testbench.eval.IngestionEval;
import edu.learnsphere.testbench.eval.ModelRun;
import edu.learnsphere.testbench.eval.RetrievalEval;
import edu.learnsphere.testbench.material.Chapter;
import edu.learnsphere.testbench.material.Material;
import edu.learnsphere.testbench.material.ScanSimulator;
import edu.learnsphere.testbench.metrics.Stats;
import edu.learnsphere.testbench.probe.ChatLog;
import edu.learnsphere.testbench.probe.MemorySampler;
import edu.learnsphere.testbench.probe.ServerLogs;
import edu.learnsphere.testbench.truth.EvaluationSet;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.stream.Collectors;

import static edu.learnsphere.testbench.report.Markdown.countOf;
import static edu.learnsphere.testbench.report.Markdown.duration;
import static edu.learnsphere.testbench.report.Markdown.number;
import static edu.learnsphere.testbench.report.Markdown.percent;
import static edu.learnsphere.testbench.report.Markdown.shorten;

/**
 * Writes the testbench report: a summary of every measure against its target, the setup (hardware, software,
 * models, material, ground truth), one section per evaluation with the measured numbers and samples, the
 * definitions of the measures, and per-question results in an appendix.
 */
public final class ReportWriter {

    /**
     * What a report is written from.
     *
     * @param results     one entry per evaluated model; empty when the evaluations were skipped
     * @param skipped     why the AI evaluations did not run, or null when they did
     * @param totalMs     how long the whole testbench took
     */
    public record Inputs(ZonedDateTime started, long totalMs, Environment environment, Material material,
                         EvaluationSet truth, List<ModelRun.Result> results, String skipped) {

        public Inputs {
            Objects.requireNonNull(started, "started");
            Objects.requireNonNull(environment, "environment");
            Objects.requireNonNull(material, "material");
            Objects.requireNonNull(truth, "truth");
            results = List.copyOf(results);
        }
    }

    private static final Map<ItemType, String> TYPE_NAMES = Map.of(
            ItemType.MCQ, "Multiple choice",
            ItemType.TRUE_FALSE, "True or false",
            ItemType.SHORT_ANSWER, "Short answer",
            ItemType.FLASHCARD, "Flashcard",
            ItemType.CLOZE, "Fill in the blank");

    private final Inputs in;
    private final StringBuilder out = new StringBuilder();

    private ReportWriter(Inputs in) {
        this.in = in;
    }

    /** The report as Markdown. */
    public static String write(Inputs inputs) {
        return new ReportWriter(inputs).render();
    }

    private String render() {
        header();
        summary();
        setup();
        if (!in.results().isEmpty()) {
            perModel("Import", this::ingestion);
            perModel("Retrieval", this::retrieval);
            perModel("Answers", this::answers);
            perModel("Item generation", this::generation);
            perModel("Grading short answers", this::grading);
            perModel("Resource use", this::resources);
        }
        method();
        if (!in.results().isEmpty()) {
            perModel("Appendix A: per-question results", this::questionAppendix);
            perModel("Appendix B: every generated item", this::itemAppendix);
        }
        return out.toString();
    }

    // ------------------------------------------------------------------ header and summary

    private void header() {
        Environment env = in.environment();
        out.append("# LearnSphere model testbench report\n\n");
        out.append("The testbench runs LearnSphere's own import, search and study-assistant pipeline on generated course ")
                .append("material with known answers, and measures how well each step works on this computer. ")
                .append("Every number below comes from the app's real services (PDF tools, Lucene index, llama.cpp ")
                .append("servers) on the run described here.\n\n");
        out.append("- Run: ").append(in.started().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm z", Locale.ROOT)))
                .append(", ").append(duration(in.totalMs())).append(" in all\n");
        out.append("- Computer: ").append(env.cpu()).append(", ").append(number(env.memoryBytes() / Math.pow(1024, 3), 0))
                .append(" GiB memory, ").append(env.operatingSystem()).append('\n');
        out.append("- Models evaluated: ").append(in.results().isEmpty() ? "none"
                : in.results().stream().map(r -> r.model().code()).collect(Collectors.joining(", "))).append("\n\n");
        if (in.skipped() != null) {
            out.append("> **The AI evaluations were skipped.** ").append(in.skipped()).append("\n\n");
        }
    }

    private void summary() {
        out.append("## Summary\n\n");
        if (in.results().isEmpty()) {
            Markdown.Table table = new Markdown.Table("Area", "Measure", "Target");
            Targets.unmeasured().forEach(c -> table.row(c.area(), c.measure(), c.target()));
            out.append("No model was evaluated, so these targets were not measured.\n\n").append(table.render());
            return;
        }
        List<String> columns = new ArrayList<>(List.of("Area", "Measure", "Target"));
        for (ModelRun.Result result : in.results()) {
            columns.add(">" + result.model().code());
            columns.add("Met");
        }
        Markdown.Table table = new Markdown.Table(columns.toArray(String[]::new));
        List<List<Targets.Check>> checks = in.results().stream().map(Targets::checks).toList();
        for (int i = 0; i < checks.getFirst().size(); i++) {
            Targets.Check first = checks.getFirst().get(i);
            List<Object> row = new ArrayList<>(List.of(first.area(), first.measure(), first.target()));
            for (List<Targets.Check> model : checks) {
                Targets.Check check = model.get(i);
                row.add(check.value());
                row.add(check.met() == null ? "–" : check.met() ? "yes" : "**no**");
            }
            table.row(row);
        }
        out.append(table.render());
        for (int m = 0; m < in.results().size(); m++) {
            ModelRun.Result result = in.results().get(m);
            long met = checks.get(m).stream().filter(c -> Boolean.TRUE.equals(c.met())).count();
            long measured = checks.get(m).stream().filter(c -> c.met() != null).count();
            out.append("**").append(result.model().code()).append("**: ").append(met).append(" of ").append(measured)
                    .append(" measured targets met; the run took ").append(duration(result.totalMs())).append(".");
            if (!result.complete()) {
                out.append(" The run ended early: ").append(result.failure()).append('.');
            }
            if (!result.leftover().isEmpty()) {
                out.append(" Server processes still running after the run were stopped: ").append(result.leftover())
                        .append('.');
            }
            out.append("\n\n");
        }
    }

    // ------------------------------------------------------------------ setup

    private void setup() {
        Environment env = in.environment();
        out.append("## Setup\n\n### Hardware and software\n\n");
        Markdown.Table system = new Markdown.Table("Item", "Value")
                .row("Processor", env.cpu() + " (" + env.processors() + " cores)")
                .row("Memory", number(env.memoryBytes() / Math.pow(1024, 3), 0) + " GiB (shared by the CPU and GPU)")
                .row("Operating system", env.operatingSystem())
                .row("Java", env.java())
                .row("llama.cpp server", env.llamaVersion().isEmpty() ? "not found" : "version " + env.llamaVersion())
                .row("Server settings", env.serverFlags());
        out.append(system.render());

        out.append("### Models\n\n");
        if (env.models().isEmpty()) {
            out.append("No model files were found").append(env.runtime().map(p -> " in " + p).orElse("")).append(".\n\n");
        } else {
            Markdown.Table models = new Markdown.Table("Role", "File", ">Size", "Name", "Architecture", "Parameters",
                    ">Trained context");
            env.models().forEach(m -> models.row(m.role(), "`" + m.file() + "`", Markdown.bytes(m.bytes()), m.name(),
                    m.architecture(), m.sizeLabel(), m.contextLength()));
            out.append(models.render());
        }

        Material material = in.material();
        out.append("### Course material\n\n")
                .append("The testbench writes its own course PDFs with PDFBox, so the material and its answers are known ")
                .append("exactly and nothing is downloaded. Both chapters have headings, bullet lists, a table, ")
                .append("figures drawn as images (with their captions as text), running headers, footers and page ")
                .append("numbers.\n\n");
        Markdown.Table chapters = new Markdown.Table("Document", ">Pages", ">Words", ">Headings", ">Tables", ">Figures",
                "Text layer");
        for (String key : List.of(Material.OS, Material.RESEARCH_METHODS)) {
            Chapter chapter = material.chapter(key);
            chapters.row(chapter.title() + " (`" + key + "`)", chapter.pages().size(), chapter.wordCount(),
                    chapter.count(Chapter.Heading.class), chapter.count(Chapter.Table.class),
                    chapter.count(Chapter.Figure.class), "yes");
        }
        Chapter source = material.chapter(material.scanSource());
        int scannedWords = material.scannedPages().stream().mapToInt(p -> source.page(p).wordCount()).sum();
        chapters.row("Scanned copy of " + source.title() + " pages " + Markdown.list(material.scannedPages()) + " (`"
                + Material.SCANNED + "`)", material.scannedPages().size(), scannedWords, "", "", "", "no (images only)");
        out.append(chapters.render());
        out.append("The scanned copy imitates an office scan of a photocopy: each page is rendered in greyscale at ")
                .append(ScanSimulator.DPI).append(" DPI, turned by up to ").append(number(ScanSimulator.maxAngleDegrees(), 1))
                .append("°, given paper tone, sensor noise, dust and a shadow along the binding, and stored as a JPEG at ")
                .append("quality ").append(number(ScanSimulator.JPEG_QUALITY, 2)).append(". It has no text layer, so the ")
                .append("app has to read it with the vision model. It is imported into a separate course, so its pages ")
                .append("never compete with the original pages in the main course's retrieval results.\n\n");

        EvaluationSet truth = in.truth();
        long osQuestions = truth.covered().stream().filter(q -> q.document().equals(Material.OS)).count();
        out.append("### Ground truth\n\n")
                .append("- ").append(truth.covered().size()).append(" questions the chapters answer (").append(osQuestions)
                .append(" operating systems, ").append(truth.covered().size() - osQuestions)
                .append(" research methods), each with the page(s) that answer it, a reference answer, key terms a ")
                .append("correct answer mentions, and a plausible wrong answer.\n")
                .append("- ").append(truth.scanned().size()).append(" questions answered by the scanned copy.\n")
                .append("- ").append(truth.uncovered().size()).append(" questions on the same subjects that the material ")
                .append("does not answer.\n")
                .append("- ").append(truth.shortAnswers().size()).append(" short-answer questions with a model answer and ")
                .append(truth.responseCount()).append(" student answers graded by a teacher (one correct, one partly ")
                .append("correct and one wrong per question).\n\n")
                .append("Before any evaluation the testbench checks the ground truth against the generated PDFs: every ")
                .append("question's evidence phrase must appear on one of its answering pages in the text the PDF tools ")
                .append("extract.\n\n");
    }

    // ------------------------------------------------------------------ per-model sections

    private void perModel(String title, Consumer<ModelRun.Result> section) {
        out.append("## ").append(title).append("\n\n");
        for (ModelRun.Result result : in.results()) {
            if (in.results().size() > 1) {
                out.append("### ").append(result.model().code()).append("\n\n");
            }
            section.accept(result);
        }
    }

    private void notMeasured() {
        out.append("Not measured: the run ended before this evaluation.\n\n");
    }

    private void ingestion(ModelRun.Result result) {
        IngestionEval.Result ingestion = result.ingestion();
        if (ingestion == null) {
            notMeasured();
            return;
        }
        out.append("Each PDF was imported through the app's document service and timed from the moment the file was ")
                .append("handed over until it was ready to answer questions (for the scanned copy, without the time it ")
                .append("waited for review).\n\n");
        Markdown.Table table = new Markdown.Table("Document", ">Pages", ">Import time", ">Per page", ">Passages",
                ">Passages per page", ">Words per passage (mean / max)", ">Pages read by AI", ">Figure pages described",
                ">Embedding time");
        for (IngestionEval.DocumentResult d : ingestion.documents()) {
            table.row(d.title() + " (`" + d.key() + "`)", d.pages(), duration(d.wallMs()),
                    number(d.secondsPerPage(), 2) + " s", d.chunks(), number(d.chunksPerPage(), 2),
                    number(d.meanChunkWords(), 0) + " / " + d.maxChunkWords(), d.pagesRead(), d.figurePages(),
                    duration(d.embedMs()));
        }
        out.append(table.render());
        ingestion.documents().stream().filter(d -> d.figurePages() > 0 || d.pagesRead() > 0).forEach(d -> {
            out.append("- ").append(d.title()).append(": ");
            List<String> parts = new ArrayList<>();
            if (d.pagesRead() > 0) {
                parts.add("reading " + d.pagesRead() + " page(s) took " + duration(d.readMs()));
            }
            if (d.figurePages() > 0) {
                parts.add("describing figures on " + d.figurePages() + " page(s) took " + duration(d.figureMs()) + " ("
                        + d.figureNotes() + " notes)");
            }
            out.append(String.join("; ", parts)).append(".\n");
        });
        out.append('\n');

        out.append("**Scanned pages.** The import stopped with ").append(ingestion.pagesWaitingForReview())
                .append(" page(s) waiting for review, as the app does for every page it reads with the vision model. ")
                .append("Each transcription was scored against the authored text of the page it shows (Markdown marks ")
                .append("and spacing removed first), then every page was approved and the import finished.\n\n");
        Markdown.Table scans = new Markdown.Table(">Scanned page", ">Shows page", ">Characters right", ">Words right",
                ">Reading time", ">Parts marked unreadable");
        ingestion.scanned().forEach(p -> scans.row(p.page(), p.sourcePage(), percent(p.characterAccuracy()),
                percent(p.wordAccuracy()), duration(p.readMs()), p.illegibleSpans()));
        scans.row("**All**", "", "**" + percent(ingestion.scannedCharacterAccuracy()) + "**",
                "**" + percent(ingestion.scannedWordAccuracy()) + "**", "", "");
        out.append(scans.render());
        ingestion.scanned().stream().min(Comparator.comparingDouble(IngestionEval.ScannedPage::characterAccuracy))
                .ifPresent(worst -> out.append("Start of the least accurate transcription (scanned page ")
                        .append(worst.page()).append("):\n\n> ").append(shorten(worst.transcription(), 600)).append("\n\n"));

        if (!ingestion.figures().isEmpty()) {
            out.append("**Figure notes** written by the vision model and added to the passages:\n\n");
            ingestion.figures().forEach(f -> out.append("- `").append(f.document()).append("` page ").append(f.page())
                    .append(": ").append(shorten(String.join(" / ", f.notes()), 400)).append('\n'));
            out.append('\n');
        }
    }

    private void retrieval(ModelRun.Result result) {
        RetrievalEval.Result retrieval = result.retrieval();
        if (retrieval == null) {
            notMeasured();
            return;
        }
        out.append("Each of the ").append(retrieval.mainQuestions()).append(" questions about the chapters was searched ")
                .append("in the main course's index four ways. A passage counts as relevant when its page range includes ")
                .append("an answering page. Each list holds up to 24 passages (40 candidates per search before fusion), ")
                .append("as in the app.\n\n");
        Markdown.Table table = new Markdown.Table("Ranking", ">Recall@1", ">Recall@5", ">MRR", ">Found in the 24");
        retrieval.main().forEach(s -> table.row(s.mode() == RetrievalEval.Mode.HYBRID_RERANKED
                        ? "**" + s.mode().label() + "**" : s.mode().label(), number(s.recallAt1(), 3),
                number(s.recallAt5(), 3), number(s.mrr(), 3), number(s.recallAtLimit(), 3)));
        out.append(table.render());
        retrieval.scanned().stream().filter(s -> s.mode() == RetrievalEval.Mode.HYBRID_RERANKED).findFirst()
                .ifPresent(s -> out.append("On the scanned copy (").append(retrieval.scannedQuestions())
                        .append(" questions, searched in its own course) the app's ranking has recall@5 ")
                        .append(number(s.recallAt5(), 3)).append(" and MRR ").append(number(s.mrr(), 3))
                        .append(", so passages built from transcribed pages are found like any other.\n\n"));
        out.append("Median time per question: embedding the question ").append(duration(retrieval.medianEmbedMs()))
                .append(", hybrid search ").append(duration(retrieval.medianSearchMs())).append(", reranking the ")
                .append("candidates ").append(duration(retrieval.medianRerankMs())).append(".\n\n");
        List<RetrievalEval.QuestionRow> missed = retrieval.questions().stream()
                .filter(q -> q.covered() && !q.document().equals(Material.SCANNED))
                .filter(q -> {
                    int rank = q.firstRelevant().getOrDefault(RetrievalEval.Mode.HYBRID_RERANKED, 0);
                    return rank == 0 || rank > 5;
                }).toList();
        if (missed.isEmpty()) {
            out.append("Every question had an answering passage in the reranked top five.\n\n");
        } else {
            out.append("Questions without an answering passage in the reranked top five: ");
            out.append(missed.stream().map(q -> {
                int rank = q.firstRelevant().getOrDefault(RetrievalEval.Mode.HYBRID_RERANKED, 0);
                return q.id() + " (" + (rank == 0 ? "not in the 24" : "rank " + rank) + ")";
            }).collect(Collectors.joining(", "))).append(".\n\n");
        }

        out.append("**Evidence threshold.** The app gives the answerer only passages whose reranker score reaches the ")
                .append("evidence threshold (setting: ").append(number(retrieval.currentThreshold(), 2)).append("); with ")
                .append("none, the learner gets the insufficient-evidence card without the answerer being asked. The ")
                .append("sweep shows, for each threshold, the covered questions that keep a relevant passage and the ")
                .append("uncovered questions left with no passage at all.\n\n");
        Markdown.Table sweep = new Markdown.Table(">Threshold", ">Covered questions keeping a relevant passage",
                ">Uncovered questions with no passage", ">Mean of the two");
        for (RetrievalEval.ThresholdRow row : retrieval.sweep()) {
            boolean marked = Math.abs(row.threshold() - retrieval.currentThreshold()) < 0.026
                    || Math.abs(row.threshold() - retrieval.recommendedThreshold()) < 1e-9;
            if (Math.round(row.threshold() * 100) % 10 == 0 || marked) {
                String label = number(row.threshold(), 2);
                sweep.row(marked ? "**" + label + "**" : label, percent(row.coveredKept()), percent(row.uncoveredRefused()),
                        percent(row.balanced()));
            }
        }
        out.append(sweep.render());
        RetrievalEval.ThresholdRow current = retrieval.at(retrieval.currentThreshold());
        out.append("At the current setting, ").append(percent(current.coveredKept()))
                .append(" of covered questions keep a relevant passage and ").append(percent(current.uncoveredRefused()))
                .append(" of uncovered questions are stopped before the answerer. The best balance on this material ")
                .append("is at ").append(number(retrieval.recommendedThreshold(), 2))
                .append(Math.abs(retrieval.recommendedThreshold() - retrieval.currentThreshold()) < 1e-9
                        ? ", the current setting" : "")
                .append(". Uncovered questions that pass this filter can still be refused by the answerer (see Answers).\n\n");
    }

    private void answers(ModelRun.Result result) {
        AnswerEval.Result answers = result.answers();
        if (answers == null) {
            notMeasured();
            return;
        }
        out.append("Every question was asked through the app's ask service in a new conversation, exactly as a learner ")
                .append("would, and the reply was scored.\n\n");
        Markdown.Table outcomes = new Markdown.Table("Questions", ">Asked", ">Answered with citations",
                ">Insufficient-evidence card", ">Error");
        for (String kind : List.of("covered", "scanned", "uncovered")) {
            List<AnswerEval.Row> rows = answers.rows().stream().filter(r -> r.kind().equals(kind)).toList();
            String label = switch (kind) {
                case "covered" -> "Covered by the chapters";
                case "scanned" -> "Covered by the scanned copy";
                default -> "Not covered";
            };
            outcomes.row(label, rows.size(), count(rows, AnswerEval.Outcome.ANSWERED), count(rows, AnswerEval.Outcome.NOT_COVERED),
                    count(rows, AnswerEval.Outcome.ERROR));
        }
        out.append(outcomes.render());
        if (answers.rows().stream().anyMatch(r -> r.outcome() == AnswerEval.Outcome.NOT_COVERED)) {
            out.append("Where the insufficient-evidence card was decided: ");
            List<String> parts = new ArrayList<>();
            for (String kind : List.of("uncovered", "covered")) {
                Map<String, Long> byStage = answers.rows().stream()
                        .filter(r -> r.outcome() == AnswerEval.Outcome.NOT_COVERED)
                        .filter(r -> kind.equals("uncovered") == r.kind().equals("uncovered"))
                        .collect(Collectors.groupingBy(AnswerEval.Row::refusal, LinkedHashMap::new, Collectors.counting()));
                if (!byStage.isEmpty()) {
                    parts.add(kind + " questions: " + byStage.entrySet().stream()
                            .map(e -> e.getValue() + " by " + stageName(e.getKey())).collect(Collectors.joining(", ")));
                }
            }
            out.append(String.join("; ", parts)).append(".\n\n");
        }
        Markdown.Table quality = new Markdown.Table("Measure", ">Result")
                .row("Citation validity (markers naming a passage the answerer was given)",
                        percent(answers.citationValidity()) + " of " + answers.markerCount())
                .row("Citation precision (citations on an answering page)", percent(answers.citationPrecision()))
                .row("Answers citing at least one answering page", percent(answers.groundedShare()))
                .row("Key terms present in answers (mean)", percent(answers.keyTermCoverage()))
                .row("Refusal rate, uncovered questions", percent(answers.refusalRate()))
                .row("False-refusal rate, covered questions", percent(answers.falseRefusalRate()));
        out.append(quality.render());
        List<Long> first = answers.firstTokenTimes();
        List<Long> total = answers.totalTimes();
        Markdown.Table latency = new Markdown.Table("Wait", ">Median", ">90th percentile", ">Longest", ">Questions");
        latency.row("Until the first words", duration(Stats.median(first)), duration(Stats.percentile(first, 90)),
                duration(Stats.max(first)), first.size());
        latency.row("Until the full answer or card", duration(Stats.median(total)), duration(Stats.percentile(total, 90)),
                duration(Stats.max(total)), total.size());
        out.append(latency.render());
        List<ChatLog.Completion> chats = answers.completions();
        if (!chats.isEmpty()) {
            out.append("Answer writing: median ").append(number(Stats.median(chats.stream().map(ChatLog.Completion::promptTokens)
                            .toList()), 0)).append(" prompt tokens read at ")
                    .append(number(Stats.median(chats.stream().map(ChatLog.Completion::promptRate).toList()), 0))
                    .append(" tokens/s, median ").append(number(Stats.median(chats.stream()
                            .map(ChatLog.Completion::completionTokens).toList()), 0)).append(" tokens written at ")
                    .append(number(Stats.median(chats.stream().map(ChatLog.Completion::generationRate).toList()), 1))
                    .append(" tokens/s.\n\n");
        }
        out.append("Sample answers:\n\n");
        answers.answered().stream().limit(2).forEach(r -> quote(r.id() + " — " + r.question(), r.text()));
        answers.uncovered().stream().filter(r -> r.outcome() == AnswerEval.Outcome.ANSWERED).findFirst()
                .ifPresent(r -> quote(r.id() + " (not covered, but answered) — " + r.question(), r.text()));
        answers.uncovered().stream().filter(r -> r.outcome() == AnswerEval.Outcome.NOT_COVERED).findFirst()
                .ifPresent(r -> quote(r.id() + " (not covered) — " + r.question(), r.text()));
    }

    private static long count(List<AnswerEval.Row> rows, AnswerEval.Outcome outcome) {
        return rows.stream().filter(r -> r.outcome() == outcome).count();
    }

    private static String stageName(String stage) {
        return switch (stage) {
            case "retrieval" -> "the evidence threshold";
            case "answerer" -> "the answerer";
            case "no citation" -> "a missing citation";
            default -> stage;
        };
    }

    private void quote(String title, String text) {
        out.append("**").append(Markdown.cell(title)).append("**\n\n> ")
                .append(text.strip().replace("\n\n", "\n>\n> ").replace("\n", "\n> ")).append("\n\n");
    }

    private void generation(ModelRun.Result result) {
        GenerationEval.Result g = result.generation();
        if (g == null) {
            notMeasured();
            return;
        }
        out.append("Questions were written through the app's generation jobs: concept extraction for each module, ")
                .append("then item generation for each module with every item type allowed.\n\n");
        out.append("- Concepts extracted: ").append(g.concepts().size()).append(" in ").append(duration(g.conceptMs()))
                .append(" (").append(shorten(String.join(", ", g.concepts()), 500)).append(").\n");
        out.append("- Items asked for: ").append(g.requested()).append("; drafts written by the model: ")
                .append(g.draftsWritten()).append("; stored: ").append(g.stored()).append("; dropped by the app as ")
                .append("near-duplicates: ").append(g.duplicatesDropped()).append(".\n");
        out.append("- Generation time: ").append(duration(g.generationMs())).append(" (")
                .append(number(g.stored() == 0 ? 0 : g.generationMs() / 1000.0 / g.stored(), 1))
                .append(" s per stored item, including support checks).\n");
        out.append("- JSON replies (concepts, items, support checks): ").append(g.structuredReplies()).append(", of which ")
                .append(g.invalidReplies()).append(" did not parse (").append(percent(g.jsonValidity())).append(" valid).\n\n");

        Markdown.Table types = new Markdown.Table("Item type", ">Stored", ">Breaking a type rule");
        Map<ItemType, Long> byType = g.byType();
        for (ItemType type : ItemType.values()) {
            long violations = g.items().stream().filter(i -> i.type() == type && i.ruleViolation().isPresent()).count();
            types.row(TYPE_NAMES.get(type), byType.getOrDefault(type, 0L), violations);
        }
        out.append(types.render());
        g.items().stream().filter(i -> i.ruleViolation().isPresent()).forEach(i -> out.append("- ")
                .append(TYPE_NAMES.get(i.type())).append(": ").append(i.ruleViolation().get()).append(" — \"")
                .append(shorten(i.prompt(), 120)).append("\"\n"));
        Map<SupportStatus, Long> support = g.bySupport();
        out.append("Support check of the stored items: ").append(support.getOrDefault(SupportStatus.SUPPORTED, 0L))
                .append(" supported, ").append(support.getOrDefault(SupportStatus.UNSUPPORTED, 0L))
                .append(" not supported (kept as drafts and marked for review), ")
                .append(support.getOrDefault(SupportStatus.UNCHECKED, 0L)).append(" not checked.\n\n");
        if (!g.droppedReasons().isEmpty()) {
            out.append("Drafts the model wrote that the app dropped before storing:\n\n");
            Markdown.Table dropped = new Markdown.Table("Reason", ">Drafts");
            g.droppedReasons().entrySet().stream().sorted(Map.Entry.<String, Long>comparingByValue().reversed())
                    .forEach(e -> dropped.row(e.getKey(), e.getValue()));
            out.append(dropped.render());
        }
        out.append("**Near-duplicates.** Stored items whose prompt and answer share at least ")
                .append(percent(GenerationEval.DUPLICATE_OVERLAP)).append(" of their words: ")
                .append(g.duplicates().size()).append(" pair(s), ").append(percent(g.duplicateRate()))
                .append(" of items.\n");
        g.duplicates().forEach(d -> out.append("- \"").append(shorten(d.first(), 100)).append("\" / \"")
                .append(shorten(d.second(), 100)).append("\" (").append(percent(d.overlap())).append(")\n"));
        out.append('\n');

        out.append("**Support checker against labels.** For each covered question with a relevant passage, the checker ")
                .append("judged the reference answer (expected: supported) and the plausible wrong answer (expected: not ")
                .append("supported) against that passage.\n\n");
        Markdown.Table pairs = new Markdown.Table("Measure", ">Result")
                .row("Agreement with the labels", countOf(g.supportPairs().stream().filter(GenerationEval.SupportPair::agrees)
                        .count(), g.supportPairs().size()))
                .row("Reference answers judged supported", percent(g.supportTruePositiveRate()))
                .row("Wrong answers judged not supported", percent(g.supportTrueNegativeRate()))
                .row("Median time per check", duration(Stats.median(g.supportPairs().stream()
                        .map(GenerationEval.SupportPair::millis).toList())));
        out.append(pairs.render());
        if (g.unlabelledPairs() > 0) {
            out.append(g.unlabelledPairs()).append(" question(s) had no relevant passage among the candidates and were ")
                    .append("left out.\n\n");
        }
        List<GenerationEval.SupportPair> disagreements = g.supportPairs().stream().filter(p -> !p.agrees()).toList();
        if (!disagreements.isEmpty()) {
            out.append("Disagreements: ");
            out.append(disagreements.stream().map(p -> p.id() + " (judged " + (p.supported() ? "supported" : "not supported")
                    + (p.note().isBlank() ? "" : ": " + shorten(p.note(), 120)) + ")").collect(Collectors.joining("; ")));
            out.append(".\n\n");
        }

        out.append("**Sample items** (up to two of each type):\n\n");
        for (ItemType type : ItemType.values()) {
            g.items().stream().filter(i -> i.type() == type)
                    .sorted(Comparator.comparing((GenerationEval.StoredItem i) -> i.support() != SupportStatus.SUPPORTED))
                    .limit(2).forEach(this::sampleItem);
        }
    }

    private void sampleItem(GenerationEval.StoredItem item) {
        out.append("- **").append(TYPE_NAMES.get(item.type())).append("** (")
                .append(item.document() == null ? "no source" : item.document())
                .append(item.page() == null ? "" : ", p. " + item.page()).append(", ")
                .append(item.support().name().toLowerCase(Locale.ROOT)).append(")\n")
                .append("  - Prompt: ").append(Markdown.cell(item.prompt())).append('\n')
                .append("  - Answer: ").append(Markdown.cell(item.answer())).append('\n');
        if (!item.distractors().isEmpty()) {
            out.append("  - Wrong options: ").append(Markdown.cell(String.join("; ", item.distractors()))).append('\n');
        }
        if (!item.explanation().isBlank()) {
            out.append("  - Explanation: ").append(Markdown.cell(shorten(item.explanation(), 300))).append('\n');
        }
    }

    private void grading(ModelRun.Result result) {
        GradingEval.Result grading = result.grading();
        if (grading == null) {
            notMeasured();
            return;
        }
        out.append("Each student answer was graded by the app's short-answer grader with the question, the model answer ")
                .append("and the best passage from the answering page, as the app does when a learner answers a ")
                .append("short-answer item.\n\n");
        out.append("- Verdicts matching the teacher: ").append(countOf(grading.rows().stream().filter(GradingEval.Row::agrees)
                .count(), grading.rows().size())).append('\n');
        out.append("- Verdicts at most one step away (correct / partly correct / wrong): ")
                .append(percent(grading.withinOneStep())).append('\n');
        out.append("- Median grading time: ").append(duration(Stats.median(grading.rows().stream()
                .map(GradingEval.Row::millis).toList()))).append('\n');
        out.append("- JSON replies: ").append(grading.structuredReplies()).append(", of which ")
                .append(grading.invalidReplies()).append(" did not parse\n\n");
        Markdown.Table confusion = new Markdown.Table("Teacher's verdict", ">Graded correct", ">Graded partly correct",
                ">Graded wrong", ">Mean score");
        Map<GradeResult.Verdict, Map<GradeResult.Verdict, Long>> matrix = grading.confusion();
        for (GradeResult.Verdict expected : GradeResult.Verdict.values()) {
            Map<GradeResult.Verdict, Long> row = matrix.get(expected);
            confusion.row(verdictName(expected), row.get(GradeResult.Verdict.CORRECT), row.get(GradeResult.Verdict.PARTIAL),
                    row.get(GradeResult.Verdict.INCORRECT), number(grading.meanScore().get(expected), 2));
        }
        out.append(confusion.render());
        Markdown.Table rows = new Markdown.Table("Question", "Answer", "Teacher", "Grader", ">Score", "Feedback");
        grading.rows().forEach(r -> rows.row(r.id(), r.label(), verdictName(r.expected()),
                r.agrees() ? verdictName(r.verdict()) : "**" + verdictName(r.verdict()) + "**", number(r.score(), 2),
                shorten(r.feedback(), 160)));
        out.append(rows.render());
    }

    private static String verdictName(GradeResult.Verdict verdict) {
        return switch (verdict) {
            case CORRECT -> "correct";
            case PARTIAL -> "partly correct";
            case INCORRECT -> "wrong";
        };
    }

    private void resources(ModelRun.Result result) {
        MemorySampler.Peaks memory = result.memory();
        ModelRun.LoadTimes load = result.load();
        out.append("Memory was sampled from the llama-server processes every half second (resident size) and every two ")
                .append("seconds (physical footprint, which includes the GPU buffers Metal allocates in shared memory). ")
                .append("Buffer sizes are what llama.cpp reported allocating when it loaded each model.\n\n");
        Markdown.Table servers = new Markdown.Table("Server", ">Ready after start", ">Peak resident", ">Peak footprint",
                ">Model weights", ">Context cache", ">Compute buffers");
        for (ServerRole role : ServerRole.values()) {
            ServerLogs.Buffers buffers = result.buffers().getOrDefault(role, new ServerLogs.Buffers(Map.of(), Map.of()));
            Long ready = load == null ? null : load.readyMs().get(role);
            double cache = buffers.byKind().getOrDefault("KV", 0.0) + buffers.byKind().getOrDefault("RS", 0.0);
            servers.row(role.label(), ready == null ? "–" : duration(ready),
                    memory == null || !memory.rssByRole().containsKey(role) ? "–" : Markdown.bytes(memory.rssByRole().get(role)),
                    memory == null || !memory.footprintByRole().containsKey(role) ? "–"
                            : Markdown.bytes(memory.footprintByRole().get(role)),
                    buffers.isEmpty() ? "–" : Markdown.mebibytes(buffers.byKind().getOrDefault("model", 0.0)),
                    buffers.isEmpty() ? "–" : Markdown.mebibytes(cache),
                    buffers.isEmpty() ? "–" : Markdown.mebibytes(buffers.byKind().getOrDefault("compute", 0.0)));
        }
        out.append(servers.render());
        if (memory != null && memory.samples() > 0) {
            out.append("All servers together peaked at ").append(Markdown.bytes(memory.totalRss())).append(" resident");
            if (memory.hasFootprint()) {
                out.append(" and ").append(Markdown.bytes(memory.totalFootprint())).append(" footprint");
            }
            out.append(" (").append(memory.samples()).append(" samples). The servers load in parallel; all were ready ")
                    .append(load == null ? "–" : "after " + duration(load.allMs())).append(".\n\n");
            if (!memory.footprintByPhase().isEmpty()) {
                Markdown.Table phases = new Markdown.Table("Phase", ">Peak, all servers");
                memory.footprintByPhase().forEach((phase, bytes) -> phases.row(phase, Markdown.bytes(bytes)));
                out.append(phases.render());
            }
        }
        List<ChatLog.Completion> chats = result.completions();
        if (!chats.isEmpty()) {
            out.append("Chat throughput over all ").append(chats.size()).append(" chat requests of the run: median ")
                    .append(number(Stats.median(chats.stream().map(ChatLog.Completion::generationRate)
                            .filter(rate -> rate > 0).toList()), 1)).append(" tokens/s written, median ")
                    .append(number(Stats.median(chats.stream().map(ChatLog.Completion::promptRate)
                            .filter(rate -> rate > 0).toList()), 0)).append(" prompt tokens/s read; ")
                    .append(chats.stream().mapToLong(ChatLog.Completion::promptTokens).sum()).append(" prompt tokens and ")
                    .append(chats.stream().mapToLong(ChatLog.Completion::completionTokens).sum())
                    .append(" written tokens in all.\n\n");
        }
    }

    // ------------------------------------------------------------------ method and appendices

    private void method() {
        out.append("## How the measures are defined\n\n")
                .append("- **Time per text page**: import wall time of the two chapters divided by their pages. It covers ")
                .append("text extraction, figure description, splitting into passages, embedding and indexing.\n")
                .append("- **Characters right / words right**: 1 − (edit distance ÷ length of the reference), on characters ")
                .append("or on words, after removing Markdown marks and collapsing spaces in both texts. The reference ")
                .append("is the authored text of the page without running header, footer and page number.\n")
                .append("- **Recall@k**: the share of questions with at least one relevant passage among the first k. ")
                .append("**MRR**: the mean of 1 ÷ rank of the first relevant passage (0 when there is none in the list).\n")
                .append("- **Citation validity**: of the citation markers ([1], [2, 3], [4-6]) in the answers as the model ")
                .append("wrote them, the share naming a passage it was given. **Citation precision**: of the citations ")
                .append("the app kept in answered questions, the share whose page range includes an answering page.\n")
                .append("- **Refusal rate**: uncovered questions that got the insufficient-evidence card. **False-refusal ")
                .append("rate**: covered questions (chapters and scanned copy) that did not get an answer with citations.\n")
                .append("- **Time to first words**: from asking until the first streamed text arrived, including search ")
                .append("and reranking; questions stopped by the evidence threshold stream nothing and are left out.\n")
                .append("- **Valid JSON replies**: chat replies that were one complete JSON object, over every reply the ")
                .append("concept extractor, item generator and support checker received (a reply that does not parse ")
                .append("is asked for again, and both attempts count).\n")
                .append("- **Type rules**: multiple choice has three distinct wrong options different from the answer; ")
                .append("true-or-false items are statements answered true or false; fill-in-the-blank prompts have one ")
                .append("blank and do not contain the answer; no prompt refers to \"the passage\" or \"the text\".\n")
                .append("- **Near-duplicate items**: pairs of stored items whose prompt and answer share at least ")
                .append(percent(GenerationEval.DUPLICATE_OVERLAP)).append(" of their words (Jaccard overlap); the rate ")
                .append("is the share of items in such a pair. The app also drops drafts whose embedding is very close ")
                .append("to an existing item before storing them; those are counted separately.\n")
                .append("- **Peak memory**: the highest sum over the three servers, sampled during the run.\n\n")
                .append("Limits: the material is two generated chapters and the ground truth is small (")
                .append(in.truth().covered().size()).append(" + ").append(in.truth().scanned().size()).append(" + ")
                .append(in.truth().uncovered().size()).append(" questions, ").append(in.truth().responseCount())
                .append(" graded answers), so one question moves a rate by several points. Timings depend on what else ")
                .append("the computer was doing during the run.\n\n");
    }

    private void questionAppendix(ModelRun.Result result) {
        RetrievalEval.Result retrieval = result.retrieval();
        AnswerEval.Result answers = result.answers();
        if (retrieval == null && answers == null) {
            notMeasured();
            return;
        }
        out.append("Rank of the first relevant passage per ranking (– when none was in the 24), the reranker's top ")
                .append("score, and what the learner got.\n\n");
        Markdown.Table table = new Markdown.Table("Question", "Pages", ">Keyword", ">Vector", ">Hybrid", ">Reranked",
                ">Top score", "Reply", ">Citations on page", ">Key terms", ">First words", ">Full reply");
        Map<String, RetrievalEval.QuestionRow> byId = retrieval == null ? Map.of()
                : retrieval.questions().stream().collect(Collectors.toMap(RetrievalEval.QuestionRow::id, q -> q));
        List<String> ids = answers != null ? answers.rows().stream().map(AnswerEval.Row::id).toList()
                : retrieval.questions().stream().map(RetrievalEval.QuestionRow::id).toList();
        for (String id : ids) {
            RetrievalEval.QuestionRow q = byId.get(id);
            AnswerEval.Row a = answers == null ? null
                    : answers.rows().stream().filter(r -> r.id().equals(id)).findFirst().orElse(null);
            List<Object> row = new ArrayList<>();
            row.add(id);
            row.add(q == null || q.pages().isEmpty() ? "–" : q.document() + " " + Markdown.list(q.pages()));
            for (RetrievalEval.Mode mode : RetrievalEval.Mode.values()) {
                row.add(q == null || !q.covered() ? "–" : rank(q.firstRelevant().getOrDefault(mode, 0)));
            }
            row.add(q == null ? "–" : number(q.topScore(), 3));
            if (a == null) {
                row.addAll(List.of("–", "–", "–", "–", "–"));
            } else {
                row.add(switch (a.outcome()) {
                    case ANSWERED -> "answered";
                    case NOT_COVERED -> "card (" + stageName(a.refusal()) + ")";
                    case ERROR -> "error";
                });
                row.add(a.outcome() == AnswerEval.Outcome.ANSWERED ? a.onTruePages() + " of " + a.citations() : "–");
                row.add(a.outcome() == AnswerEval.Outcome.ANSWERED && !a.kind().equals("uncovered")
                        ? percent(a.keyTermCoverage()) : "–");
                row.add(a.firstTokenMs() < 0 ? "–" : duration(a.firstTokenMs()));
                row.add(duration(a.totalMs()));
            }
            table.row(row);
        }
        out.append(table.render());
    }

    private static String rank(int rank) {
        return rank == 0 ? "–" : String.valueOf(rank);
    }

    private void itemAppendix(ModelRun.Result result) {
        GenerationEval.Result g = result.generation();
        if (g == null) {
            notMeasured();
            return;
        }
        Markdown.Table table = new Markdown.Table("Type", "Prompt", "Answer", "Source", "Support", "Rule");
        g.items().forEach(i -> table.row(TYPE_NAMES.get(i.type()), shorten(i.prompt(), 160), shorten(i.answer(), 100),
                (i.document() == null ? "" : i.document()) + (i.page() == null ? "" : " p. " + i.page()),
                i.support().name().toLowerCase(Locale.ROOT), i.ruleViolation().orElse("ok")));
        out.append(table.render());
    }
}
