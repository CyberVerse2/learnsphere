package edu.learnsphere.core.service.impl;

import static edu.learnsphere.core.learning.StudyData.MINUTE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.assertj.core.api.Assertions.within;

import edu.learnsphere.core.error.LearnSphereException;
import edu.learnsphere.core.error.NotFoundException;
import edu.learnsphere.core.error.UserMessage;
import edu.learnsphere.core.events.EventBus;
import edu.learnsphere.core.events.ProgressChanged;
import edu.learnsphere.core.learning.FsrsScheduler;
import edu.learnsphere.core.learning.StudyData;
import edu.learnsphere.core.learning.StudyMessages;
import edu.learnsphere.core.model.Attempt;
import edu.learnsphere.core.model.Chunk;
import edu.learnsphere.core.model.Concept;
import edu.learnsphere.core.model.Confidence;
import edu.learnsphere.core.model.Course;
import edu.learnsphere.core.model.CourseModule;
import edu.learnsphere.core.model.CourseOrigin;
import edu.learnsphere.core.model.CourseStatus;
import edu.learnsphere.core.model.Difficulty;
import edu.learnsphere.core.model.Document;
import edu.learnsphere.core.model.GradedBy;
import edu.learnsphere.core.model.ItemOrigin;
import edu.learnsphere.core.model.ItemStatus;
import edu.learnsphere.core.model.ItemType;
import edu.learnsphere.core.model.LearningItem;
import edu.learnsphere.core.model.Rating;
import edu.learnsphere.core.model.ReviewPhase;
import edu.learnsphere.core.model.ReviewState;
import edu.learnsphere.core.model.SessionKind;
import edu.learnsphere.core.model.Visibility;
import edu.learnsphere.core.service.ConceptChange;
import edu.learnsphere.core.service.Feedback;
import edu.learnsphere.core.service.Hint;
import edu.learnsphere.core.service.Response;
import edu.learnsphere.core.service.SessionItem;
import edu.learnsphere.core.service.SessionRequest;
import edu.learnsphere.core.service.SessionSummary;
import edu.learnsphere.core.service.SettingKeys;
import edu.learnsphere.core.service.SourceRef;
import edu.learnsphere.core.service.StudyNext;
import edu.learnsphere.core.service.StudySession;
import edu.learnsphere.core.spi.GradeResult;
import edu.learnsphere.core.spi.ShortAnswerGrader;
import edu.learnsphere.core.testing.TestDb;
import edu.learnsphere.core.util.Ids;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PracticeServiceImplTest {

    @TempDir
    Path dir;
    TestDb t;
    StudyData data;
    EventBus events;
    SettingsServiceImpl settings;
    AtomicReference<GradeResult> nextGrade = new AtomicReference<>(
            new GradeResult(0.9, GradeResult.Verdict.CORRECT, "Clear and complete.", List.of()));
    List<ProgressChanged> published = new CopyOnWriteArrayList<>();
    PracticeServiceImpl practice;
    Course course;
    Chunk chunk;

    @BeforeEach
    void open() {
        t = TestDb.open(dir);
        data = new StudyData(t);
        events = new EventBus();
        events.subscribe(ProgressChanged.class, published::add);
        settings = new SettingsServiceImpl(t.repos.settings(), events);
        ShortAnswerGrader grader = (question, reference, answer, context, token) -> nextGrade.get();
        practice = new PracticeServiceImpl(t.repos, settings, grader, events, t.clock);
        course = t.enrolledCourse("Biology");
        Document document = t.document(course, null, "Cell Biology");
        chunk = t.chunk(document, 4, "The nucleus holds the cell's genetic material.");
    }

    @AfterEach
    void close() {
        events.close();
        t.close();
    }

    /** An approved item of {@code course} drawn from page 5 of “Cell Biology”, with answer "nucleus". */
    private LearningItem item(ItemType type, String prompt) {
        return item(type, prompt, type == ItemType.TRUE_FALSE ? "true" : "nucleus");
    }

    private LearningItem item(ItemType type, String prompt, String answer) {
        LearningItem item = LearningItem.draft(course.id(), null, type, prompt, answer, "It holds the DNA.",
                        type == ItemType.MCQ ? List.of("ribosome", "membrane") : List.of(), Difficulty.MEDIUM, chunk.id(), 5,
                        ItemOrigin.AI, t.now())
                .withStatus(ItemStatus.APPROVED, t.now());
        t.repos.items().insert(item);
        t.clock.advance(Duration.ofMillis(1));
        return item;
    }

    private StudySession start(SessionRequest request) {
        return practice.start(request);
    }

    private static UserMessage messageOf(ThrowingCallable call) {
        Throwable thrown = catchThrowable(call);
        assertThat(thrown).isInstanceOf(LearnSphereException.class);
        return ((LearnSphereException) thrown).userMessage();
    }

    /** Waits for events already published and forgets them. */
    private void clearEvents() {
        assertThat(events.awaitIdle(Duration.ofSeconds(5))).isTrue();
        published.clear();
    }

    private List<ProgressChanged> events() {
        assertThat(events.awaitIdle(Duration.ofSeconds(5))).isTrue();
        return published;
    }

    @Test
    void anAnswerIsGradedScheduledAndRecordedTogether() {
        LearningItem mcq = item(ItemType.MCQ, "Which part holds the DNA?");
        StudySession session = start(SessionRequest.studyNext(course.id()));

        assertThat(session.kind()).isEqualTo(SessionKind.STUDY_NEXT);
        assertThat(session.courseTitle()).isEqualTo("Biology");
        SessionItem shown = session.items().getFirst();
        assertThat(shown.itemId()).isEqualTo(mcq.id());
        assertThat(shown.isNew()).isTrue();
        assertThat(shown.hintAvailable()).isTrue();
        assertThat(shown.choices()).containsExactlyInAnyOrder("nucleus", "ribosome", "membrane");
        assertThat(practice.start(SessionRequest.studyNext(course.id())).items().getFirst().choices())
                .as("each session shuffles the options once")
                .containsExactlyInAnyOrder("nucleus", "ribosome", "membrane");
        assertThat(t.repos.sessions().findSession(session.id())).isPresent();

        long answeredAt = t.now();
        Feedback feedback = practice.submit(session.id(),
                Response.answer(mcq.id(), "Nucleus", Confidence.FAIRLY_SURE, false, 6_000));

        assertThat(feedback.correct()).isTrue();
        assertThat(feedback.rating()).isEqualTo(Rating.GOOD);
        assertThat(feedback.gradedBy()).isEqualTo(GradedBy.RULE);
        assertThat(feedback.correctAnswer()).isEqualTo("nucleus");
        assertThat(feedback.explanation()).isEqualTo("It holds the DNA.");
        assertThat(feedback.graderFeedback()).isNull();
        assertThat(feedback.source()).isEqualTo(new SourceRef(chunk.documentId(), "Cell Biology", 5, 5, chunk.id()));
        // A new item answered Good moves to the second learning step: back in ten minutes.
        assertThat(feedback.nextDueAt()).isEqualTo(answeredAt + 10 * MINUTE);
        assertThat(feedback.intervalDays()).isZero();
        assertThat(feedback.disputable()).isFalse();

        Attempt attempt = t.repos.sessions().findAttempt(feedback.attemptId()).orElseThrow();
        assertThat(attempt.sessionId()).isEqualTo(session.id());
        assertThat(attempt.response()).isEqualTo("Nucleus");
        assertThat(attempt.durationMs()).isEqualTo(6_000);
        assertThat(attempt.confidence()).isEqualTo(Confidence.FAIRLY_SURE);
        ReviewState state = t.repos.reviews().find(mcq.id()).orElseThrow();
        assertThat(state.phase()).isEqualTo(ReviewPhase.LEARNING);
        assertThat(state.dueAt()).isEqualTo(feedback.nextDueAt());
        assertThat(state.reps()).isEqualTo(1);
        assertThat(events()).contains(new ProgressChanged(course.id()));
    }

    @Test
    void aReviewItemGetsAnIntervalInDaysThatFollowsTheDesiredRetention() {
        LearningItem first = item(ItemType.CLOZE, "The ___ holds the DNA.");
        LearningItem second = item(ItemType.CLOZE, "The DNA sits in the ___.");
        data.state(first, 10, 10, t.now() - MINUTE);
        data.state(second, 10, 10, t.now() - MINUTE);
        StudySession session = start(SessionRequest.studyNext(course.id()));
        assertThat(session.items()).extracting(SessionItem::isNew).containsOnly(false);

        Feedback usual = practice.submit(session.id(), Response.answer(first.id(), "nucleus", null, false, 1000));
        settings.set(SettingKeys.DESIRED_RETENTION, 0.97);
        Feedback stricter = practice.submit(session.id(), Response.answer(second.id(), "the nucleus", null, false, 1000));

        assertThat(usual.rating()).isEqualTo(Rating.GOOD);
        assertThat(usual.intervalDays()).isGreaterThan(10);
        assertThat(stricter.intervalDays()).isPositive().isLessThan(usual.intervalDays());
        assertThat(t.repos.reviews().find(first.id()).orElseThrow().scheduledDays()).isEqualTo(usual.intervalDays());
    }

    @Test
    void aWrongAnswerComesBackUpToThreeTimes() {
        LearningItem mcq = item(ItemType.MCQ, "Which part holds the DNA?");
        LearningItem other = item(ItemType.TRUE_FALSE, "The nucleus holds the DNA.");
        StudySession session = start(SessionRequest.studyNext(course.id()));

        for (int i = 0; i < PracticeServiceImpl.MAX_TRIES_PER_SESSION; i++) {
            Feedback wrong = practice.submit(session.id(), Response.answer(mcq.id(), "ribosome", Confidence.CERTAIN, false, 1));
            assertThat(wrong.correct()).isFalse();
            assertThat(wrong.rating()).isEqualTo(Rating.AGAIN);
        }
        assertThat(messageOf(() -> practice.submit(session.id(), Response.answer(mcq.id(), "nucleus", null, false, 1))))
                .isEqualTo(StudyMessages.ALREADY_ANSWERED);
        assertThat(t.repos.sessions().listAttemptsForSession(session.id())).hasSize(3);
        assertThat(t.repos.reviews().find(mcq.id()).orElseThrow().phase()).isEqualTo(ReviewPhase.LEARNING);

        practice.submit(session.id(), Response.answer(other.id(), "yes", null, false, 1));
        assertThat(messageOf(() -> practice.submit(session.id(), Response.answer(other.id(), "true", null, false, 1))))
                .as("a right answer does not come back in the same session")
                .isEqualTo(StudyMessages.ALREADY_ANSWERED);
    }

    @Test
    void refusesItemsOutsideTheSessionAndSessionsThatEnded() {
        LearningItem mcq = item(ItemType.MCQ, "Which part holds the DNA?");
        StudySession session = start(SessionRequest.studyNext(course.id()));

        assertThat(messageOf(() -> practice.submit(session.id(), Response.answer(Ids.newId(), "x", null, false, 1))))
                .isEqualTo(StudyMessages.NOT_IN_SESSION);
        assertThatThrownBy(() -> practice.submit(Ids.newId(), Response.answer(mcq.id(), "x", null, false, 1)))
                .isInstanceOf(NotFoundException.class);
        assertThat(messageOf(() -> practice.finish(Ids.newId()))).isEqualTo(StudyMessages.SESSION_NOT_FOUND);

        practice.finish(session.id());
        assertThat(messageOf(() -> practice.submit(session.id(), Response.answer(mcq.id(), "nucleus", null, false, 1))))
                .isEqualTo(StudyMessages.SESSION_ENDED);
        assertThat(messageOf(() -> practice.hint(session.id(), mcq.id()))).isEqualTo(StudyMessages.SESSION_ENDED);
    }

    @Test
    void onlyCoursesBeingStudiedOrCreatedHereCanBePractised() {
        Course imported = new Course(Ids.newId(), "Chemistry", "", "", "Grace", CourseOrigin.IMPORTED, Visibility.PRIVATE,
                CourseStatus.PUBLISHED, 30, 1, t.now(), Ids.newId(), null, t.now(), t.now());
        t.repos.courses().insert(imported);
        assertThat(messageOf(() -> practice.start(SessionRequest.studyNext(imported.id()))))
                .isEqualTo(StudyMessages.NOT_STUDYING);

        Course mine = t.course("Draft course");
        assertThat(practice.start(SessionRequest.studyNext(mine.id())).courseId()).isEqualTo(mine.id());
    }

    @Test
    void aHintHidesWrongOptionsAndCapsTheRating() {
        LearningItem mcq = item(ItemType.MCQ, "Which part holds the DNA?");
        LearningItem cloze = item(ItemType.CLOZE, "The ___ holds the DNA.");
        LearningItem statement = item(ItemType.TRUE_FALSE, "The nucleus holds the DNA.");
        StudySession session = start(SessionRequest.studyNext(course.id()));

        Hint hint = practice.hint(session.id(), mcq.id());
        assertThat(hint.text()).isEqualTo("One wrong answer is hidden.");
        assertThat(hint.removedChoices()).hasSize(1).isSubsetOf("ribosome", "membrane");
        assertThat(practice.hint(session.id(), cloze.id()).text()).isEqualTo("It starts with “n” and has 7 letters.");
        assertThat(practice.hint(session.id(), statement.id()).text()).isEqualTo("Look back at page 5 of “Cell Biology”.");

        Feedback feedback = practice.submit(session.id(), Response.answer(mcq.id(), "nucleus", Confidence.CERTAIN, false, 1));
        assertThat(feedback.rating()).isEqualTo(Rating.HARD);
        assertThat(t.repos.sessions().findAttempt(feedback.attemptId()).orElseThrow().hintUsed()).isTrue();
        assertThat(messageOf(() -> practice.hint(session.id(), mcq.id()))).isEqualTo(StudyMessages.ALREADY_ANSWERED);

        LearningItem card = item(ItemType.FLASHCARD, "Nucleus");
        StudySession cards = start(SessionRequest.flashcards(course.id(), null));
        assertThat(cards.items()).extracting(SessionItem::itemId).containsExactly(card.id());
        assertThat(cards.items().getFirst().hintAvailable()).isFalse();
        assertThat(messageOf(() -> practice.hint(cards.id(), card.id()))).isEqualTo(StudyMessages.NO_HINT);
    }

    @Test
    void flashcardsAreRatedByTheLearner() {
        LearningItem card = item(ItemType.FLASHCARD, "Where is the DNA kept?");
        item(ItemType.MCQ, "Which part holds the DNA?");
        StudySession session = start(SessionRequest.flashcards(course.id(), null));

        SessionItem shown = session.items().getFirst();
        assertThat(session.items()).hasSize(1);
        assertThat(shown.flashcardBack()).isEqualTo("nucleus");
        assertThat(shown.choices()).isEmpty();

        assertThat(messageOf(() -> practice.submit(session.id(), Response.answer(card.id(), "", null, false, 1))))
                .isEqualTo(StudyMessages.RATE_THE_CARD);
        Feedback feedback = practice.submit(session.id(), Response.flashcard(card.id(), Rating.EASY, 2_000));
        assertThat(feedback.rating()).isEqualTo(Rating.EASY);
        assertThat(feedback.gradedBy()).isEqualTo(GradedBy.SELF);
        // Easy on a new card skips learning: it is due in whole days.
        assertThat(feedback.intervalDays()).isPositive();
        assertThat(t.repos.reviews().find(card.id()).orElseThrow().phase()).isEqualTo(ReviewPhase.REVIEW);
        assertThat(t.repos.sessions().findAttempt(feedback.attemptId()).orElseThrow().response()).isEqualTo("easy");
    }

    @Test
    void shortAnswersAreCheckedByTheAssistantAndCanBeDisputed() {
        LearningItem question = item(ItemType.SHORT_ANSWER, "What does the nucleus do?",
                "It stores the genetic material and controls the cell.");
        StudySession session = start(SessionRequest.studyNext(course.id()));
        nextGrade.set(new GradeResult(0.3, GradeResult.Verdict.INCORRECT, "This misses what the nucleus stores.",
                List.of("genetic material")));
        long answeredAt = t.now();

        Feedback graded = practice.submit(session.id(),
                Response.answer(question.id(), "It makes energy.", Confidence.FAIRLY_SURE, false, 30_000));
        assertThat(graded.correct()).isFalse();
        assertThat(graded.rating()).isEqualTo(Rating.AGAIN);
        assertThat(graded.gradedBy()).isEqualTo(GradedBy.AI);
        assertThat(graded.graderFeedback()).isEqualTo("This misses what the nucleus stores.");
        assertThat(graded.missingPoints()).containsExactly("genetic material");
        assertThat(graded.disputable()).isTrue();
        assertThat(graded.nextDueAt()).isEqualTo(answeredAt + MINUTE);

        t.clock.advance(Duration.ofSeconds(30));
        clearEvents();
        Feedback disputed = practice.dispute(graded.attemptId());
        assertThat(disputed.rating()).isEqualTo(Rating.GOOD);
        assertThat(disputed.correct()).isTrue();
        assertThat(disputed.disputed()).isTrue();
        assertThat(disputed.disputable()).isFalse();
        // The schedule is rebuilt as if the answer had been graded Good when it was given.
        ReviewState expected = FsrsScheduler.defaults().review(ReviewState.newItem(question.id(), answeredAt), Rating.GOOD,
                answeredAt);
        assertThat(disputed.nextDueAt()).isEqualTo(expected.dueAt()).isEqualTo(answeredAt + 10 * MINUTE);
        ReviewState stored = t.repos.reviews().find(question.id()).orElseThrow();
        assertThat(stored.dueAt()).isEqualTo(expected.dueAt());
        assertThat(stored.stability()).isEqualTo(expected.stability());
        assertThat(stored.reps()).isEqualTo(1);
        Attempt attempt = t.repos.sessions().findAttempt(graded.attemptId()).orElseThrow();
        assertThat(attempt.rating()).isEqualTo(Rating.GOOD);
        assertThat(attempt.disputed()).isTrue();
        assertThat(events()).contains(new ProgressChanged(course.id()));

        assertThat(practice.dispute(graded.attemptId())).isEqualTo(disputed);
        assertThat(messageOf(() -> practice.submit(session.id(), Response.answer(question.id(), "again", null, false, 1))))
                .as("a disputed answer counts as Good, so the item does not come back")
                .isEqualTo(StudyMessages.ALREADY_ANSWERED);

        LearningItem mcq = item(ItemType.MCQ, "Which part holds the DNA?");
        StudySession quiz = start(SessionRequest.studyNext(course.id()));
        Feedback ruled = practice.submit(quiz.id(), Response.answer(mcq.id(), "ribosome", null, false, 1));
        assertThat(messageOf(() -> practice.dispute(ruled.attemptId()))).isEqualTo(StudyMessages.NOT_DISPUTABLE);
        assertThat(messageOf(() -> practice.dispute(Ids.newId()))).isEqualTo(StudyMessages.ANSWER_NOT_FOUND);
    }

    @Test
    void aBlankShortAnswerIsNotSentForChecking() {
        LearningItem question = item(ItemType.SHORT_ANSWER, "What does the nucleus do?", "It stores the DNA.");
        nextGrade.set(null);
        StudySession session = start(SessionRequest.studyNext(course.id()));
        Feedback feedback = practice.submit(session.id(), Response.answer(question.id(), " ", null, false, 1));
        assertThat(feedback.rating()).isEqualTo(Rating.AGAIN);
        assertThat(feedback.gradedBy()).isEqualTo(GradedBy.RULE);
        assertThat(feedback.disputable()).isFalse();
    }

    @Test
    void finishingSummarizesTheSession() {
        Concept nucleus = t.concept(course, null, "Nucleus");
        LearningItem a = item(ItemType.MCQ, "Which part holds the DNA?");
        LearningItem b = item(ItemType.CLOZE, "The ___ holds the DNA.");
        LearningItem c = item(ItemType.TRUE_FALSE, "The nucleus holds the DNA.");
        LearningItem untouched = item(ItemType.TRUE_FALSE, "The DNA sits in the cytoplasm.", "false");
        for (LearningItem item : List.of(a, b, c, untouched)) {
            data.tag(item, nucleus);
        }
        StudySession session = start(SessionRequest.studyNext(course.id()));
        assertThat(session.items()).hasSize(4);
        assertThat(session.items().getFirst().conceptNames()).containsExactly("Nucleus");

        practice.submit(session.id(), Response.answer(a.id(), "nucleus", Confidence.CERTAIN, false, 5_000));
        practice.submit(session.id(), Response.answer(b.id(), "ribosome", null, false, 5_000));
        practice.submit(session.id(), Response.answer(c.id(), "true", null, false, 5_000));
        practice.submit(session.id(), Response.answer(b.id(), "nucleus", null, false, 5_000));
        t.clock.advance(Duration.ofMinutes(5));
        clearEvents();

        SessionSummary summary = practice.finish(session.id());
        assertThat(summary.kind()).isEqualTo(SessionKind.STUDY_NEXT);
        assertThat(summary.courseId()).isEqualTo(course.id());
        assertThat(summary.itemCount()).isEqualTo(4);
        assertThat(summary.answered()).isEqualTo(3);
        assertThat(summary.correct()).as("each item's first answer counts").isEqualTo(2);
        assertThat(summary.accuracy()).isCloseTo(2.0 / 3, within(1e-9));
        assertThat(summary.durationMs()).isEqualTo(Duration.ofMinutes(5).toMillis());
        assertThat(summary.newItemsLearned()).isEqualTo(3);
        assertThat(summary.improved()).extracting(ConceptChange::name).containsExactly("Nucleus");
        assertThat(summary.improved().getFirst().before()).isNull();
        assertThat(summary.improved().getFirst().after()).isBetween(0.6, 1.0);
        assertThat(summary.nextReviewAt()).isGreaterThan(t.now());
        assertThat(summary.streakDays()).isEqualTo(1);
        assertThat(events()).containsExactly(new ProgressChanged(course.id()));
        assertThat(t.repos.sessions().findSession(session.id()).orElseThrow().isFinished()).isTrue();

        t.clock.advance(Duration.ofMinutes(5));
        SessionSummary again = practice.finish(session.id());
        assertThat(again.durationMs()).isEqualTo(summary.durationMs());
        assertThat(again.answered()).isEqualTo(3);
        assertThat(again.correct()).isEqualTo(2);
        assertThat(again.newItemsLearned()).isEqualTo(3);
    }

    @Test
    void theDailyNewItemLimitHoldsAcrossSessions() {
        settings.set(SettingKeys.NEW_ITEMS_PER_DAY, 3);
        List<LearningItem> items = List.of(item(ItemType.CLOZE, "one"), item(ItemType.CLOZE, "two"),
                item(ItemType.CLOZE, "three"), item(ItemType.CLOZE, "four"), item(ItemType.CLOZE, "five"));

        StudyNext before = practice.studyNext(course.id());
        assertThat(before.newCount()).isEqualTo(3);
        assertThat(before.dueCount()).isZero();

        StudySession first = start(SessionRequest.studyNext(course.id()));
        assertThat(first.items()).extracting(SessionItem::prompt).containsExactly("one", "two", "three");
        practice.submit(first.id(), Response.answer(items.get(0).id(), "nucleus", null, false, 1));
        practice.submit(first.id(), Response.answer(items.get(1).id(), "nucleus", null, false, 1));
        practice.finish(first.id());

        StudySession second = start(SessionRequest.studyNext(course.id()));
        assertThat(second.items()).extracting(SessionItem::prompt).containsExactly("three");
        assertThat(practice.studyNext(null).newCount()).isEqualTo(1);

        // Tomorrow: the two items learned today are due and three new ones are allowed again.
        t.clock.advance(Duration.ofDays(1));
        StudySession tomorrow = start(SessionRequest.studyNext(course.id()));
        assertThat(tomorrow.items()).extracting(SessionItem::isNew).containsExactly(false, true, true, false, true);
        assertThat(tomorrow.items()).extracting(SessionItem::prompt)
                .containsExactlyInAnyOrder("one", "two", "three", "four", "five");
        StudyNext next = practice.studyNext(course.id());
        assertThat(next.dueCount()).isEqualTo(2);
        assertThat(next.newCount()).isEqualTo(3);
        assertThat(next.estimatedMinutes()).isPositive();
    }

    @Test
    void aModuleQuizCoversOneModule() {
        CourseModule cells = t.module(course, "Cells");
        CourseModule tissues = t.module(course, "Tissues");
        LearningItem inModule = t.item(course, cells, ItemType.MCQ, ItemStatus.APPROVED, "In cells");
        t.item(course, cells, ItemType.MCQ, ItemStatus.DRAFT, "Draft in cells");
        t.item(course, tissues, ItemType.MCQ, ItemStatus.APPROVED, "In tissues");

        StudySession quiz = start(SessionRequest.moduleQuiz(course.id(), cells.id()));
        assertThat(quiz.moduleId()).isEqualTo(cells.id());
        assertThat(quiz.moduleTitle()).isEqualTo("Cells");
        assertThat(quiz.items()).extracting(SessionItem::itemId).containsExactly(inModule.id());
        assertThat(quiz.items().getFirst().moduleTitle()).isEqualTo("Cells");

        Feedback feedback = practice.submit(quiz.id(), Response.answer(inModule.id(), "answer", null, false, 1));
        assertThat(feedback.correct()).isTrue();
        assertThat(feedback.source()).isNull();
        SessionSummary summary = practice.finish(quiz.id());
        assertThat(summary.kind()).isEqualTo(SessionKind.MODULE_QUIZ);
        assertThat(summary.improved()).isEmpty();
    }

    @Test
    void sessionsAcrossCoursesCoverEveryCourseBeingStudied() {
        Course other = t.enrolledCourse("Chemistry");
        LearningItem here = item(ItemType.CLOZE, "Biology item");
        LearningItem there = t.item(other, null, ItemType.CLOZE, ItemStatus.APPROVED, "Chemistry item");
        t.repos.courses().leave(other.id());
        StudySession session = start(SessionRequest.studyNext());
        assertThat(session.courseId()).isNull();
        assertThat(session.items()).extracting(SessionItem::itemId).containsExactly(here.id());
        assertThat(there.courseId()).isEqualTo(other.id());
        assertThat(practice.studyNext(null).newCount()).isEqualTo(1);
        assertThat(practice.studyNext(null).courseId()).isNull();
    }
}
