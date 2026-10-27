package edu.learnsphere.core.db;

import static org.assertj.core.api.Assertions.assertThat;

import edu.learnsphere.core.model.Course;
import edu.learnsphere.core.model.CourseModule;
import edu.learnsphere.core.model.Document;
import edu.learnsphere.core.model.DocumentError;
import edu.learnsphere.core.model.DocumentPage;
import edu.learnsphere.core.model.DocumentStatus;
import edu.learnsphere.core.model.PageSource;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class DocumentRepositoryTest extends RepositoryTestBase {

    @Test
    void roundTripsADocument() {
        Course course = t.course("C");
        CourseModule module = t.module(course, "M");
        Document document = t.document(course, module, "Notes");

        assertThat(repos.documents().get(document.id())).isEqualTo(document);
        assertThat(repos.documents().listByCourse(course.id())).containsExactly(document);
        assertThat(repos.documents().listByModule(module.id())).containsExactly(document);
        assertThat(repos.documents().findByCourseAndSha(course.id(), document.fileSha256())).contains(document);
        assertThat(repos.documents().countBySha(document.fileSha256())).isEqualTo(1);
    }

    @Test
    void movesThroughThePipeline() {
        Document document = t.document(t.course("C"), null, "Doc");

        repos.documents().setPageCount(document.id(), 120);
        repos.documents().updateStatus(document.id(), DocumentStatus.EXTRACTING, 0.1, "12/120");
        assertThat(repos.documents().listByStatus(Set.of(DocumentStatus.EXTRACTING, DocumentStatus.READING)))
                .extracting(Document::id).containsExactly(document.id());
        repos.documents().updateProgress(document.id(), 0.5, "60/120");
        Document midway = repos.documents().get(document.id());
        assertThat(midway.pageCount()).isEqualTo(120);
        assertThat(midway.progress()).isEqualTo(0.5);
        assertThat(midway.statusDetail()).isEqualTo("60/120");

        repos.documents().markFailed(document.id(), DocumentError.ENCRYPTED, "password");
        Document failed = repos.documents().get(document.id());
        assertThat(failed.status()).isEqualTo(DocumentStatus.FAILED);
        assertThat(failed.errorCode()).isEqualTo(DocumentError.ENCRYPTED);

        repos.documents().markReady(document.id());
        Document ready = repos.documents().get(document.id());
        assertThat(ready.status()).isEqualTo(DocumentStatus.READY);
        assertThat(ready.progress()).isEqualTo(1.0);
        assertThat(ready.errorCode()).isNull();
        assertThat(ready.statusDetail()).isNull();
    }

    @Test
    void renamesMovesAndDeletes() {
        Course course = t.course("C");
        CourseModule module = t.module(course, "M");
        Document document = t.document(course, null, "Doc");
        t.chunk(document, 0, "text");

        repos.documents().rename(document.id(), "Renamed");
        repos.documents().setModule(document.id(), module.id());

        assertThat(repos.documents().get(document.id()).title()).isEqualTo("Renamed");
        assertThat(repos.documents().get(document.id()).moduleId()).isEqualTo(module.id());
        assertThat(repos.chunks().listByDocument(document.id())).allSatisfy(c -> assertThat(c.moduleId()).isEqualTo(module.id()));

        assertThat(repos.documents().delete(document.id())).isTrue();
        assertThat(repos.documents().find(document.id())).isEmpty();
        assertThat(repos.chunks().countByDocument(document.id())).isZero();
    }

    @Test
    void storesAndReviewsPages() {
        Course course = t.course("C");
        Document document = t.document(course, null, "Scan");
        DocumentPage text = new DocumentPage(document.id(), 1, "Typed text", PageSource.TEXT_LAYER, false, null, false, t.now(), t.now());
        DocumentPage scanned = new DocumentPage(document.id(), 2, "# Read", PageSource.AI_READ, false, null, true, t.now(), t.now());
        DocumentPage scanned2 = new DocumentPage(document.id(), 3, "More", PageSource.AI_READ, false, null, true, t.now(), t.now());
        repos.documents().upsertPages(List.of(text, scanned, scanned2));

        assertThat(repos.documents().listPages(document.id())).containsExactly(text, scanned, scanned2);
        assertThat(repos.documents().findPage(document.id(), 2)).contains(scanned);
        assertThat(repos.documents().storedPageNumbers(document.id())).containsExactlyInAnyOrder(1, 2, 3);
        assertThat(repos.documents().listPagesNeedingReview(document.id())).extracting(DocumentPage::pageNumber).containsExactly(2, 3);
        assertThat(repos.documents().listUsablePages(document.id())).extracting(DocumentPage::pageNumber).containsExactly(1);
        assertThat(repos.documents().pagesNeedingReviewByDocument(course.id())).containsEntry(document.id(), 2);

        repos.documents().updatePageText(document.id(), 2, "# Corrected", true);
        assertThat(repos.documents().markPagesReviewed(document.id(), List.of(3))).isEqualTo(1);
        repos.documents().setFigureNotes(document.id(), 3, "A cell diagram.");

        assertThat(repos.documents().countPagesNeedingReview(document.id())).isZero();
        assertThat(repos.documents().findPage(document.id(), 2).orElseThrow().text()).isEqualTo("# Corrected");
        assertThat(repos.documents().findPage(document.id(), 3).orElseThrow().figureNotes()).isEqualTo("A cell diagram.");
        assertThat(repos.documents().listUsablePages(document.id())).hasSize(3);

        DocumentPage replaced = new DocumentPage(document.id(), 1, "Replaced", PageSource.TEXT_LAYER, false, null, false, t.now(), t.now());
        repos.documents().upsertPage(replaced);
        assertThat(repos.documents().findPage(document.id(), 1)).contains(replaced);
        assertThat(repos.documents().deletePages(document.id())).isEqualTo(3);
    }
}
