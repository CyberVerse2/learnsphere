package edu.learnsphere.core.sharing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import edu.learnsphere.core.error.ConflictException;
import edu.learnsphere.core.error.InvalidInputException;
import edu.learnsphere.core.error.LearnSphereException;
import edu.learnsphere.core.error.NotFoundException;
import edu.learnsphere.core.error.UserMessage;
import edu.learnsphere.core.model.Flag;
import edu.learnsphere.core.model.FlagReason;
import edu.learnsphere.core.model.FlagSource;
import edu.learnsphere.core.model.Visibility;
import edu.learnsphere.core.service.ClassInsightSummary;
import edu.learnsphere.core.service.ConceptInsight;
import edu.learnsphere.core.service.ImportOutcome;
import edu.learnsphere.core.service.ImportResult;
import edu.learnsphere.core.service.ItemInsight;
import edu.learnsphere.core.service.LearnerInsight;
import edu.learnsphere.core.sharing.ProgressFile.ItemProgress;
import edu.learnsphere.core.sharing.SharingProfile.Made;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ProgressAndFeedbackSharingTest {

    @TempDir
    Path dir;

    private SharingProfile creator;
    private SharingProfile learner;
    private Made made;
    private String learnerCourseId;

    @BeforeEach
    void setUp() {
        creator = SharingProfile.open(dir.resolve("creator"));
        learner = SharingProfile.open(dir.resolve("learner"));
        made = creator.createCourse(Visibility.SHAREABLE);
        learnerCourseId = learner.importCourse(creator.export(made.course(), dir.resolve("course.learnsphere")), null)
                .courseId();
    }

    @AfterEach
    void tearDown() {
        learner.close();
        creator.close();
    }

    // ---- progress -----------------------------------------------------------------------------------

    @Test
    void learnerProgressBecomesClassInsights() throws IOException {
        learner.answer(learnerCourseId, made.mcq().id(), true, true, false, true);
        learner.answer(learnerCourseId, made.shortAnswer().id(), false);
        learner.t.reviewed(learner.t.repos.items().get(made.mcq().id()), learner.t.now() + 86_400_000L);
        Path ana = dir.resolve("ana.lsprogress");

        learner.sharing.exportProgress(learnerCourseId, "  Ana   Lopez ", ana);

        ProgressFile file = SharingFiles.read(ana, ProgressFile.FORMAT, ProgressFile.class,
                SharingMessages.NOT_A_PROGRESS_FILE);
        assertThat(file.learnerLabel()).isEqualTo("Ana Lopez");
        assertThat(file.packageId()).isEqualTo(made.course().packageId());
        assertThat(file.items()).extracting(ItemProgress::itemId)
                .containsExactlyInAnyOrder(made.mcq().id(), made.shortAnswer().id());
        ItemProgress mcq = file.items().stream().filter(i -> i.itemId().equals(made.mcq().id())).findFirst().orElseThrow();
        assertThat(mcq.attempts()).isEqualTo(4);
        assertThat(mcq.correct()).isEqualTo(3);
        assertThat(mcq.lapses()).isEqualTo(1);
        assertThat(Files.readString(ana)).doesNotContain("my answer");

        // A second learner, struggling with the membrane.
        Path ben = dir.resolve("ben.lsprogress");
        SharingFiles.write(ben, new ProgressFile(ProgressFile.FORMAT, 1, made.course().packageId(), "Cell Biology", 1, "Ben",
                creator.t.now(), List.of(new ItemProgress(made.mcq().id(), 2, 0, 0, 3_000, 2),
                new ItemProgress(made.flashcard().id(), 1, 1, 1, 2_000, 0),
                new ItemProgress("6f7a3b2c-1111-4222-8333-944455556666", 5, 5, 1, 1_000, 0))));

        ClassInsightSummary summary = creator.sharing.importClassProgress(made.course().id(), List.of(ana, ben));

        assertThat(summary.learnerCount()).isEqualTo(2);
        assertThat(summary.learners()).extracting(LearnerInsight::label).containsExactly("Ana Lopez", "Ben");
        LearnerInsight anaInsight = summary.learners().getFirst();
        assertThat(anaInsight.attempts()).isEqualTo(5);
        assertThat(anaInsight.accuracy()).isCloseTo(3 / 5.0, within(1e-9));
        assertThat(summary.items()).extracting(ItemInsight::itemId)
                .as("hardest first; attempts on unknown items are left out")
                .containsExactly(made.shortAnswer().id(), made.mcq().id(), made.flashcard().id());
        ItemInsight mcqInsight = summary.items().get(1);
        assertThat(mcqInsight.attempts()).isEqualTo(6);
        assertThat(mcqInsight.learners()).isEqualTo(2);
        assertThat(mcqInsight.accuracy()).isCloseTo(3 / 6.0, within(1e-9));
        assertThat(summary.concepts()).extracting(ConceptInsight::name).containsExactly("Membrane", "ATP");
        ConceptInsight membrane = summary.concepts().getFirst();
        assertThat(membrane.accuracy()).isCloseTo(3 / 7.0, within(1e-9));
        assertThat(membrane.learnersAttempted()).isEqualTo(2);
        assertThat(membrane.learnersStruggling()).isEqualTo(1);
        ConceptInsight atp = summary.concepts().get(1);
        assertThat(atp.learnersAttempted()).isEqualTo(1);
        assertThat(atp.learnersStruggling()).isZero();

        ProgressSharing progress = new ProgressSharing(creator.t.repos, creator.events, creator.t.clock);
        assertThat(progress.itemClassStats(made.course().id()).get(made.mcq().id()))
                .isEqualTo(new ProgressSharing.ItemClassStats(2, 0.5));
    }

    @Test
    void sameLabelReplacesTheEarlierProgressAndInsightsCanBeRemoved() {
        Path first = progress("Ana", 100, new ItemProgress(made.mcq().id(), 1, 0, 0, 1_000, 0));
        Path later = progress("ana", 200, new ItemProgress(made.mcq().id(), 3, 3, 1, 1_000, 0));
        Path ben = progress("Ben", 100, new ItemProgress(made.mcq().id(), 1, 1, 1, 1_000, 0));
        creator.sharing.importClassProgress(made.course().id(), List.of(first, ben));

        ClassInsightSummary summary = creator.sharing.importClassProgress(made.course().id(), List.of(later));

        assertThat(summary.learners()).extracting(LearnerInsight::label).containsExactly("ana", "Ben");
        assertThat(summary.learners().getFirst().accuracy()).isEqualTo(1.0);

        creator.sharing.removeClassInsight(summary.learners().getFirst().insightId());
        assertThat(creator.sharing.classInsights(made.course().id()).learners()).extracting(LearnerInsight::label)
                .containsExactly("Ben");
        assertThatThrownBy(() -> creator.sharing.removeClassInsight(summary.learners().getFirst().insightId()))
                .isInstanceOf(NotFoundException.class)
                .satisfies(e -> assertThat(message(e)).isEqualTo(SharingMessages.INSIGHT_NOT_FOUND));
    }

    @Test
    void progressFilesAreChecked() throws IOException {
        String courseId = made.course().id();
        assertThatThrownBy(() -> creator.sharing.importClassProgress(courseId, List.of()))
                .isInstanceOf(InvalidInputException.class)
                .satisfies(e -> assertThat(message(e)).isEqualTo(SharingMessages.NO_PROGRESS_FILES));

        Path other = dir.resolve("other.lsprogress");
        SharingFiles.write(other, new ProgressFile(ProgressFile.FORMAT, 1, "another-package", "Other", 1, "Ana", 1,
                List.of()));
        assertThatThrownBy(() -> creator.sharing.importClassProgress(courseId, List.of(other)))
                .isInstanceOf(InvalidInputException.class)
                .satisfies(e -> assertThat(message(e)).isEqualTo(SharingMessages.PROGRESS_OTHER_COURSE));

        Path notes = Files.writeString(dir.resolve("notes.lsprogress"), "{\"format\":\"something-else\"}");
        Path garbage = Files.write(dir.resolve("garbage.lsprogress"), new byte[] {1, 2, 3});
        Path future = Files.writeString(dir.resolve("future.lsprogress"),
                "{\"format\":\"learnsphere-progress\",\"formatVersion\":2}", StandardCharsets.UTF_8);
        Path badCounts = dir.resolve("bad.lsprogress");
        SharingFiles.write(badCounts, new ProgressFile(ProgressFile.FORMAT, 1, made.course().packageId(), "", 1, "Ana", 1,
                List.of(new ItemProgress(made.mcq().id(), 1, 5, 1, 0, 0))));
        for (Path bad : List.of(notes, garbage, badCounts)) {
            assertThatThrownBy(() -> creator.sharing.importClassProgress(courseId, List.of(bad)))
                    .isInstanceOf(InvalidInputException.class)
                    .satisfies(e -> assertThat(message(e)).isEqualTo(SharingMessages.NOT_A_PROGRESS_FILE));
        }
        assertThatThrownBy(() -> creator.sharing.importClassProgress(courseId, List.of(future)))
                .isInstanceOf(InvalidInputException.class)
                .satisfies(e -> assertThat(message(e)).isEqualTo(SharingMessages.FILE_TOO_NEW));
        assertThat(creator.sharing.classInsights(courseId).isEmpty()).isTrue();

        assertThatThrownBy(() -> learner.sharing.importClassProgress(learnerCourseId, List.of(other)))
                .isInstanceOf(ConflictException.class)
                .satisfies(e -> assertThat(message(e)).isEqualTo(SharingMessages.INSIGHTS_NEED_OWN_COURSE));
    }

    @Test
    void progressExportNeedsALabelAndAnswers() {
        Path target = dir.resolve("me.lsprogress");
        assertThatThrownBy(() -> learner.sharing.exportProgress(learnerCourseId, "   ", target))
                .isInstanceOf(InvalidInputException.class)
                .satisfies(e -> assertThat(message(e)).isEqualTo(SharingMessages.LABEL_REQUIRED));
        assertThatThrownBy(() -> learner.sharing.exportProgress(learnerCourseId, "x".repeat(61), target))
                .isInstanceOf(InvalidInputException.class)
                .satisfies(e -> assertThat(message(e)).isEqualTo(SharingMessages.LABEL_TOO_LONG));
        assertThatThrownBy(() -> learner.sharing.exportProgress(learnerCourseId, "Ana", target))
                .isInstanceOf(ConflictException.class)
                .satisfies(e -> assertThat(message(e)).isEqualTo(SharingMessages.NO_PROGRESS));
        assertThat(target).doesNotExist();
    }

    // ---- feedback -----------------------------------------------------------------------------------

    @Test
    void learnerFlagsReachTheCreatorOnce() {
        Flag wrong = Flag.open(made.mcq().id(), FlagReason.WRONG_ANSWER, "Option two is also right", FlagSource.SELF,
                learner.t.now());
        Flag unclear = Flag.open(made.shortAnswer().id(), FlagReason.UNCLEAR, "", FlagSource.SELF, learner.t.now());
        Flag resolved = Flag.open(made.flashcard().id(), FlagReason.TYPO, "", FlagSource.SELF, learner.t.now());
        learner.t.repos.flags().insert(wrong);
        learner.t.repos.flags().insert(unclear);
        learner.t.repos.flags().insert(resolved);
        learner.t.repos.flags().resolve(resolved.id(), learner.t.now());
        Path file = dir.resolve("feedback.lsfeedback");

        learner.sharing.exportFeedback(learnerCourseId, file);
        ImportResult result = creator.sharing.importFeedback(file);

        assertThat(result.outcome()).isEqualTo(ImportOutcome.FEEDBACK_ADDED);
        assertThat(result.courseId()).isEqualTo(made.course().id());
        assertThat(result.added()).isEqualTo(2);
        List<Flag> flags = creator.t.repos.flags().listByCourse(made.course().id(), true);
        assertThat(flags).extracting(Flag::itemId).containsExactlyInAnyOrder(made.mcq().id(), made.shortAnswer().id());
        assertThat(flags).allSatisfy(f -> {
            assertThat(f.source()).isEqualTo(FlagSource.LEARNER_FEEDBACK);
            assertThat(f.isOpen()).isTrue();
        });
        assertThat(flags).extracting(Flag::note).contains("Option two is also right");

        assertThat(creator.sharing.importFeedback(file).added()).isZero();
        assertThat(creator.t.repos.flags().listByCourse(made.course().id(), true)).hasSize(2);
    }

    @Test
    void feedbackNeedsFlagsAndTheCreatorsCourse() throws IOException {
        Path file = dir.resolve("feedback.lsfeedback");
        assertThatThrownBy(() -> learner.sharing.exportFeedback(learnerCourseId, file))
                .isInstanceOf(ConflictException.class)
                .satisfies(e -> assertThat(message(e)).isEqualTo(SharingMessages.NO_FEEDBACK));

        learner.t.repos.flags().insert(Flag.open(made.mcq().id(), FlagReason.OTHER, "", FlagSource.SELF, learner.t.now()));
        learner.sharing.exportFeedback(learnerCourseId, file);
        assertThatThrownBy(() -> learner.sharing.importFeedback(file))
                .isInstanceOf(ConflictException.class)
                .satisfies(e -> assertThat(message(e)).isEqualTo(SharingMessages.FEEDBACK_NOT_YOUR_COURSE));
        try (SharingProfile stranger = SharingProfile.open(dir.resolve("stranger"))) {
            assertThatThrownBy(() -> stranger.sharing.importFeedback(file))
                    .isInstanceOf(NotFoundException.class)
                    .satisfies(e -> assertThat(message(e)).isEqualTo(SharingMessages.FEEDBACK_COURSE_MISSING));
        }
        Path progress = progress("Ana", 1, new ItemProgress(made.mcq().id(), 1, 1, 1, 0, 0));
        assertThatThrownBy(() -> creator.sharing.importFeedback(progress))
                .isInstanceOf(InvalidInputException.class)
                .satisfies(e -> assertThat(message(e)).isEqualTo(SharingMessages.NOT_A_FEEDBACK_FILE));
    }

    // ---- helpers ------------------------------------------------------------------------------------

    private Path progress(String label, long exportedAt, ItemProgress... items) {
        Path file = dir.resolve(label + "-" + exportedAt + ".lsprogress");
        SharingFiles.write(file, new ProgressFile(ProgressFile.FORMAT, 1, made.course().packageId(), "Cell Biology", 1,
                label, exportedAt, List.of(items)));
        return file;
    }

    private static UserMessage message(Throwable e) {
        return ((LearnSphereException) e).userMessage();
    }
}
