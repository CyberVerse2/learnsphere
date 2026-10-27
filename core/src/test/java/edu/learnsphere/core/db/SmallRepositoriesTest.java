package edu.learnsphere.core.db;

import static org.assertj.core.api.Assertions.assertThat;

import edu.learnsphere.core.model.AuditLogEntry;
import edu.learnsphere.core.model.ClassInsight;
import edu.learnsphere.core.model.Course;
import edu.learnsphere.core.model.EntityType;
import edu.learnsphere.core.model.Setting;
import edu.learnsphere.core.util.Ids;
import java.time.Duration;
import org.junit.jupiter.api.Test;

/** Round trips for class insights, the audit log and settings. */
class SmallRepositoriesTest extends RepositoryTestBase {

    @Test
    void classInsightsReplaceByLearnerLabel() {
        Course course = t.course("C");
        ClassInsight first = new ClassInsight(Ids.newId(), course.id(), "Amara", "{\"accuracy\":0.6}", t.now(), t.now());
        repos.classInsights().insert(first);
        assertThat(repos.classInsights().find(first.id())).contains(first);

        ClassInsight newer = new ClassInsight(Ids.newId(), course.id(), "Amara", "{\"accuracy\":0.8}", t.now() + 1, t.now() + 1);
        repos.classInsights().replace(newer);
        ClassInsight other = new ClassInsight(Ids.newId(), course.id(), "Ben", "{}", t.now(), t.now());
        repos.classInsights().insert(other);

        assertThat(repos.classInsights().findByLabel(course.id(), "Amara")).contains(newer);
        assertThat(repos.classInsights().listByCourse(course.id())).containsExactlyInAnyOrder(newer, other);
        assertThat(repos.classInsights().delete(other.id())).isTrue();
        assertThat(repos.classInsights().deleteByCourse(course.id())).isEqualTo(1);
    }

    @Test
    void auditLogAppendsAndLists() {
        AuditLogEntry first = repos.audit().append("course.created", EntityType.COURSE, "c1", "Cell biology");
        t.clock.advance(Duration.ofSeconds(1));
        AuditLogEntry second = repos.audit().append("course.published", EntityType.COURSE, "c1", "v1");
        repos.audit().append("item.approved", EntityType.ITEM, "i1", null);

        assertThat(repos.audit().listForEntity(EntityType.COURSE, "c1")).containsExactly(second, first);
        assertThat(repos.audit().listRecent(2)).hasSize(2);
        assertThat(first.createdAt()).isEqualTo(t.now() - 1000);
    }

    @Test
    void settingsUpsert() {
        assertThat(repos.settings().get("theme")).isEmpty();
        repos.settings().put("theme", "dark");
        long created = t.now();
        t.clock.advance(Duration.ofMinutes(1));
        repos.settings().put("theme", "light");
        repos.settings().put("practice.session_size", "25");

        Setting theme = repos.settings().find("theme").orElseThrow();
        assertThat(theme.value()).isEqualTo("light");
        assertThat(theme.createdAt()).isEqualTo(created);
        assertThat(theme.updatedAt()).isEqualTo(t.now());
        assertThat(repos.settings().all()).containsOnlyKeys("theme", "practice.session_size");
        assertThat(repos.settings().delete("theme")).isTrue();
        assertThat(repos.settings().get("theme")).isEmpty();
    }
}
