package edu.learnsphere.app.testing;

import edu.learnsphere.ai.fake.FakeAi;
import edu.learnsphere.app.AppContext;
import edu.learnsphere.core.AppPaths;
import edu.learnsphere.core.service.DocumentSummary;
import edu.learnsphere.core.model.DocumentStatus;
import edu.learnsphere.core.service.ProfileDraft;
import edu.learnsphere.core.service.ProfileSummary;
import edu.learnsphere.core.service.Workspace;
import edu.learnsphere.core.service.impl.WorkspaceImpl;
import edu.learnsphere.pdf.testing.TestPdf;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import javafx.application.Platform;

/**
 * An app over the real services for UI tests: a temporary app home, real SQLite, the pdf module, Lucene search
 * and the deterministic {@link FakeAi} instead of the study assistant's processes.
 *
 * <pre>{@code
 * @TempDir Path dir;
 * RealWorkspaces real = RealWorkspaces.open(dir);          // one profile, already open
 * Workspace ws = real.workspace();
 * Course course = ws.courses().create(CourseDraft.titled("Cell Biology"));
 * Path pdf = real.textPdf("cells.pdf", "Cells", 6);
 * real.awaitSettled(ws.documents().importPdf(course.id(), null, pdf).id());
 * ... new MainWindow(real.context(), …) ...
 * real.close();                                            // in @AfterEach
 * }</pre>
 *
 * <p>{@link #fakeAi()} exposes the fakes, so a test can make the assistant slow, unavailable or failing.
 * Use {@link #textPdf} and {@link #scannedPdf} to make PDFs; {@link TestPdf} builds anything more specific.
 */
public final class RealWorkspaces implements AutoCloseable {

    private static final Duration DEFAULT_WAIT = Duration.ofSeconds(30);

    private final Path dir;
    private final FakeAi ai;
    private final AppContext context;

    private RealWorkspaces(Path dir, FakeAi ai, Clock clock) {
        startFx();
        this.dir = dir;
        this.ai = ai;
        AppPaths paths = AppPaths.at(dir.resolve("home"));
        this.context = new AppContext(paths, clock, false,
                WorkspaceImpl.factory(AppContext.capabilities(paths, ai.capabilities())));
    }

    /** An app home under {@code dir} with one profile, "Ada Lovelace", open and without a password. */
    public static RealWorkspaces open(Path dir) {
        return open(dir, FakeAi.create(), Clock.systemDefaultZone());
    }

    /** As {@link #open(Path)} with the given fakes and clock. */
    public static RealWorkspaces open(Path dir, FakeAi ai, Clock clock) {
        RealWorkspaces real = new RealWorkspaces(dir, ai, clock);
        ProfileSummary profile = real.context.profiles().create(new ProfileDraft("Ada Lovelace", 210, null));
        Workspace opened = real.context.open(profile.id(), null);
        // The context publishes the workspace on the FX thread; views read it from there.
        await(() -> real.context.workspace() == opened, DEFAULT_WAIT, "the profile to open");
        return real;
    }

    /** An app home under {@code dir} with no profiles, as on first run. */
    public static RealWorkspaces empty(Path dir) {
        return new RealWorkspaces(dir, FakeAi.create(), Clock.systemDefaultZone());
    }

    /** The app context, for building windows and views. */
    public AppContext context() {
        return context;
    }

    /** The open workspace. */
    public Workspace workspace() {
        return context.workspace();
    }

    /** The AI fakes behind every workspace this app opens. */
    public FakeAi fakeAi() {
        return ai;
    }

    /** A PDF with a title, {@code pages} pages of headed paragraphs and page numbers, written to {@code dir/name}. */
    public Path textPdf(String name, String title, int pages) {
        TestPdf.Builder pdf = TestPdf.builder().title(title).pageNumbers();
        for (int page = 1; page <= pages; page++) {
            if (page > 1) {
                pdf.pageBreak();
            }
            pdf.heading(page + ". " + title + " part " + page)
                    .paragraph(TestPdf.sentences(page, 6))
                    .paragraph(TestPdf.sentences(page * 31L, 5));
        }
        return write(pdf, name);
    }

    /** A PDF of {@code pages} image-only pages, as a scanner makes, written to {@code dir/name}. */
    public Path scannedPdf(String name, int pages) {
        TestPdf.Builder pdf = TestPdf.builder();
        for (int page = 1; page <= pages; page++) {
            pdf.imagePage(0.9);
        }
        return write(pdf, name);
    }

    /** A copy of {@code source} protected with {@code password}, written to {@code dir/name}. */
    public Path lockedPdf(Path source, String name, String password) {
        try {
            return TestPdf.encrypt(source, dir.resolve(name), password);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Waits until the document is ready (or needs review, or failed) and returns it. */
    public DocumentSummary awaitSettled(String documentId) {
        await(() -> {
            DocumentStatus status = workspace().documents().get(documentId).status();
            return status == DocumentStatus.READY || status == DocumentStatus.NEEDS_REVIEW
                    || status == DocumentStatus.FAILED;
        }, DEFAULT_WAIT, "document " + documentId + " to finish processing");
        return workspace().documents().get(documentId);
    }

    /** Waits until {@code condition} holds, failing the test after {@code timeout}. */
    public static void await(BooleanSupplier condition, Duration timeout, String what) {
        Instant end = Instant.now().plus(timeout);
        while (!condition.getAsBoolean()) {
            if (Instant.now().isAfter(end)) {
                throw new AssertionError("Timed out waiting for " + what);
            }
            try {
                Thread.sleep(20);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError("Interrupted waiting for " + what, e);
            }
        }
    }

    /** Starts JavaFX (headless in tests) unless TestFX or an earlier test already did; the app context needs it. */
    private static void startFx() {
        CountDownLatch started = new CountDownLatch(1);
        try {
            Platform.startup(started::countDown);
        } catch (IllegalStateException alreadyRunning) {
            return;
        }
        try {
            if (!started.await(30, TimeUnit.SECONDS)) {
                throw new AssertionError("JavaFX did not start");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError("Interrupted while starting JavaFX", e);
        }
    }

    private Path write(TestPdf.Builder pdf, String name) {
        try {
            return pdf.write(dir.resolve(name));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public void close() {
        context.close();
    }
}
