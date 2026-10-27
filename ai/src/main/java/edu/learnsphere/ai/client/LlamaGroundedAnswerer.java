package edu.learnsphere.ai.client;

import edu.learnsphere.ai.prompt.PassageFormat;
import edu.learnsphere.ai.prompt.Prompts;
import edu.learnsphere.core.spi.ChatPriority;
import edu.learnsphere.core.spi.GroundedAnswer;
import edu.learnsphere.core.spi.GroundedAnswerer;
import edu.learnsphere.core.spi.Passage;
import edu.learnsphere.core.spi.TokenSink;
import edu.learnsphere.core.util.CancellationToken;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * {@link GroundedAnswerer} on the chat model at {@link ChatPriority#INTERACTIVE}. The prompt
 * ({@code answer-system.txt}) asks for plain language, a {@code [n]} citation after each claim, and the
 * sentinel {@link Prompts#NOT_IN_MATERIAL} when the passages don't hold the answer; the sentinel is never
 * streamed and turns the result into {@link GroundedAnswer#insufficientEvidence()}. A reply that refuses in
 * its own words instead (it opens by saying the material doesn't cover the question and cites nothing) is
 * held back while it streams ({@link RefusalOpeningFilter}) and also becomes insufficient evidence, so the
 * learner never sees text that is then taken back. Thread-safe.
 */
public final class LlamaGroundedAnswerer implements GroundedAnswerer {

    /** Longest answer, in tokens. */
    public static final int MAX_TOKENS = 700;
    /** Passage text budget per prompt, in characters (about 7,000 tokens). */
    public static final int PASSAGE_BUDGET = 28_000;
    /** Longest question sent, in characters. */
    public static final int MAX_QUESTION_CHARS = 2_000;

    private final LocalChat chat;
    private final double temperature;

    public LlamaGroundedAnswerer(LocalChat chat) {
        this(chat, 0.1);
    }

    public LlamaGroundedAnswerer(LocalChat chat, double temperature) {
        this.chat = Objects.requireNonNull(chat, "chat");
        this.temperature = temperature;
    }

    @Override
    public GroundedAnswer answer(String question, List<Passage> passages, TokenSink sink, CancellationToken token) {
        Objects.requireNonNull(passages, "passages");
        Objects.requireNonNull(sink, "sink");
        Objects.requireNonNull(token, "token");
        token.throwIfCancelled();
        String q = question == null ? "" : question.strip();
        if (passages.isEmpty() || q.isEmpty()) {
            return GroundedAnswer.insufficientEvidence();
        }
        if (q.length() > MAX_QUESTION_CHARS) {
            q = q.substring(0, MAX_QUESTION_CHARS);
        }
        List<Passage> fitted = PassageFormat.fit(passages, PASSAGE_BUDGET);
        String user = Prompts.ANSWER_USER.render(Map.of(
                "passages", PassageFormat.format(fitted),
                "question", q));
        ChatRequest request = ChatRequest.text(messages(user), MAX_TOKENS, temperature);
        RefusalOpeningFilter opening = new RefusalOpeningFilter(sink);
        SentinelStream stream = new SentinelStream(Prompts.NOT_IN_MATERIAL, opening);
        chat.complete(ChatPriority.INTERACTIVE, request, stream, token);
        stream.finish();
        opening.finish();
        String text = stream.text().strip();
        if (stream.refused() || text.isEmpty() || opening.refused()) {
            return GroundedAnswer.insufficientEvidence();
        }
        return new GroundedAnswer(text, false);
    }

    /**
     * True when the answer opens, before any citation, by saying the material doesn't cover the question,
     * and cites no passage anywhere.
     */
    static boolean refusesInItsOwnWords(String text) {
        return RefusalOpeningFilter.refuses(text);
    }

    /**
     * The system rules, two worked examples (a cited answer, and a related question the passages don't
     * answer, replied to with the sentinel), then the student's question. The fixed prefix is the same for
     * every question, so the server reuses it from its prompt cache.
     */
    static List<ChatMessage> messages(String user) {
        String examplePassages = Prompts.ANSWER_EXAMPLE_PASSAGES.render();
        return List.of(
                ChatMessage.system(Prompts.ANSWER_SYSTEM.render()),
                ChatMessage.user(Prompts.ANSWER_USER.render(Map.of("passages", examplePassages,
                        "question", Prompts.ANSWER_EXAMPLE_QUESTION.render()))),
                ChatMessage.assistant(Prompts.ANSWER_EXAMPLE_ANSWER.render()),
                ChatMessage.user(Prompts.ANSWER_USER.render(Map.of("passages", examplePassages,
                        "question", Prompts.ANSWER_EXAMPLE_REFUSAL.render()))),
                ChatMessage.assistant(Prompts.NOT_IN_MATERIAL),
                ChatMessage.user(user));
    }
}
