package edu.learnsphere.app.ui.studio;

import static org.assertj.core.api.Assertions.assertThat;

import edu.learnsphere.app.ui.StudioTab;
import edu.learnsphere.core.model.CourseOrigin;
import edu.learnsphere.core.model.CourseStatus;
import edu.learnsphere.core.model.DocumentStatus;
import edu.learnsphere.core.model.Visibility;
import edu.learnsphere.core.service.CourseSummary;
import edu.learnsphere.core.service.DocumentSummary;
import org.junit.jupiter.api.Test;

class StudioFormatsTest {

    @Test
    void sizesReadAsPeopleSayThem() {
        assertThat(StudioFormats.size(0)).isEqualTo("0 KB");
        assertThat(StudioFormats.size(300)).isEqualTo("1 KB");
        assertThat(StudioFormats.size(640_000)).isEqualTo("640 KB");
        assertThat(StudioFormats.size(6_220_000)).isEqualTo("6.2 MB");
        assertThat(StudioFormats.size(14_000_000)).isEqualTo("14 MB");
        assertThat(StudioFormats.size(250_400_000)).isEqualTo("250 MB");
        assertThat(StudioFormats.size(1_230_000_000)).isEqualTo("1.2 GB");
    }

    @Test
    void courseFileNamesKeepTheTitleAndDropWhatFilesCannotHold() {
        assertThat(StudioFormats.courseFileName("Cell Biology")).isEqualTo("Cell Biology.learnsphere");
        assertThat(StudioFormats.courseFileName("Maths: A/B testing?")).isEqualTo("Maths A B testing.learnsphere");
        assertThat(StudioFormats.courseFileName(".hidden")).isEqualTo("hidden.learnsphere");
        assertThat(StudioFormats.courseFileName("  ")).isEqualTo("Course.learnsphere");
        assertThat(StudioFormats.courseFileName("x".repeat(120))).hasSize(80 + ".learnsphere".length());
    }

    @Test
    void everySharingChoiceHasAShortNameAndAMeaning() {
        for (Visibility visibility : Visibility.values()) {
            assertThat(StudioFormats.visibilityLabel(visibility)).isNotBlank();
            assertThat(StudioFormats.visibilityDetail(visibility)).endsWith(".");
        }
        assertThat(StudioFormats.visibilityLabel(Visibility.PRIVATE)).isEqualTo("Only me");
        assertThat(StudioFormats.visibilityLabel(Visibility.SHAREABLE)).isEqualTo("Anyone with the file");
        assertThat(StudioFormats.visibilityLabel(Visibility.PROTECTED)).isEqualTo("With a code");
    }

    @Test
    void aPdfIsDescribedByItsPagesSizeAndModule() {
        DocumentSummary inModule = document("Isomerism", 120, 14_900_000);
        DocumentSummary wholeCourse = document(null, 0, 0);

        assertThat(StudioFormats.documentMeta(inModule)).isEqualTo("120 pages · 14.9 MB · Isomerism");
        assertThat(StudioFormats.documentMeta(wholeCourse)).isEqualTo("Whole course");
    }

    @Test
    void aCourseOpensWhereItNeedsTheCreatorFirst() {
        assertThat(CourseListSection.landingTab(course(0, 1, 0, 3, 0))).isEqualTo(StudioTab.DOCUMENTS);
        assertThat(CourseListSection.landingTab(course(2, 0, 0, 3, 0))).isEqualTo(StudioTab.REVIEW);
        assertThat(CourseListSection.landingTab(course(0, 0, 1, 3, 0))).isEqualTo(StudioTab.DOCUMENTS);
        assertThat(CourseListSection.landingTab(course(0, 0, 0, 3, 1))).isEqualTo(StudioTab.PUBLISH);
        assertThat(CourseListSection.landingTab(course(0, 0, 0, 0, 0))).isEqualTo(StudioTab.MODULES);
        assertThat(CourseListSection.landingTab(course(0, 0, 0, 3, 0))).isEqualTo(StudioTab.DETAILS);
    }

    @Test
    void anEmptyCourseSaysSoInsteadOfCountingZeros() {
        assertThat(CourseListSection.contentsLine(course(0, 0, 0, 0, 0))).isEqualTo("No modules or PDFs yet");
        assertThat(CourseListSection.contentsLine(course(0, 0, 0, 3, 0)))
                .isEqualTo("3 modules · 0 PDFs · 0 approved questions");
    }

    @Test
    void deletingNeedsTheWordWhateverItsCase() {
        assertThat(DetailsSection.matches(" Delete ")).isTrue();
        assertThat(DetailsSection.matches("DELETE")).isTrue();
        assertThat(DetailsSection.matches("delet")).isFalse();
        assertThat(DetailsSection.matches(null)).isFalse();
    }

    private static DocumentSummary document(String module, int pages, long bytes) {
        return new DocumentSummary("d1", "c1", module == null ? null : "m1", module, "Chapter", "chapter.pdf", bytes, pages,
                DocumentStatus.READY, 1, 0, 0, null, 0, 0, 0);
    }

    private static CourseSummary course(int drafts, int attention, int processing, int modules, int flags) {
        return new CourseSummary("c1", "Course", "", "", 210, CourseOrigin.LOCAL, CourseStatus.DRAFT, Visibility.PRIVATE,
                0, true, false, modules, attention + processing, processing, attention, 0, drafts, 0, 0, flags, 0, null,
                0);
    }
}
