package edu.learnsphere.core.service.impl.pipeline;

import static org.assertj.core.api.Assertions.assertThat;

import edu.learnsphere.core.spi.QuestionReading.Kind;
import org.junit.jupiter.api.Test;

class GreetingsTest {

    @Test
    void greetingsThanksAndQuestionsAboutTheAssistant() {
        for (String message : new String[] {"hello", "Hi!", "hey there", "Good morning", "thanks!", "Thank you so much",
                "ok", "What can you do?", "who are you"}) {
            assertThat(Greetings.kindOf(message, false)).as(message).contains(Kind.GREETING);
            assertThat(Greetings.kindOf(message, true)).as(message + ", later").contains(Kind.GREETING);
        }
        assertThat(Greetings.isThanks("Thanks!")).isTrue();
        assertThat(Greetings.isThanks("ok")).isTrue();
        assertThat(Greetings.isThanks("hello")).isFalse();
    }

    @Test
    void requestsThatNameNoSubjectAreVagueOnlyInANewConversation() {
        for (String message : new String[] {"tell me about this topic", "Explain this.", "What's this chapter about?",
                "summarise it please", "teach me something"}) {
            assertThat(Greetings.kindOf(message, false)).as(message).contains(Kind.VAGUE);
            assertThat(Greetings.kindOf(message, true)).as(message + ", later").isEmpty();
        }
    }

    @Test
    void requestsToBeQuizzedAtAnyPointOfAConversation() {
        for (String message : new String[] {"quiz me on what we're doing", "Quiz me", "can you test me on this?",
                "give me some practice", "what questions may come from this pdf in exams that I don't know about",
                "flashcards please", "ask me questions about conjunction"}) {
            assertThat(Greetings.kindOf(message, false)).as(message).contains(Kind.PRACTICE);
            assertThat(Greetings.kindOf(message, true)).as(message + ", later").contains(Kind.PRACTICE);
        }
        for (String message : new String[] {"What is good practice in formal methods?", "How do I test a tautology?",
                "Define the test for equality"}) {
            assertThat(Greetings.kindOf(message, true)).as(message).isEmpty();
        }
    }

    @Test
    void questionsThatNameASubjectAreLeftAlone() {
        for (String message : new String[] {"explain conjunction", "What is a tautology?", "hello, what is paging?",
                "Tell me about the Z notation", "thank god for truth tables: how do they work", "it"}) {
            assertThat(Greetings.kindOf(message, false)).as(message).isNotEqualTo(java.util.Optional.of(Kind.GREETING));
        }
        assertThat(Greetings.kindOf("explain conjunction", false)).isEmpty();
        assertThat(Greetings.kindOf("Tell me about the Z notation", false)).isEmpty();
        assertThat(Greetings.kindOf("hello, what is paging?", false)).isEmpty();
        assertThat(Greetings.kindOf("", false)).isEmpty();
    }
}
