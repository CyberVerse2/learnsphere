package edu.learnsphere.app.ui.study;

import static org.assertj.core.api.Assertions.assertThat;

import edu.learnsphere.core.model.Difficulty;
import edu.learnsphere.core.model.ItemType;
import edu.learnsphere.core.service.SessionItem;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

class StudyQueueTest {

    private static SessionItem item(String id) {
        return new SessionItem(id, ItemType.MCQ, "Question " + id, List.of("a", "b"), null, List.of(), null,
                Difficulty.MEDIUM, false, false);
    }

    private static StudyQueue queue(int size) {
        return new StudyQueue(IntStream.rangeClosed(1, size).mapToObj(i -> item("i" + i)).toList());
    }

    private static List<String> order(StudyQueue queue) {
        List<String> ids = new ArrayList<>();
        while (queue.hasCurrent()) {
            ids.add(queue.current().itemId() + (queue.isComeback() ? "*" : ""));
            queue.advance();
        }
        return ids;
    }

    @Test
    void showsEveryItemOnceInOrder() {
        StudyQueue queue = queue(3);

        assertThat(queue.isEmpty()).isFalse();
        assertThat(queue.position()).isEqualTo(1);
        assertThat(queue.total()).isEqualTo(3);
        assertThat(order(queue)).containsExactly("i1", "i2", "i3");
        assertThat(queue.hasCurrent()).isFalse();
        assertThat(queue.finished()).isEqualTo(3);
    }

    @Test
    void aWrongAnswerComesBackThreeCardsLater() {
        StudyQueue queue = queue(6);

        assertThat(queue.requeueCurrent()).isTrue();

        assertThat(queue.total()).isEqualTo(7);
        assertThat(queue.comesBack("i1")).isTrue();
        assertThat(order(queue)).containsExactly("i1", "i2", "i3", "i4", "i1*", "i5", "i6");
    }

    @Test
    void nearTheEndTheComebackGoesLast() {
        StudyQueue queue = queue(2);
        queue.advance();

        queue.requeueCurrent();

        assertThat(queue.isLast()).isFalse();
        assertThat(order(queue)).containsExactly("i2", "i2*");
    }

    @Test
    void anItemComesBackAtMostTwice() {
        StudyQueue queue = queue(1);

        assertThat(queue.requeueCurrent()).isTrue();
        queue.advance();
        assertThat(queue.attempt()).isEqualTo(2);
        assertThat(queue.requeueCurrent()).isTrue();
        queue.advance();
        assertThat(queue.attempt()).isEqualTo(StudyQueue.MAX_TRIES);

        assertThat(queue.requeueCurrent()).isFalse();
        assertThat(queue.isLast()).isTrue();
    }

    @Test
    void aDisputedAnswerTakesItsComebackBack() {
        StudyQueue queue = queue(5);
        queue.requeueCurrent();

        assertThat(queue.dropComebacks("i1")).isEqualTo(1);

        assertThat(queue.comesBack("i1")).isFalse();
        assertThat(queue.total()).isEqualTo(5);
        assertThat(queue.current().itemId()).isEqualTo("i1");
    }

    @Test
    void anEmptySessionHasNoCards() {
        StudyQueue queue = new StudyQueue(List.of());

        assertThat(queue.isEmpty()).isTrue();
        assertThat(queue.hasCurrent()).isFalse();
        assertThat(queue.advance()).isFalse();
    }
}
