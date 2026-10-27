package edu.learnsphere.app.testing;

import static org.assertj.core.api.Assertions.assertThat;

import edu.learnsphere.core.model.Course;
import edu.learnsphere.core.model.DocumentStatus;
import edu.learnsphere.core.service.CourseDraft;
import edu.learnsphere.core.service.DocumentSummary;
import edu.learnsphere.core.service.Workspace;
import java.nio.file.Path;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RealWorkspacesTest {

    @TempDir
    Path dir;

    private RealWorkspaces real;

    @AfterEach
    void tearDown() {
        if (real != null) {
            real.close();
        }
    }

    @Test
    void importsAPdfThroughTheRealServices() {
        real = RealWorkspaces.open(dir);
        Workspace ws = real.workspace();
        Course course = ws.courses().create(CourseDraft.titled("Cell Biology"));

        DocumentSummary document = ws.documents().importPdf(course.id(), null, real.textPdf("cells.pdf", "Cells", 3));

        assertThat(real.awaitSettled(document.id()).status()).isEqualTo(DocumentStatus.READY);
        assertThat(ws.profile().displayName()).isEqualTo("Ada Lovelace");
    }

    @Test
    void scannedPagesWaitForReview() {
        real = RealWorkspaces.open(dir);
        Workspace ws = real.workspace();
        Course course = ws.courses().create(CourseDraft.titled("Cell Biology"));

        DocumentSummary document = ws.documents().importPdf(course.id(), null, real.scannedPdf("scanned.pdf", 2));

        assertThat(real.awaitSettled(document.id()).status()).isEqualTo(DocumentStatus.NEEDS_REVIEW);
    }

    @Test
    void startsWithoutProfilesWhenEmpty() {
        real = RealWorkspaces.empty(dir);

        assertThat(real.context().profiles().list()).isEmpty();
        assertThat(real.context().workspace()).isNull();
    }
}
