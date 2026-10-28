package edu.learnsphere.core.service.impl.pipeline;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import edu.learnsphere.core.error.InvalidInputException;
import edu.learnsphere.core.error.LearnSphereException;
import edu.learnsphere.core.error.NotFoundException;
import edu.learnsphere.core.error.UnavailableException;
import edu.learnsphere.core.error.UserMessage;
import edu.learnsphere.core.model.ChatMessage;
import edu.learnsphere.core.model.ChatRole;
import edu.learnsphere.core.model.ChatThread;
import edu.learnsphere.core.model.Citation;
import edu.learnsphere.core.model.Concept;
import edu.learnsphere.core.model.Course;
import edu.learnsphere.core.model.CourseOrigin;
import edu.learnsphere.core.model.CourseStatus;
import edu.learnsphere.core.model.DocumentStatus;
import edu.learnsphere.core.model.MessageStatus;
import edu.learnsphere.core.model.Visibility;
import edu.learnsphere.core.service.Answer;
import edu.learnsphere.core.service.AnswerListener;
import edu.learnsphere.core.service.AskPhase;
import edu.learnsphere.core.service.RecentQuestion;
import edu.learnsphere.core.service.SettingKeys;
import edu.learnsphere.core.service.ThreadSummary;
import edu.learnsphere.core.spi.AnswerRequest;
import edu.learnsphere.core.spi.EmbedKind;
import edu.learnsphere.core.spi.Embedder;
import edu.learnsphere.core.spi.GroundedAnswer;
import edu.learnsphere.core.spi.GroundedAnswerer;
import edu.learnsphere.core.spi.Passage;
import edu.learnsphere.core.spi.QuestionReader;
import edu.learnsphere.core.spi.QuestionReading;
import edu.learnsphere.core.spi.TokenSink;
import edu.learnsphere.core.util.Cancellable;
import edu.learnsphere.core.util.CancellationToken;
import edu.learnsphere.core.util.Ids;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AskServiceImplTest {

    private static final String MITOCHONDRIA =
            "# Mitochondria\nMitochondria release energy from glucose during cellular respiration.";
    private static final String RIBOSOMES = "# Ribosomes\nRibosomes build proteins from amino acids.";
    private static final String MEMBRANE = "# Membranes\nThe cell membrane controls what enters and leaves the cell.";

    @TempDir
    Path dir;

    private PipelineFixture f;
    private PipelineFixture.Material material;
    private AskServiceImpl ask;

    @BeforeEach
    void setUp() {
        f = new PipelineFixture(dir);
        material = f.readyMaterial(MITOCHONDRIA, RIBOSOMES, MEMBRANE);
        ask = f.ask();
    }

    @AfterEach
    void tearDown() {
        ask.close();
        f.close();
    }

    private ChatThread thread() {
        return ask.createThread(material.course().id(), null);
    }

    private AskServiceImpl askWith(Embedder embedder, GroundedAnswerer answerer) {
        return new AskServiceImpl(f.repos, f.settings, f.index, embedder, f.ai.reranker(), answerer, f.ai.questionReader());
    }

    private AskServiceImpl askWith(QuestionReader reader, GroundedAnswerer answerer) {
        return new AskServiceImpl(f.repos, f.settings, f.index, f.ai.embedder(), f.ai.reranker(), answerer, reader);
    }

    // ---- every message reaches the assistant ----------------------------------------------------------

    @Test
    void aGreetingReachesTheAssistantWithTheCourseAndPassagesFromIt() {
        CapturingAnswerer answerer = new CapturingAnswerer(f.ai.answerer());
        AskServiceImpl service = askWith(f.ai.questionReader(), answerer);
        ChatThread thread = thread();
        Recorder recorder = new Recorder();

        service.ask(thread.id(), "hello", recorder);

        Answer answer = recorder.awaitAnswer();
        service.close();
        assertThat(answer.status()).isEqualTo(MessageStatus.COMPLETE);
        assertThat(answer.text()).isEqualTo("Hi! What would you like to go over in Biology?");
        assertThat(answer.citations()).isEmpty();
        assertThat(recorder.phases).containsExactly(AskPhase.SEARCHING, AskPhase.WRITING);
        AnswerRequest request = answerer.last();
        assertThat(request.course()).isEqualTo("Biology");
        assertThat(request.topics()).containsExactly("Cells");
        assertThat(request.message()).isEqualTo("hello");
        assertThat(request.earlier()).isEmpty();
        assertThat(request.passages()).as("passages picked from the course").hasSize(3)
                .extracting(Passage::number).containsExactly(1, 2, 3);
    }

    @Test
    void aQuestionTheCourseDoesNotCoverReachesTheAssistantToSaySo() {
        CapturingAnswerer answerer = new CapturingAnswerer(f.ai.answerer());
        AskServiceImpl service = askWith(f.ai.questionReader(), answerer);
        ChatThread thread = thread();
        Recorder recorder = new Recorder();

        service.ask(thread.id(), "What is the capital of France?", recorder);

        Answer answer = recorder.awaitAnswer();
        service.close();
        assertThat(answer.status()).isEqualTo(MessageStatus.COMPLETE);
        assertThat(answer.text()).isEqualTo("Your course material doesn't cover that.");
        assertThat(answer.citations()).isEmpty();
        assertThat(answerer.last().passages()).isNotEmpty().allSatisfy(p -> assertThat(p.relevance()).isZero());
    }

    @Test
    void aFollowUpIsSearchedAsTheQuestionItStandsForAndRepliedToWithTheConversation() {
        List<List<QuestionReader.Turn>> seen = new CopyOnWriteArrayList<>();
        QuestionReader reader = (message, earlier, token) -> {
            seen.add(earlier);
            return message.equals("and what do they build them from?")
                    ? QuestionReading.asIs("What do ribosomes build proteins from?") : QuestionReading.asIs(message);
        };
        CapturingAnswerer answerer = new CapturingAnswerer(f.ai.answerer());
        AskServiceImpl service = askWith(reader, answerer);
        ChatThread thread = thread();
        Recorder first = new Recorder();
        service.ask(thread.id(), "How do ribosomes build proteins?", first);
        first.awaitAnswer();

        Recorder second = new Recorder();
        service.ask(thread.id(), "and what do they build them from?", second);

        Answer answer = second.awaitAnswer();
        service.close();
        assertThat(answer.citations()).extracting(Citation::pageStart).containsExactly(2);
        assertThat(seen).as("only the follow-up is read").hasSize(1);
        assertThat(seen.getFirst()).singleElement().satisfies(turn -> {
            assertThat(turn.question()).isEqualTo("How do ribosomes build proteins?");
            assertThat(turn.answer()).isEqualTo("Your material says: Ribosomes build proteins from amino acids.");
        });
        AnswerRequest request = answerer.last();
        assertThat(request.message()).isEqualTo("and what do they build them from?");
        assertThat(request.earlier()).isEqualTo(seen.getFirst());
        assertThat(ask.messages(thread.id()).get(2).content()).isEqualTo("and what do they build them from?");
    }

    @Test
    void aMessageWithNothingToSearchForKeepsTheTopicOfTheLastAnswer() {
        CapturingAnswerer answerer = new CapturingAnswerer(f.ai.answerer());
        AskServiceImpl service = askWith(f.ai.questionReader(), answerer);
        ChatThread thread = thread();
        Recorder first = new Recorder();
        service.ask(thread.id(), "How do ribosomes build proteins?", first);
        String cited = first.awaitAnswer().citations().getFirst().chunkId();
        Recorder second = new Recorder();

        service.ask(thread.id(), "quiz me", second);

        assertThat(second.awaitAnswer().text()).isEqualTo("Here's one: what does your course say about Ribosomes?");
        service.close();
        assertThat(answerer.last().passages()).extracting(Passage::chunkId).containsExactly(cited);
    }

    @Test
    void aFollowUpThatCannotBeReadIsSearchedAsTyped() {
        QuestionReader broken = (message, earlier, token) -> {
            throw new IllegalStateException("no reply");
        };
        AskServiceImpl service = askWith(broken, f.ai.answerer());
        ChatThread thread = thread();
        Recorder first = new Recorder();
        service.ask(thread.id(), "How do mitochondria release energy?", first);
        first.awaitAnswer();
        Recorder second = new Recorder();

        service.ask(thread.id(), "How do ribosomes build proteins?", second);

        assertThat(second.awaitAnswer().citations()).extracting(Citation::pageStart).contains(2);
        service.close();
    }

    @Test
    void onlyMessagesAfterAnAnswerAreSentToTheReader() {
        AtomicInteger reads = new AtomicInteger();
        QuestionReader counting = (message, earlier, token) -> {
            reads.incrementAndGet();
            return QuestionReading.asIs(message);
        };
        AskServiceImpl service = askWith(counting, f.ai.answerer());
        ChatThread thread = thread();
        Recorder question = new Recorder();
        service.ask(thread.id(), "How do ribosomes build proteins?", question);
        question.awaitAnswer();
        assertThat(reads).hasValue(0);
        Recorder thanks = new Recorder();

        service.ask(thread.id(), "thanks!", thanks);

        thanks.awaitAnswer();
        service.close();
        assertThat(reads).hasValue(1);
    }

    @Test
    void topicsComeFromModulesThenConceptsThenTheHeadingsOfThePdfs() {
        assertThat(ask.topics(material.course().id(), 6)).containsExactly("Cells");

        Course notes = f.t.course("Logic");
        f.addPdf(notes, null, "Reading guide", FakePdf.document()
                .page("# 1.1 Formal methods\nFormal methods describe software precisely.")
                .page("# Example 2.2 Consider the truth table\nA truth table lists every case.")
                .page("# 2.2 Conjunction\nA conjunction is true when both parts are true.")
                .page("# Introduction\nThis guide covers logic."));
        f.importAndWait(f.repos.documents().listByCourse(notes.id()).getFirst());
        assertThat(ask.topics(notes.id(), 6)).containsExactly("Formal methods", "Conjunction");

        f.repos.concepts().insert(Concept.create(notes.id(), null, "Truth tables", "", 1L));
        assertThat(ask.topics(notes.id(), 6)).containsExactly("Truth tables");
    }

    // ---- answers -------------------------------------------------------------------------------------

    @Test
    void answersFromTheMaterialWithPageCitationsAndStoresBothMessages() {
        ChatThread thread = thread();
        Recorder recorder = new Recorder();

        ask.ask(thread.id(), "  How do ribosomes build proteins?  ", recorder);

        Answer answer = recorder.awaitAnswer();
        assertThat(recorder.ending).isEqualTo("answer");
        assertThat(answer.status()).isEqualTo(MessageStatus.COMPLETE);
        assertThat(answer.text()).isEqualTo("Your material says: Ribosomes build proteins from amino acids [1].");
        assertThat(answer.citations()).singleElement().satisfies(citation -> {
            assertThat(citation.number()).isEqualTo(1);
            assertThat(citation.documentId()).isEqualTo(material.document().id());
            assertThat(citation.documentTitle()).isEqualTo("Cell biology");
            assertThat(citation.pageStart()).isEqualTo(2);
            assertThat(citation.chunkId()).isEqualTo(f.repos.chunks().listByDocument(material.document().id()).get(1).id());
        });
        assertThat(recorder.phases).containsExactly(AskPhase.SEARCHING, AskPhase.WRITING);
        assertThat(String.join("", recorder.tokens)).isEqualTo(answer.text());
        assertThat(recorder.question).isNotNull();
        assertThat(answer.questionMessageId()).isEqualTo(recorder.question.id());

        List<ChatMessage> messages = ask.messages(thread.id());
        assertThat(messages).extracting(ChatMessage::role).containsExactly(ChatRole.USER, ChatRole.ASSISTANT);
        assertThat(messages.get(0).content()).isEqualTo("How do ribosomes build proteins?");
        assertThat(messages.get(1).id()).isEqualTo(answer.messageId());
        assertThat(messages.get(1).status()).isEqualTo(MessageStatus.COMPLETE);
        assertThat(messages.get(1).citations()).extracting(Citation::pageStart).containsExactly(2);
    }

    @Test
    void titlesAnUntitledConversationAfterItsFirstQuestion() {
        ChatThread thread = thread();
        Recorder first = new Recorder();
        ask.ask(thread.id(), "How do ribosomes build proteins?", first);
        first.awaitEnd();
        Recorder second = new Recorder();
        ask.ask(thread.id(), "What do mitochondria release from glucose?", second);
        second.awaitEnd();

        List<ThreadSummary> threads = ask.threads(material.course().id());
        assertThat(threads).singleElement().satisfies(summary -> {
            assertThat(summary.title()).isEqualTo("How do ribosomes build proteins?");
            assertThat(summary.messageCount()).isEqualTo(4);
            assertThat(summary.lastMessage()).doesNotContain("[1]").startsWith("Your material says: Mitochondria");
        });
    }

    @Test
    void keepsAGivenTitleAndShortensLongFirstQuestions() {
        ChatThread named = ask.createThread(material.course().id(), "Exam prep");
        Recorder recorder = new Recorder();
        ask.ask(named.id(), "How do ribosomes build proteins?", recorder);
        recorder.awaitEnd();
        ChatThread untitled = thread();
        String longQuestion = "How do ribosomes build proteins " + "and what else happens in the cell ".repeat(5) + "?";
        Recorder other = new Recorder();
        ask.ask(untitled.id(), longQuestion, other);
        other.awaitEnd();

        assertThat(f.repos.chats().findThread(named.id()).orElseThrow().title()).isEqualTo("Exam prep");
        String title = f.repos.chats().findThread(untitled.id()).orElseThrow().title();
        assertThat(title).hasSizeLessThanOrEqualTo(AskServiceImpl.AUTO_TITLE_LENGTH).startsWith("How do ribosomes").endsWith("…");
    }

    // ---- evidence ------------------------------------------------------------------------------------

    @Test
    void usesTheEvidenceThresholdSetting() {
        CapturingAnswerer answerer = new CapturingAnswerer(f.ai.answerer());
        AskServiceImpl service = askWith(f.ai.questionReader(), answerer);
        // Two of the three content words ("ribosomes", "proteins", "quickly") are in the passage: 0.67.
        String question = "Do ribosomes make proteins quickly?";
        f.settings.set(SettingKeys.EVIDENCE_THRESHOLD, 0.9);
        Recorder strict = new Recorder();
        service.ask(thread().id(), question, strict);
        strict.awaitAnswer();
        assertThat(answerer.last().passages()).as("nothing found: passages picked from the course")
                .allSatisfy(p -> assertThat(p.relevance()).isZero());

        f.settings.set(SettingKeys.EVIDENCE_THRESHOLD, 0.5);
        Recorder lenient = new Recorder();
        service.ask(thread().id(), question, lenient);
        assertThat(lenient.awaitAnswer().citations()).extracting(Citation::pageStart).containsExactly(2);
        service.close();
        assertThat(answerer.last().passages().getFirst().relevance()).isGreaterThanOrEqualTo(0.5);
    }

    @Test
    void markersThatPointAtNoPassageAreRemoved() {
        GroundedAnswerer uncited = (request, sink, token) -> {
            sink.accept("Ribosomes build proteins [9].");
            return new GroundedAnswer("Ribosomes build proteins [9].", false);
        };
        AskServiceImpl service = askWith(f.ai.embedder(), uncited);
        ChatThread thread = thread();
        Recorder recorder = new Recorder();

        service.ask(thread.id(), "How do ribosomes build proteins?", recorder);

        Answer answer = recorder.awaitAnswer();
        service.close();
        assertThat(answer.text()).doesNotContain("[9]").startsWith("Ribosomes build proteins");
        assertThat(answer.citations()).isEmpty();
        assertThat(answer.status()).isEqualTo(MessageStatus.COMPLETE);
    }

    @Test
    void anAssistantThatWritesNothingGivesTheInsufficientEvidenceCard() {
        GroundedAnswerer silent = (request, sink, token) -> GroundedAnswer.insufficientEvidence();
        AskServiceImpl service = askWith(f.ai.embedder(), silent);
        ChatThread thread = thread();
        Recorder recorder = new Recorder();

        service.ask(thread.id(), "How do ribosomes build proteins?", recorder);

        Answer answer = recorder.awaitInsufficient();
        service.close();
        assertThat(answer.text()).isEqualTo(AskServiceImpl.NOT_COVERED.text());
        assertThat(answer.suggestions()).containsExactly("Cells");
        assertThat(ask.messages(thread.id()).getLast().status()).isEqualTo(MessageStatus.INSUFFICIENT);
    }

    @Test
    void onlyReadyPdfsAreUsedAsEvidence() {
        f.repos.documents().updateStatus(material.document().id(), DocumentStatus.NEEDS_REVIEW, 1, null);
        ChatThread thread = thread();
        Recorder recorder = new Recorder();

        ask.ask(thread.id(), "How do ribosomes build proteins?", recorder);

        assertThat(recorder.awaitInsufficient().text()).isEqualTo(AskServiceImpl.NOTHING_READY.text());
    }

    // ---- cancellation and errors -----------------------------------------------------------------------

    @Test
    void aStoppedAnswerIsStoredAsCancelledWithWhatWasWritten() {
        CountDownLatch firstToken = new CountDownLatch(1);
        Gate gate = new Gate(1);
        GroundedAnswerer slow = (request, sink, token) -> {
            sink.accept("Your material says: ");
            sink.accept("Ribosomes build proteins [1] ");
            firstToken.countDown();
            gate.pass(token);
            return new GroundedAnswer("never", false);
        };
        AskServiceImpl service = askWith(f.ai.embedder(), slow);
        ChatThread thread = thread();
        Recorder recorder = new Recorder();

        Cancellable handle = service.ask(thread.id(), "How do ribosomes build proteins?", recorder);
        await(firstToken);
        handle.cancel();

        recorder.awaitEnd();
        service.close();
        assertThat(recorder.ending).isEqualTo("cancelled");
        ChatMessage stored = ask.messages(thread.id()).getLast();
        assertThat(stored.status()).isEqualTo(MessageStatus.CANCELLED);
        assertThat(stored.content()).isEqualTo("Your material says: Ribosomes build proteins [1]");
        assertThat(stored.citations()).extracting(Citation::pageStart).containsExactly(2);
    }

    @Test
    void closingStopsAnswersInProgress() {
        Gate gate = new Gate(1);
        GroundedAnswerer waiting = (request, sink, token) -> {
            gate.pass(token);
            return new GroundedAnswer("never", false);
        };
        AskServiceImpl service = askWith(f.ai.embedder(), waiting);
        ChatThread thread = thread();
        Recorder recorder = new Recorder();
        service.ask(thread.id(), "How do ribosomes build proteins?", recorder);
        gate.awaitReached();

        service.close();

        recorder.awaitEnd();
        assertThat(recorder.ending).isEqualTo("cancelled");
        assertThat(ask.messages(thread.id()).getLast().status()).isEqualTo(MessageStatus.CANCELLED);
        assertThatThrownBy(() -> service.ask(thread.id(), "Again?", new Recorder()))
                .isInstanceOf(LearnSphereException.class);
    }

    @Test
    void tellsTheLearnerWhenTheAssistantIsNotAvailable() {
        Embedder unavailable = new Embedder() {
            @Override
            public List<float[]> embed(List<String> texts, EmbedKind kind, CancellationToken token) {
                throw new UnavailableException(UserMessage.ASSISTANT_STARTING, "HTTP 503 from embedding role");
            }

            @Override
            public int dimensions() {
                return f.ai.embedder().dimensions();
            }

            @Override
            public String modelName() {
                return f.ai.embedder().modelName();
            }
        };
        AskServiceImpl service = askWith(unavailable, f.ai.answerer());
        ChatThread thread = thread();
        Recorder recorder = new Recorder();

        service.ask(thread.id(), "How do ribosomes build proteins?", recorder);

        recorder.awaitEnd();
        service.close();
        assertThat(recorder.error).isEqualTo(UserMessage.ASSISTANT_STARTING);
        ChatMessage stored = ask.messages(thread.id()).getLast();
        assertThat(stored.status()).isEqualTo(MessageStatus.ERROR);
        assertThat(stored.content()).isEqualTo(UserMessage.ASSISTANT_STARTING.text()).doesNotContain("503");
    }

    @Test
    void asksToTryAgainWhileTheMaterialIsBeingPrepared() {
        f.index.setNeedsRebuild(true);
        ChatThread thread = thread();
        Recorder recorder = new Recorder();

        ask.ask(thread.id(), "How do ribosomes build proteins?", recorder);

        recorder.awaitEnd();
        assertThat(recorder.error).isEqualTo(AskServiceImpl.MATERIAL_PREPARING);
    }

    // ---- checks before asking --------------------------------------------------------------------------

    @Test
    void refusesBlankQuestionsAndUnknownConversations() {
        ChatThread thread = thread();

        assertThatThrownBy(() -> ask.ask(thread.id(), "   ", new Recorder()))
                .isInstanceOfSatisfying(InvalidInputException.class,
                        e -> assertThat(e.userMessage()).isEqualTo(AskServiceImpl.BLANK_QUESTION));
        assertThatThrownBy(() -> ask.ask(Ids.newId(), "Why?", new Recorder()))
                .isInstanceOfSatisfying(NotFoundException.class,
                        e -> assertThat(e.userMessage()).isEqualTo(AskServiceImpl.THREAD_NOT_FOUND));
        assertThat(ask.messages(thread.id())).isEmpty();
    }

    @Test
    void onlyCoursesCreatedHereOrStudiedCanBeAsked() {
        Course imported = new Course(Ids.newId(), "Chemistry", "", "", "Grace", CourseOrigin.IMPORTED, Visibility.PRIVATE,
                CourseStatus.PUBLISHED, 30, 1, f.t.now(), Ids.newId(), null, f.t.now(), f.t.now());
        f.repos.courses().insert(imported);

        assertThatThrownBy(() -> ask.createThread(imported.id(), null))
                .isInstanceOfSatisfying(InvalidInputException.class,
                        e -> assertThat(e.userMessage()).isEqualTo(AskServiceImpl.NOT_STUDYING));

        f.repos.courses().enrol(imported.id(), f.t.now());
        ChatThread thread = ask.createThread(imported.id(), "Bonds");
        Recorder recorder = new Recorder();
        ask.ask(thread.id(), "What is a covalent bond?", recorder);
        assertThat(recorder.awaitInsufficient().text()).isEqualTo(AskServiceImpl.NOTHING_READY.text());
    }

    // ---- conversations ---------------------------------------------------------------------------------

    @Test
    void renamesAndDeletesConversations() {
        ChatThread thread = thread();

        ask.renameThread(thread.id(), "  Ribosomes  ");
        assertThat(ask.threads(material.course().id())).extracting(ThreadSummary::title).containsExactly("Ribosomes");
        assertThatThrownBy(() -> ask.renameThread(thread.id(), " "))
                .isInstanceOfSatisfying(InvalidInputException.class,
                        e -> assertThat(e.userMessage()).isEqualTo(AskServiceImpl.BLANK_TITLE));

        ask.deleteThread(thread.id());
        assertThat(ask.threads(material.course().id())).isEmpty();
        assertThatThrownBy(() -> ask.messages(thread.id())).isInstanceOf(NotFoundException.class);
    }

    @Test
    void listsRecentQuestionsOfCoursesShownOnHome() {
        ChatThread thread = thread();
        Recorder recorder = new Recorder();
        ask.ask(thread.id(), "How do ribosomes build proteins?", recorder);
        recorder.awaitEnd();

        List<RecentQuestion> recent = ask.recentQuestions(5);

        assertThat(recent).singleElement().satisfies(question -> {
            assertThat(question.courseTitle()).isEqualTo("Biology");
            assertThat(question.question()).isEqualTo("How do ribosomes build proteins?");
            assertThat(question.answerPreview()).isEqualTo("Your material says: Ribosomes build proteins from amino acids.");
            assertThat(question.citations()).hasSize(1);
        });
        f.t.archive(material.course());
        assertThat(ask.recentQuestions(5)).isEmpty();
        assertThat(ask.recentQuestions(0)).isEmpty();
    }

    // ---- helpers ---------------------------------------------------------------------------------------

    private static void await(CountDownLatch latch) {
        try {
            assertThat(latch.await(PipelineFixture.TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)).isTrue();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError(e);
        }
    }

    /** Records what an answer reports. */
    private static final class Recorder implements AnswerListener {

        final List<AskPhase> phases = new CopyOnWriteArrayList<>();
        final List<String> tokens = new CopyOnWriteArrayList<>();
        final CountDownLatch ended = new CountDownLatch(1);
        volatile ChatMessage question;
        volatile Answer answer;
        volatile UserMessage error;
        volatile String ending;

        @Override
        public void onQuestionSaved(ChatMessage saved) {
            question = saved;
        }

        @Override
        public void onPhase(AskPhase phase) {
            phases.add(phase);
        }

        @Override
        public void onToken(String token) {
            tokens.add(token);
        }

        @Override
        public void onAnswer(Answer value) {
            end("answer", value, null);
        }

        @Override
        public void onInsufficientEvidence(Answer value) {
            end("insufficient", value, null);
        }

        @Override
        public void onError(UserMessage message) {
            end("error", null, message);
        }

        @Override
        public void onCancelled() {
            end("cancelled", null, null);
        }

        private void end(String how, Answer value, UserMessage message) {
            if (ended.getCount() == 0) {
                throw new AssertionError("The answer ended twice");
            }
            answer = value;
            error = message;
            ending = how;
            ended.countDown();
        }

        void awaitEnd() {
            await(ended);
        }

        Answer awaitAnswer() {
            awaitEnd();
            assertThat(ending).as("how the answer ended (error: %s)", error).isEqualTo("answer");
            return answer;
        }

        Answer awaitInsufficient() {
            awaitEnd();
            assertThat(ending).as("how the answer ended (error: %s)", error).isEqualTo("insufficient");
            return answer;
        }
    }

    /** Remembers what the answerer was asked. */
    private static final class CapturingAnswerer implements GroundedAnswerer {

        private final List<AnswerRequest> requests = new CopyOnWriteArrayList<>();
        private final GroundedAnswerer delegate;

        CapturingAnswerer(GroundedAnswerer delegate) {
            this.delegate = delegate;
        }

        AnswerRequest last() {
            assertThat(requests).as("requests to the answerer").isNotEmpty();
            return requests.getLast();
        }

        @Override
        public GroundedAnswer answer(AnswerRequest request, TokenSink sink, CancellationToken token) {
            requests.add(request);
            return delegate.answer(request, sink, token);
        }
    }
}
