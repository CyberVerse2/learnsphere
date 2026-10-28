package edu.learnsphere.core.service.impl.pipeline;

import edu.learnsphere.core.spi.QuestionReading;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * Recognises, by their words, the messages that are plainly not questions about the course: greetings, thanks
 * and questions about the assistant, requests to be quizzed ("quiz me", "what questions could come up in the
 * exam?"), and (in a new conversation) requests that name no subject such as "tell me about this topic". The
 * study assistant's model tells these apart unreliably, and words settle them at once.
 */
final class Greetings {

    private static final int MAX_GREETING_WORDS = 6;
    private static final int MAX_VAGUE_WORDS = 10;
    private static final int MAX_PRACTICE_WORDS = 16;

    private static final Set<String> HELLO = Set.of("hi", "hello", "hey", "hiya", "howdy", "yo", "greetings", "morning",
            "afternoon", "evening");
    private static final Set<String> THANKS = Set.of("thanks", "thank", "thx", "cheers", "ty");
    private static final Set<String> ACKNOWLEDGEMENTS = Set.of("ok", "okay", "cool", "great", "nice", "awesome",
            "perfect", "alright", "got it", "ok thanks", "okay thanks", "great thanks");
    private static final List<String> ABOUT_THE_ASSISTANT = List.of("what can you do", "who are you", "what are you",
            "how do you work", "how does this work", "what do you do", "help", "can you help me", "are you there");
    /** Words that ask to practise, unless the message opens by asking what something is. */
    private static final Set<String> PRACTICE = Set.of("quiz", "quizzes", "practise", "practice", "flashcard",
            "flashcards", "drill");
    /** Phrases that ask to practise wherever they appear. */
    private static final List<String> PRACTICE_PHRASES = List.of("test me", "test my", "ask me questions",
            "ask me some questions", "give me questions", "give me some questions", "questions may come",
            "questions might come", "questions could come", "questions will come", "questions can come",
            "likely questions", "possible questions");
    /** Words of an exam, which with "questions" ask what to practise. */
    private static final Set<String> EXAM = Set.of("exam", "exams", "examination", "examinations", "test", "tests");
    /** Openings of a question about what something is ("what is practice-based learning?"). */
    private static final Set<String> ASKS_WHAT = Set.of("what", "whats", "why", "how", "when", "where", "who", "which",
            "define", "explain", "describe");
    /** Words that ask about something without naming it. */
    private static final Set<String> GENERIC = Set.of("tell", "me", "about", "more", "explain", "what", "whats", "is",
            "are", "the", "a", "an", "this", "that", "it", "these", "those", "here", "topic", "topics", "chapter",
            "section", "subject", "lesson", "part", "page", "please", "can", "could", "would", "you", "describe",
            "summarise", "summarize", "summary", "give", "overview", "of", "on", "i", "want", "to", "know", "learn",
            "understand", "everything", "all", "discuss", "talk", "say", "does", "do", "mean", "means", "so", "now",
            "go", "through", "teach", "something", "anything", "stuff", "thing", "things");

    private Greetings() {
    }

    /**
     * {@link QuestionReading.Kind#GREETING}, {@link QuestionReading.Kind#PRACTICE} or
     * {@link QuestionReading.Kind#VAGUE} when the words settle it, else empty. A message only counts as vague in a
     * new conversation; later, the conversation says what it means.
     */
    static Optional<QuestionReading.Kind> kindOf(String message, boolean conversationStarted) {
        String text = normalise(message);
        if (text.isEmpty()) {
            return Optional.empty();
        }
        String[] words = text.split(" ");
        if (isGreeting(text, words)) {
            return Optional.of(QuestionReading.Kind.GREETING);
        }
        if (isPractice(text, words)) {
            return Optional.of(QuestionReading.Kind.PRACTICE);
        }
        if (!conversationStarted && words.length <= MAX_VAGUE_WORDS && List.of(words).stream().allMatch(GENERIC::contains)) {
            return Optional.of(QuestionReading.Kind.VAGUE);
        }
        return Optional.empty();
    }

    /** True for thanks and acknowledgements such as "thanks!" or "ok". */
    static boolean isThanks(String message) {
        String text = normalise(message);
        return !text.isEmpty() && (THANKS.contains(text.split(" ")[0]) || ACKNOWLEDGEMENTS.contains(text));
    }

    private static boolean isPractice(String text, String[] words) {
        if (words.length > MAX_PRACTICE_WORDS) {
            return false;
        }
        String padded = " " + text + " ";
        if (PRACTICE_PHRASES.stream().anyMatch(phrase -> padded.contains(" " + phrase + " "))) {
            return true;
        }
        List<String> all = List.of(words);
        if (all.contains("questions") && all.stream().anyMatch(EXAM::contains)) {
            return true;
        }
        return !ASKS_WHAT.contains(words[0]) && all.stream().anyMatch(PRACTICE::contains);
    }

    private static boolean isGreeting(String text, String[] words) {
        if (words.length > MAX_GREETING_WORDS) {
            return false;
        }
        if (HELLO.contains(words[0]) || (words[0].equals("good") && words.length > 1 && HELLO.contains(words[1]))) {
            return List.of(words).stream().noneMatch(w -> !GENERIC.contains(w) && !HELLO.contains(w) && !w.equals("good")
                    && !w.equals("there") && !w.equals("everyone") && !w.equals("assistant"));
        }
        return isThanks(text) || ABOUT_THE_ASSISTANT.contains(text);
    }

    /** Lowercase words separated by single spaces, punctuation removed ("What's" becomes "whats"). */
    private static String normalise(String message) {
        if (message == null) {
            return "";
        }
        return message.toLowerCase(Locale.ROOT).replace("'", "").replace("’", "")
                .replaceAll("[^\\p{L}\\p{N} ]", " ").strip().replaceAll("\\s+", " ");
    }
}
