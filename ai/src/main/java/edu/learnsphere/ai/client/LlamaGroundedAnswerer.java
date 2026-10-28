package edu.learnsphere.ai.client;

import edu.learnsphere.ai.prompt.PassageFormat;
import edu.learnsphere.ai.prompt.Prompts;
import edu.learnsphere.core.spi.AnswerRequest;
import edu.learnsphere.core.spi.ChatPriority;
import edu.learnsphere.core.spi.GroundedAnswer;
import edu.learnsphere.core.spi.GroundedAnswerer;
import edu.learnsphere.core.spi.QuestionReader;
import edu.learnsphere.core.spi.TokenSink;
import edu.learnsphere.core.util.CancellationToken;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * {@link GroundedAnswerer} on the chat model at {@link ChatPriority#INTERACTIVE}. The rules
 * ({@code answer-system.txt}) make it a tutor that replies to anything the learner writes, takes every fact from
 * the numbered passages with a {@code [n]} citation, says in its own words when the course doesn't cover
 * something, and quizzes one question at a time. Worked examples follow the rules: a cited answer, a related
 * question the passages don't answer, and "quiz me" with a wrong answer marked. The conversation's earlier exchanges come next as
 * chat turns, then the learner's message with the course, its topics and the passages. Thread-safe.
 */
public final class LlamaGroundedAnswerer implements GroundedAnswerer {

    /** Longest reply, in tokens. */
    public static final int MAX_TOKENS = 700;
    /** Passage text budget per prompt, in characters (about 7,000 tokens). */
    public static final int PASSAGE_BUDGET = 28_000;
    /** Longest message sent, in characters. */
    public static final int MAX_MESSAGE_CHARS = 2_000;
    /** Inline maths that holds at least one LaTeX command. */
    private static final Pattern INLINE_MATH = Pattern.compile("\\$([^$\n]*\\\\[A-Za-z][^$\n]*)\\$");
    /** LaTeX commands (as patterns) and the symbols shown instead. */
    private static final Map<String, String> SYMBOLS = symbols();
    /** The course and topics the worked examples use. */
    private static final String EXAMPLE_COURSE = "Biology";
    private static final String EXAMPLE_TOPICS = "Photosynthesis, Chloroplasts, The light reactions, The Calvin cycle";

    private final LocalChat chat;
    private final double temperature;

    public LlamaGroundedAnswerer(LocalChat chat) {
        this(chat, 0.3);
    }

    public LlamaGroundedAnswerer(LocalChat chat, double temperature) {
        this.chat = Objects.requireNonNull(chat, "chat");
        this.temperature = temperature;
    }

    @Override
    public GroundedAnswer answer(AnswerRequest request, TokenSink sink, CancellationToken token) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(sink, "sink");
        Objects.requireNonNull(token, "token");
        token.throwIfCancelled();
        String message = request.message().strip();
        if (request.passages().isEmpty() || message.isEmpty()) {
            return GroundedAnswer.insufficientEvidence();
        }
        if (message.length() > MAX_MESSAGE_CHARS) {
            message = message.substring(0, MAX_MESSAGE_CHARS);
        }
        String last = request.earlier().isEmpty() ? "" : request.earlier().getLast().answer();
        String user = user(request.course(), request.topics().isEmpty() ? "" : String.join(", ", request.topics()),
                PassageFormat.format(PassageFormat.fit(request.passages(), PASSAGE_BUDGET)), last, message);
        ChatRequest chatRequest = ChatRequest.text(messages(request.earlier(), user), MAX_TOKENS, temperature);
        String text = plainSymbols(chat.complete(ChatPriority.INTERACTIVE, chatRequest, sink, token).text()).strip();
        return text.isEmpty() ? GroundedAnswer.insufficientEvidence() : new GroundedAnswer(text, false);
    }

    /**
     * The rules, the worked examples, the earlier exchanges, then the learner's message. The rules and examples
     * are the same for every message, so the server reuses them from its prompt cache.
     */
    static List<ChatMessage> messages(List<QuestionReader.Turn> earlier, String user) {
        String examplePassages = Prompts.ANSWER_EXAMPLE_PASSAGES.render();
        List<ChatMessage> messages = new ArrayList<>(List.of(
                ChatMessage.system(Prompts.ANSWER_SYSTEM.render()),
                ChatMessage.user(user(EXAMPLE_COURSE, EXAMPLE_TOPICS, examplePassages, "",
                        Prompts.ANSWER_EXAMPLE_QUESTION.render())),
                ChatMessage.assistant(Prompts.ANSWER_EXAMPLE_ANSWER.render()),
                ChatMessage.user(user(EXAMPLE_COURSE, EXAMPLE_TOPICS, examplePassages, "",
                        Prompts.ANSWER_EXAMPLE_UNCOVERED_QUESTION.render())),
                ChatMessage.assistant(Prompts.ANSWER_EXAMPLE_UNCOVERED.render()),
                ChatMessage.user(user(EXAMPLE_COURSE, EXAMPLE_TOPICS, examplePassages, "", "quiz me")),
                ChatMessage.assistant(Prompts.ANSWER_EXAMPLE_QUIZ.render()),
                ChatMessage.user(user(EXAMPLE_COURSE, EXAMPLE_TOPICS, examplePassages,
                        Prompts.ANSWER_EXAMPLE_QUIZ.render(), Prompts.ANSWER_EXAMPLE_QUIZ_REPLY.render())),
                ChatMessage.assistant(Prompts.ANSWER_EXAMPLE_QUIZ_MARKED.render())));
        for (QuestionReader.Turn turn : earlier) {
            messages.add(ChatMessage.user(turn.question()));
            messages.add(ChatMessage.assistant(turn.answer()));
        }
        messages.add(ChatMessage.user(user));
        return List.copyOf(messages);
    }

    /**
     * The learner's message with the course, its topics and the passages. {@code last} is the assistant's previous
     * reply, repeated next to the message so that an answer to a quiz question is read as one (empty for none).
     */
    private static String user(String course, String topics, String passages, String last, String message) {
        return Prompts.ANSWER_USER.render(Map.of("course", course, "topics", topics, "passages", passages,
                "last", last.isBlank() ? "" : "You last said: " + last.strip() + "\n\n", "message", message));
    }

    /** LaTeX the model sometimes writes for logic and maths, such as {@code $p \Rightarrow q$}, as plain symbols. */
    static String plainSymbols(String text) {
        Matcher math = INLINE_MATH.matcher(text);
        StringBuilder out = new StringBuilder();
        while (math.find()) {
            math.appendReplacement(out, Matcher.quoteReplacement(math.group(1)));
        }
        math.appendTail(out);
        String result = out.toString();
        for (Map.Entry<String, String> symbol : SYMBOLS.entrySet()) {
            result = result.replaceAll("\\\\" + symbol.getKey() + "(?![A-Za-z])", Matcher.quoteReplacement(symbol.getValue()));
        }
        return result;
    }

    private static Map<String, String> symbols() {
        Map<String, String> symbols = new LinkedHashMap<>();
        symbols.put("Leftrightarrow", "⇔");
        symbols.put("leftrightarrow", "↔");
        symbols.put("Rightarrow", "⇒");
        symbols.put("rightarrow", "→");
        symbols.put("implies", "⇒");
        symbols.put("iff", "⇔");
        symbols.put("forall", "∀");
        symbols.put("exists", "∃");
        symbols.put("lnot", "¬");
        symbols.put("neg", "¬");
        symbols.put("land", "∧");
        symbols.put("wedge", "∧");
        symbols.put("lor", "∨");
        symbols.put("vee", "∨");
        symbols.put("notin", "∉");
        symbols.put("in", "∈");
        symbols.put("subseteq", "⊆");
        symbols.put("subset", "⊂");
        symbols.put("cup", "∪");
        symbols.put("cap", "∩");
        symbols.put("emptyset", "∅");
        symbols.put("neq", "≠");
        symbols.put("leq", "≤");
        symbols.put("geq", "≥");
        symbols.put("times", "×");
        symbols.put("cdot", "·");
        symbols.put("mathbb\\{N\\}", "ℕ");
        symbols.put("mathbb\\{Z\\}", "ℤ");
        return Collections.unmodifiableMap(symbols);
    }
}
