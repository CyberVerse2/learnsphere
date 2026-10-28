package edu.learnsphere.core.service;

import edu.learnsphere.core.error.InvalidInputException;
import edu.learnsphere.core.error.NotFoundException;
import edu.learnsphere.core.model.ChatMessage;
import edu.learnsphere.core.model.ChatThread;
import edu.learnsphere.core.util.Cancellable;
import java.util.List;

/**
 * Questions answered only from a course's material, with page citations. Methods taking an id throw
 * {@link NotFoundException} when it does not exist.
 */
public interface AskService {

    /** Conversations of a course, most recently active first. */
    List<ThreadSummary> threads(String courseId);

    /**
     * Starts a conversation.
     *
     * @param title the title, or null to use the first question
     */
    ChatThread createThread(String courseId, String title);

    /** @throws InvalidInputException when the title is blank */
    void renameThread(String threadId, String title);

    /** Deletes a conversation with its messages. */
    void deleteThread(String threadId);

    /** Messages of a conversation in order. */
    List<ChatMessage> messages(String threadId);

    /**
     * Answers {@code question} in a conversation, streaming to {@code listener}. Returns at once; the
     * work runs in the background. The course must have been created here or be enrolled.
     *
     * @return a handle that stops the answer
     * @throws InvalidInputException when the question is blank
     */
    Cancellable ask(String threadId, String question, AnswerListener listener);

    /**
     * Topics of a course to offer as questions, at most {@code limit}: its module titles, else its concepts,
     * else the section headings of its ready PDFs (numbering and worked examples left out).
     */
    List<String> topics(String courseId, int limit);

    /** The most recently answered questions across courses, newest first. */
    List<RecentQuestion> recentQuestions(int limit);
}
