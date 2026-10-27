package edu.learnsphere.core.db;

import static org.assertj.core.api.Assertions.assertThat;

import edu.learnsphere.core.model.ChatMessage;
import edu.learnsphere.core.model.ChatThread;
import edu.learnsphere.core.model.Citation;
import edu.learnsphere.core.model.Course;
import edu.learnsphere.core.model.MessageStatus;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;

class ChatRepositoryTest extends RepositoryTestBase {

    @Test
    void storesThreadsAndMessagesWithCitations() {
        Course course = t.course("C");
        ChatThread thread = ChatThread.create(course.id(), "Cells", t.now());
        repos.chats().insertThread(thread);
        ChatMessage question = ChatMessage.question(thread.id(), "What makes ATP?", t.now());
        repos.chats().insertMessage(question);
        t.clock.advance(Duration.ofSeconds(3));
        Citation citation = new Citation(1, "chunk-1", "doc-1", "Biology notes", 4, 5, "Mitochondria make ATP.");
        ChatMessage answer = ChatMessage.answer(thread.id(), "Mitochondria do [1].", MessageStatus.COMPLETE,
                List.of(citation), t.now());
        repos.chats().insertMessage(answer);

        assertThat(repos.chats().findThread(thread.id()).orElseThrow().updatedAt()).isEqualTo(t.now());
        assertThat(repos.chats().listMessages(thread.id())).containsExactly(question, answer);
        assertThat(repos.chats().findMessage(answer.id()).orElseThrow().citations()).containsExactly(citation);

        ChatRepository.ThreadRow row = repos.chats().listThreadRows(course.id()).getFirst();
        assertThat(row.messageCount()).isEqualTo(2);
        assertThat(row.lastMessage()).isEqualTo("Mitochondria do [1].");
        assertThat(row.lastMessageAt()).isEqualTo(t.now());
    }

    @Test
    void pairsRecentQuestionsWithTheirAnswers() {
        Course course = t.course("C");
        ChatThread thread = ChatThread.create(course.id(), "T", t.now());
        repos.chats().insertThread(thread);
        ChatMessage q1 = ChatMessage.question(thread.id(), "First?", t.now());
        repos.chats().insertMessage(q1);
        repos.chats().insertMessage(ChatMessage.answer(thread.id(), "One.", MessageStatus.COMPLETE, List.of(), t.now()));
        t.clock.advance(Duration.ofMinutes(1));
        ChatMessage q2 = ChatMessage.question(thread.id(), "Second?", t.now());
        repos.chats().insertMessage(q2);
        repos.chats().insertMessage(ChatMessage.answer(thread.id(), "Not covered.", MessageStatus.INSUFFICIENT, List.of(), t.now()));
        t.clock.advance(Duration.ofMinutes(1));
        ChatMessage q3 = ChatMessage.question(thread.id(), "Third?", t.now());
        repos.chats().insertMessage(q3);
        repos.chats().insertMessage(ChatMessage.answer(thread.id(), "Three.", MessageStatus.COMPLETE, List.of(), t.now()));

        List<ChatRepository.Exchange> exchanges = repos.chats().recentExchanges(null, 10);
        assertThat(exchanges).extracting(e -> e.question().content()).containsExactly("Third?", "First?");
        assertThat(exchanges).extracting(e -> e.answer().content()).containsExactly("Three.", "One.");
        assertThat(repos.chats().recentExchanges(course.id(), 1)).hasSize(1);
        assertThat(repos.chats().recentExchanges("other", 10)).isEmpty();
    }

    @Test
    void renamesAndDeletesThreads() {
        Course course = t.course("C");
        ChatThread thread = ChatThread.create(course.id(), "Old", t.now());
        repos.chats().insertThread(thread);
        repos.chats().insertMessage(ChatMessage.question(thread.id(), "Q", t.now()));

        assertThat(repos.chats().renameThread(thread.id(), "New")).isTrue();
        assertThat(repos.chats().listThreads(course.id())).extracting(ChatThread::title).containsExactly("New");
        assertThat(repos.chats().touchThread(thread.id(), t.now() + 99)).isTrue();
        assertThat(repos.chats().findThread(thread.id()).orElseThrow().updatedAt()).isEqualTo(t.now() + 99);
        assertThat(repos.chats().deleteThread(thread.id())).isTrue();
        assertThat(repos.chats().listMessages(thread.id())).isEmpty();
    }
}
