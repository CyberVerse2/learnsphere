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
 * streamed and turns the result into {@link GroundedAnswer#insufficientEvidence()}. Thread-safe.
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
        this(chat, 0.2);
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
        ChatRequest request = ChatRequest.text(List.of(
                ChatMessage.system(Prompts.ANSWER_SYSTEM.render()),
                ChatMessage.user(user)), MAX_TOKENS, temperature);
        SentinelStream stream = new SentinelStream(Prompts.NOT_IN_MATERIAL, sink);
        chat.complete(ChatPriority.INTERACTIVE, request, stream, token);
        stream.finish();
        String text = stream.text().strip();
        if (stream.refused() || text.isEmpty()) {
            return GroundedAnswer.insufficientEvidence();
        }
        return new GroundedAnswer(text, false);
    }
}
