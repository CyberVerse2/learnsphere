package edu.learnsphere.ai.client;

import com.fasterxml.jackson.databind.JsonNode;
import edu.learnsphere.ai.prompt.JsonSchemas;
import edu.learnsphere.ai.prompt.PassageFormat;
import edu.learnsphere.ai.prompt.Prompts;
import edu.learnsphere.core.spi.ChatPriority;
import edu.learnsphere.core.spi.GradeResult;
import edu.learnsphere.core.spi.Passage;
import edu.learnsphere.core.spi.ShortAnswerGrader;
import edu.learnsphere.core.util.CancellationToken;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * {@link ShortAnswerGrader} on the chat model at {@link ChatPriority#INTERACTIVE}.
 *
 * <p>The model does not pick a score. It splits the reference answer into key points, marks each as
 * covered, partly covered or missing, and lists wrong statements ({@code grade-system.txt}); the score is
 * computed from that: covered points count 1, partly covered ½, and each wrong statement takes off
 * {@value #ERROR_PENALTY} (at most two). An answer that gets at least part of one point right and says
 * nothing wrong scores at least {@value #RIGHT_TRACK_SCORE}, so a short but correct start is partly right
 * rather than wrong. The verdict follows {@link GradeResult#verdictFor}. Missing
 * points are the points not fully covered. Feedback sentences about spelling are dropped, since spelling
 * never counts. A blank answer scores 0 without asking the model. Thread-safe.
 */
public final class LlamaShortAnswerGrader implements ShortAnswerGrader {

    /** Score taken off per wrong statement. */
    public static final double ERROR_PENALTY = 0.25;
    /** Lowest score for an answer with some of a key point and no wrong statements (a partial verdict). */
    public static final double RIGHT_TRACK_SCORE = 0.5;
    /** Longest learner answer sent, in characters. */
    public static final int MAX_ANSWER_CHARS = 4_000;

    private static final Pattern SPELLING_TALK = Pattern.compile(
            "(?i)\\b(spell\\w*|misspel\\w*|typos?|hyphen\\w*|punctuation)\\b");

    private final LocalChat chat;

    public LlamaShortAnswerGrader(LocalChat chat) {
        this.chat = Objects.requireNonNull(chat, "chat");
    }

    @Override
    public GradeResult grade(String question, String referenceAnswer, String learnerAnswer, Passage context,
                             CancellationToken token) {
        Objects.requireNonNull(token, "token");
        token.throwIfCancelled();
        String answer = learnerAnswer == null ? "" : learnerAnswer.strip();
        if (answer.isEmpty()) {
            return new GradeResult(0, GradeResult.Verdict.INCORRECT, "Write an answer to get feedback.", List.of());
        }
        if (answer.length() > MAX_ANSWER_CHARS) {
            answer = answer.substring(0, MAX_ANSWER_CHARS);
        }
        String contextText = "";
        if (context != null && !context.text().isBlank()) {
            contextText = "Source passage:\n" + PassageFormat.fit(List.of(context), 8_000).getFirst().text().strip() + "\n";
        }
        String user = Prompts.GRADE_USER.render(Map.of(
                "question", TextCleanup.oneLine(question),
                "reference", TextCleanup.oneLine(referenceAnswer),
                "context", contextText,
                "answer", answer));
        JsonNode reply = StructuredChat.complete(chat, ChatPriority.INTERACTIVE,
                List.of(ChatMessage.system(Prompts.GRADE_SYSTEM.render()), ChatMessage.user(user)),
                "grade", JsonSchemas.grade(), 600, 0.0, token);
        return toResult(reply);
    }

    static GradeResult toResult(JsonNode reply) {
        double earned = 0;
        int points = 0;
        List<String> missing = new ArrayList<>();
        for (JsonNode point : reply.path("points")) {
            String text = TextCleanup.oneLine(point.path("point").asText(""));
            String status = point.path("status").asText("missing");
            points++;
            switch (status) {
                case "covered" -> earned += 1;
                case "partly" -> {
                    earned += 0.5;
                    addPoint(missing, text);
                }
                default -> addPoint(missing, text);
            }
        }
        int errors = 0;
        for (JsonNode error : reply.path("errors")) {
            if (!error.asText("").isBlank()) {
                errors++;
            }
        }
        double coverage = points == 0 ? 0 : earned / points;
        double score = Math.max(0, Math.min(1, coverage - ERROR_PENALTY * Math.min(errors, 2)));
        if (earned > 0 && errors == 0) {
            score = Math.max(score, RIGHT_TRACK_SCORE);
        }
        score = Math.round(score * 100) / 100.0;
        GradeResult.Verdict verdict = GradeResult.verdictFor(score);
        String feedback = withoutSpellingNotes(TextCleanup.oneLine(reply.path("feedback").asText("")));
        if (feedback.isEmpty()) {
            feedback = switch (verdict) {
                case CORRECT -> "That covers the key points.";
                case PARTIAL -> "You're on the right track, but some key points are missing.";
                case INCORRECT -> "That doesn't match the material. Compare it with the answer below.";
            };
        }
        return new GradeResult(score, verdict, feedback, missing);
    }

    /** Drops feedback sentences about spelling or punctuation, which grading deliberately ignores. */
    static String withoutSpellingNotes(String feedback) {
        StringBuilder kept = new StringBuilder();
        for (String sentence : feedback.split("(?<=[.!?])\\s+")) {
            if (!SPELLING_TALK.matcher(sentence).find()) {
                kept.append(kept.isEmpty() ? "" : " ").append(sentence);
            }
        }
        return kept.toString().strip();
    }

    private static void addPoint(List<String> missing, String text) {
        if (!text.isEmpty()) {
            missing.add(TextCleanup.capitalize(text.replaceAll("[.]+$", "")));
        }
    }
}
