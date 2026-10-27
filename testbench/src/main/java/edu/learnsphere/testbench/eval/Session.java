package edu.learnsphere.testbench.eval;

import edu.learnsphere.core.model.JobStatus;
import edu.learnsphere.core.service.DocumentSummary;
import edu.learnsphere.core.service.JobUpdate;
import edu.learnsphere.core.service.Workspace;
import edu.learnsphere.core.spi.Capabilities;
import edu.learnsphere.testbench.material.Material;
import edu.learnsphere.testbench.probe.ChatLog;
import edu.learnsphere.testbench.probe.Recorder;
import edu.learnsphere.testbench.truth.EvaluationSet;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.Predicate;

/**
 * Everything one model's evaluations share: the opened workspace (the app's real services), the instrumented
 * capabilities it runs on, the recorders, the material and ground truth, and the courses and documents the
 * ingestion evaluation created.
 *
 * <p>Two courses are used: the <em>main</em> course holds the operating-systems chapter and the
 * research-methods chapter (one module each); the <em>scanned</em> course holds only the scanned copy, so its
 * pages, which repeat research-methods pages, never compete with the originals in the main course's retrieval
 * ground truth.
 */
public final class Session {

    private static final Duration POLL = Duration.ofMillis(200);

    private final Workspace workspace;
    private final Capabilities capabilities;
    private final Recorder recorder;
    private final ChatLog chatLog;
    private final Material material;
    private final EvaluationSet truth;
    private final Map<String, String> documentIds = new HashMap<>();
    private final Map<String, String> moduleIds = new HashMap<>();
    private String mainCourseId;
    private String scanCourseId;

    Session(Workspace workspace, Capabilities capabilities, Recorder recorder, ChatLog chatLog, Material material,
            EvaluationSet truth) {
        this.workspace = Objects.requireNonNull(workspace, "workspace");
        this.capabilities = Objects.requireNonNull(capabilities, "capabilities");
        this.recorder = Objects.requireNonNull(recorder, "recorder");
        this.chatLog = Objects.requireNonNull(chatLog, "chatLog");
        this.material = Objects.requireNonNull(material, "material");
        this.truth = Objects.requireNonNull(truth, "truth");
    }

    public Workspace workspace() {
        return workspace;
    }

    /** The capabilities the workspace runs on (calls through them are recorded too). */
    public Capabilities capabilities() {
        return capabilities;
    }

    public Recorder recorder() {
        return recorder;
    }

    public ChatLog chatLog() {
        return chatLog;
    }

    public Material material() {
        return material;
    }

    public EvaluationSet truth() {
        return truth;
    }

    /** Tags what runs from now on (recorded calls, chat statistics and memory peaks). */
    public void phase(String name) {
        recorder.label(name);
    }

    /** The course holding the two chapters. */
    public String mainCourseId() {
        return Objects.requireNonNull(mainCourseId, "The main course has not been created");
    }

    /** The course holding the scanned copy. */
    public String scanCourseId() {
        return Objects.requireNonNull(scanCourseId, "The scanned course has not been created");
    }

    /** The course a ground-truth document ({@code os}, {@code rm} or {@code scan}) was imported into. */
    public String courseOf(String document) {
        return document.equals(Material.SCANNED) ? scanCourseId() : mainCourseId();
    }

    /** The id of an imported ground-truth document ({@code os}, {@code rm} or {@code scan}). */
    public String documentId(String document) {
        String id = documentIds.get(document);
        if (id == null) {
            throw new IllegalStateException("Document " + document + " has not been imported");
        }
        return id;
    }

    /** The ground-truth document key ({@code os}, {@code rm}, {@code scan}) of an imported document id. */
    public String documentKey(String documentId) {
        return documentIds.entrySet().stream().filter(e -> e.getValue().equals(documentId)).map(Map.Entry::getKey)
                .findFirst().orElse("?");
    }

    /** The module a ground-truth document was imported into. */
    public String moduleId(String document) {
        String id = moduleIds.get(document);
        if (id == null) {
            throw new IllegalStateException("No module for " + document);
        }
        return id;
    }

    void courses(String main, String scan) {
        this.mainCourseId = main;
        this.scanCourseId = scan;
    }

    void module(String document, String moduleId) {
        moduleIds.put(document, moduleId);
    }

    void document(String document, String documentId) {
        documentIds.put(document, documentId);
    }

    /** Waits until the document matches {@code done}, or fails after {@code timeout}. */
    public DocumentSummary awaitDocument(String documentId, Predicate<DocumentSummary> done, Duration timeout)
            throws InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (true) {
            DocumentSummary summary = workspace.documents().get(documentId);
            if (done.test(summary)) {
                return summary;
            }
            if (summary.status() == edu.learnsphere.core.model.DocumentStatus.FAILED) {
                throw new IllegalStateException("Import of " + summary.title() + " failed: "
                        + (summary.error() == null ? "no reason given" : summary.error().text()));
            }
            if (System.nanoTime() > deadline) {
                throw new IllegalStateException("Import of " + summary.title() + " did not finish within " + timeout
                        + " (status " + summary.status() + ")");
            }
            Thread.sleep(POLL.toMillis());
        }
    }

    /** Waits until the job is no longer queued or running, or fails after {@code timeout}. */
    public JobUpdate awaitJob(String jobId, Duration timeout) throws InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (true) {
            JobUpdate job = workspace.jobs().find(jobId)
                    .orElseThrow(() -> new IllegalStateException("Job " + jobId + " disappeared"));
            if (!job.isActive()) {
                if (job.status() != JobStatus.DONE) {
                    throw new IllegalStateException("Job " + job.kind() + " ended " + job.status() + ": " + job.message());
                }
                return job;
            }
            if (System.nanoTime() > deadline) {
                throw new IllegalStateException("Job " + job.kind() + " did not finish within " + timeout);
            }
            Thread.sleep(POLL.toMillis());
        }
    }
}
