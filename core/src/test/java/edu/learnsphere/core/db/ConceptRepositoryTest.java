package edu.learnsphere.core.db;

import static org.assertj.core.api.Assertions.assertThat;

import edu.learnsphere.core.model.Concept;
import edu.learnsphere.core.model.Course;
import edu.learnsphere.core.model.CourseModule;
import edu.learnsphere.core.model.ItemStatus;
import edu.learnsphere.core.model.ItemType;
import edu.learnsphere.core.model.LearningItem;
import java.util.List;
import org.junit.jupiter.api.Test;

class ConceptRepositoryTest extends RepositoryTestBase {

    @Test
    void roundTripsAndListsConcepts() {
        Course course = t.course("C");
        CourseModule m1 = t.module(course, "M1");
        CourseModule m2 = t.module(course, "M2");
        Concept mitosis = t.concept(course, m2, "mitosis");
        Concept atp = t.concept(course, m1, "ATP");
        Concept courseWide = t.concept(course, null, "Cells");

        assertThat(repos.concepts().find(atp.id())).contains(atp);
        assertThat(repos.concepts().listByCourse(course.id())).containsExactly(atp, mitosis, courseWide);
        assertThat(repos.concepts().listByModule(m2.id())).containsExactly(mitosis);
        assertThat(repos.concepts().listByIds(List.of(mitosis.id(), atp.id()))).containsExactly(atp, mitosis);
    }

    @Test
    void findsByNameIgnoringCase() {
        Course course = t.course("C");
        CourseModule module = t.module(course, "M");
        Concept concept = t.concept(course, module, "Photosynthesis");
        Concept loose = t.concept(course, null, "Energy");

        assertThat(repos.concepts().findByName(course.id(), module.id(), "  photosynthesis ")).contains(concept);
        assertThat(repos.concepts().findByName(course.id(), null, "ENERGY")).contains(loose);
        assertThat(repos.concepts().findByName(course.id(), null, "photosynthesis")).isEmpty();
    }

    @Test
    void updatesDeletesAndCountsItems() {
        Course course = t.course("C");
        CourseModule module = t.module(course, "M");
        Concept concept = t.concept(course, module, "Old");
        LearningItem approved = t.item(course, module, ItemType.FLASHCARD, ItemStatus.APPROVED, "a");
        LearningItem draft = t.item(course, module, ItemType.FLASHCARD, ItemStatus.DRAFT, "d");
        repos.items().setConcepts(approved.id(), List.of(concept.id()));
        repos.items().setConcepts(draft.id(), List.of(concept.id()));

        repos.concepts().update(concept.id(), "New", "Desc");
        assertThat(repos.concepts().find(concept.id()).orElseThrow()).satisfies(c -> {
            assertThat(c.name()).isEqualTo("New");
            assertThat(c.description()).isEqualTo("Desc");
        });
        assertThat(repos.concepts().approvedItemCounts(course.id())).containsExactlyEntriesOf(java.util.Map.of(concept.id(), 1));

        assertThat(repos.concepts().deleteByModule(module.id())).isEqualTo(1);
        assertThat(repos.items().conceptIds(approved.id())).isEmpty();
    }
}
