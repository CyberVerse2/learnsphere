package edu.learnsphere.core.db;

import static org.assertj.core.api.Assertions.assertThat;

import edu.learnsphere.core.model.Course;
import edu.learnsphere.core.model.Flag;
import edu.learnsphere.core.model.FlagReason;
import edu.learnsphere.core.model.FlagSource;
import edu.learnsphere.core.model.ItemStatus;
import edu.learnsphere.core.model.ItemType;
import edu.learnsphere.core.model.LearningItem;
import org.junit.jupiter.api.Test;

class FlagRepositoryTest extends RepositoryTestBase {

    @Test
    void opensResolvesAndReopensFlags() {
        Course course = t.course("C");
        LearningItem item = t.item(course, null, ItemType.MCQ, ItemStatus.APPROVED, "q");
        Flag flag = Flag.open(item.id(), FlagReason.WRONG_ANSWER, "Option B is right", FlagSource.LEARNER_FEEDBACK, t.now());
        Flag other = Flag.open(item.id(), FlagReason.TYPO, "", FlagSource.SELF, t.now() + 1);
        repos.flags().insert(flag);
        repos.flags().insert(other);

        assertThat(repos.flags().find(flag.id())).contains(flag);
        assertThat(repos.flags().listByItem(item.id())).containsExactlyInAnyOrder(flag, other);
        assertThat(repos.flags().countOpenByItem(course.id())).containsEntry(item.id(), 2);

        assertThat(repos.flags().resolve(flag.id(), t.now() + 5)).isTrue();
        assertThat(repos.flags().find(flag.id()).orElseThrow().resolvedAt()).isEqualTo(t.now() + 5);
        assertThat(repos.flags().listByCourse(course.id(), false)).extracting(Flag::id).containsExactly(other.id());
        assertThat(repos.flags().listByCourse(course.id(), true)).hasSize(2);
        assertThat(repos.flags().countOpenByItem(course.id())).containsEntry(item.id(), 1);

        assertThat(repos.flags().reopen(flag.id())).isTrue();
        assertThat(repos.flags().find(flag.id()).orElseThrow().resolvedAt()).isNull();
        assertThat(repos.flags().delete(other.id())).isTrue();
        assertThat(repos.flags().listByItem(item.id())).extracting(Flag::id).containsExactly(flag.id());
    }
}
