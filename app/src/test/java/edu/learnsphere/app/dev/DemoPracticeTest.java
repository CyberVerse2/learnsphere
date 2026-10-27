package edu.learnsphere.app.dev;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import edu.learnsphere.app.AppContext;
import edu.learnsphere.core.AppPaths;
import edu.learnsphere.core.error.NotFoundException;
import edu.learnsphere.core.events.ProgressChanged;
import edu.learnsphere.core.model.Confidence;
import edu.learnsphere.core.model.ItemStatus;
import edu.learnsphere.core.model.ItemType;
import edu.learnsphere.core.model.LearningItem;
import edu.learnsphere.core.model.Rating;
import edu.learnsphere.core.service.CourseFilter;
import edu.learnsphere.core.service.CourseSummary;
import edu.learnsphere.core.service.Feedback;
import edu.learnsphere.core.service.ItemCounts;
import edu.learnsphere.core.service.Response;
import edu.learnsphere.core.service.SessionItem;
import edu.learnsphere.core.service.SessionRequest;
import edu.learnsphere.core.service.SessionSummary;
import edu.learnsphere.core.service.StudySession;
import edu.learnsphere.core.service.Workspace;
import edu.learnsphere.core.util.Subscription;
import java.nio.file.Path;
import java.time.Clock;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.testfx.api.FxToolkit;
import org.testfx.util.WaitForAsyncUtils;

/** The demo profile's content and study sessions, through the service interfaces the screens use. */
class DemoPracticeTest {

    @TempDir
    Path home;

    private AppContext context;
    private Workspace workspace;

    @BeforeAll
    static void toolkit() throws TimeoutException {
        // Opening a profile publishes it on the FX thread.
        FxToolkit.registerPrimaryStage();
    }

    @BeforeEach
    void open() {
        context = new AppContext(AppPaths.at(home), Clock.systemDefaultZone(), true, DemoWorkspace.instantFactory());
        DemoData.seedProfiles(context);
        workspace = context.openLastProfile().orElseThrow();
    }

    @AfterEach
    void close() {
        context.close();
    }

    @Test
    void studyNextStartsWithDueReviewsThenNewQuestions() {
        int due = workspace.practice().studyNext(null).dueCount();
        assertThat(due).as("reviews due in the demo").isPositive();

        StudySession session = workspace.practice().start(SessionRequest.studyNext());

        List<SessionItem> items = session.items();
        assertThat(items).hasSizeGreaterThan(due);
        assertThat(items.subList(0, due)).noneMatch(SessionItem::isNew);
        assertThat(items.subList(due, items.size())).allMatch(SessionItem::isNew);
    }

    @Test
    void answeringRecordsProgressAndTakesTheItemOffTheDueList() throws TimeoutException {
        List<ProgressChanged> events = new CopyOnWriteArrayList<>();
        Subscription subscription = workspace.events().subscribe(ProgressChanged.class, events::add);
        int dueBefore = workspace.practice().studyNext(null).dueCount();
        StudySession session = workspace.practice().start(SessionRequest.studyNext());
        SessionItem first = session.items().getFirst();
        LearningItem item = workspace.items().get(first.itemId()).item();

        Feedback feedback = workspace.practice().submit(session.id(), correctResponse(item));

        assertThat(feedback.correct()).isTrue();
        assertThat(feedback.nextDueAt()).isGreaterThan(context.clock().millis());
        assertThat(workspace.practice().studyNext(null).dueCount()).isEqualTo(dueBefore - 1);
        WaitForAsyncUtils.waitFor(5, TimeUnit.SECONDS, () -> !events.isEmpty());
        assertThat(events.getFirst().courseId()).isEqualTo(item.courseId());
        subscription.close();

        SessionSummary summary = workspace.practice().finish(session.id());
        assertThat(summary.answered()).isEqualTo(1);
        assertThat(summary.correct()).isEqualTo(1);
        assertThatThrownBy(() -> workspace.practice().submit(session.id(), correctResponse(item)))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    void aWrongAnswerComesBackTomorrow() {
        StudySession session = workspace.practice().start(SessionRequest.studyNext());
        SessionItem first = session.items().stream()
                .filter(i -> i.type() == ItemType.MCQ || i.type() == ItemType.CLOZE).findFirst().orElseThrow();

        Feedback feedback = workspace.practice().submit(session.id(),
                Response.answer(first.itemId(), "not the answer", Confidence.GUESSING, false, 4_000));

        assertThat(feedback.correct()).isFalse();
        assertThat(feedback.rating()).isEqualTo(Rating.AGAIN);
        assertThat(feedback.intervalDays()).isEqualTo(1);
    }

    @Test
    void researchMethodsHasOnlyJustStarted() {
        CourseSummary research = workspace.courses().list(CourseFilter.studying()).stream()
                .filter(c -> c.id().equals(DemoData.RESEARCH_METHODS)).findFirst().orElseThrow();
        double progress = workspace.progress().course(DemoData.RESEARCH_METHODS).progress();

        assertThat(research.title()).isEqualTo("Research Methods in Computing");
        assertThat(progress).isBetween(0.1, 0.2);
    }

    @Test
    void organicChemistryHasDraftAndApprovedQuestionsOfEveryType() {
        ItemCounts counts = workspace.items().counts(DemoData.ORGANIC_CHEMISTRY);
        assertThat(counts.draft()).isPositive();
        assertThat(counts.approved()).isPositive();

        Set<ItemType> approved = EnumSet.noneOf(ItemType.class);
        Set<ItemType> drafts = EnumSet.noneOf(ItemType.class);
        workspace.items().list(edu.learnsphere.core.service.ItemFilter.forCourse(DemoData.ORGANIC_CHEMISTRY))
                .forEach(summary -> {
                    LearningItem item = summary.item();
                    if (item.status() == ItemStatus.APPROVED) {
                        approved.add(item.type());
                    } else if (item.status() == ItemStatus.DRAFT) {
                        drafts.add(item.type());
                    }
                });
        assertThat(approved).containsExactlyInAnyOrder(ItemType.values());
        assertThat(drafts).containsExactlyInAnyOrder(ItemType.values());
    }

    /** The right answer to {@code item}, as the learner would give it. */
    private static Response correctResponse(LearningItem item) {
        if (item.type() == ItemType.FLASHCARD) {
            return Response.flashcard(item.id(), Rating.GOOD, 6_000);
        }
        return Response.answer(item.id(), item.answer(), Confidence.FAIRLY_SURE, false, 6_000);
    }
}
