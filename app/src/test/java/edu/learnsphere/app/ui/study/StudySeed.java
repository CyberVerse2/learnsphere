package edu.learnsphere.app.ui.study;

import static org.assertj.core.api.Assertions.assertThat;

import edu.learnsphere.app.testing.RealWorkspaces;
import edu.learnsphere.core.model.Course;
import edu.learnsphere.core.model.CourseModule;
import edu.learnsphere.core.model.DocumentStatus;
import edu.learnsphere.core.model.ItemType;
import edu.learnsphere.core.model.JobStatus;
import edu.learnsphere.core.service.CourseDraft;
import edu.learnsphere.core.service.DocumentSummary;
import edu.learnsphere.core.service.GenerationRequest;
import edu.learnsphere.core.service.ItemFilter;
import edu.learnsphere.core.service.ItemSummary;
import edu.learnsphere.core.service.JobUpdate;
import edu.learnsphere.core.service.Workspace;
import edu.learnsphere.pdf.testing.TestPdf;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * The course the study session tests learn from, built through the real services: "Cell Biology" with one
 * module, a six-page PDF and one approved, generated question of every kind, enrolled.
 */
final class StudySeed {

    private StudySeed() {
    }

    /** Creates, fills and enrols the course; returns its id. */
    static String course(Workspace ws, Path dir) {
        Course course = ws.courses().create(CourseDraft.titled("Cell Biology"));
        CourseModule module = ws.courses().addModule(course.id(), "Cells");
        DocumentSummary document = ws.documents().importPdf(course.id(), module.id(), cellsPdf(dir));
        RealWorkspaces.await(() -> {
            DocumentStatus status = ws.documents().get(document.id()).status();
            return status == DocumentStatus.READY || status == DocumentStatus.NEEDS_REVIEW
                    || status == DocumentStatus.FAILED;
        }, Duration.ofSeconds(30), "the PDF to be read");
        // One request for every kind: the questions come from different passages, so none is a near-duplicate.
        JobUpdate job = ws.generation().generate(new GenerationRequest(course.id(), module.id(),
                EnumSet.allOf(ItemType.class), ItemType.values().length, null, Set.of()));
        RealWorkspaces.await(() -> {
            JobStatus status = ws.jobs().find(job.jobId()).orElseThrow().status();
            return status == JobStatus.DONE || status == JobStatus.FAILED || status == JobStatus.CANCELLED;
        }, Duration.ofSeconds(60), "questions to be written");
        assertThat(ws.jobs().find(job.jobId()).orElseThrow().status()).isEqualTo(JobStatus.DONE);
        List<ItemSummary> drafts = ws.items().list(ItemFilter.reviewQueue(course.id()));
        assertThat(drafts).extracting(summary -> summary.item().type()).containsExactlyInAnyOrder(ItemType.values());
        ws.items().approveAll(drafts.stream().map(summary -> summary.item().id()).toList());
        ws.courses().enrol(course.id());
        return course.id();
    }

    /** Six pages, each opening with a plain statement the questions are written from. */
    private static Path cellsPdf(Path dir) {
        String[][] pages = {
                {"Membranes", "The cell membrane controls which substances enter and leave the cell."},
                {"Mitochondria", "Mitochondria release energy from glucose during aerobic respiration."},
                {"Ribosomes", "Ribosomes build proteins by joining amino acids in the order messenger RNA sets."},
                {"The nucleus", "The nucleus stores the genetic material of the cell as chromosomes."},
                {"Chloroplasts", "Chloroplasts capture light energy and use it to make glucose in plant cells."},
                {"Enzymes", "Enzymes speed up chemical reactions without being used up themselves."}};
        TestPdf.Builder pdf = TestPdf.builder().title("Cells").pageNumbers();
        for (int i = 0; i < pages.length; i++) {
            if (i > 0) {
                pdf.pageBreak();
            }
            pdf.heading(pages[i][0])
                    .paragraph(pages[i][1] + " " + TestPdf.sentences(i + 1, 6))
                    .paragraph(TestPdf.sentences((i + 1) * 31L, 5));
        }
        try {
            return pdf.write(dir.resolve("cells.pdf"));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
