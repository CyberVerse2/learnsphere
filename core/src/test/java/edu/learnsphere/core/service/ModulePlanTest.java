package edu.learnsphere.core.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

import java.util.List;
import org.junit.jupiter.api.Test;

class ModulePlanTest {

    private static final List<String> SECTIONS = List.of("Intro", "Syntax", "Semantics", "Quantifiers", "Equality");

    private final ModulePlan plan = new ModulePlan(SECTIONS, List.of(new ModulePlan.Unit("Introduction", "", 0),
            new ModulePlan.Unit("Propositions", "", 1), new ModulePlan.Unit("Predicates", "", 3)));

    @Test
    void eachUnitRunsUntilTheNextStarts() {
        assertThat(plan.sectionsOf(0)).containsExactly("Intro");
        assertThat(plan.sectionsOf(1)).containsExactly("Syntax", "Semantics");
        assertThat(plan.sectionsOf(2)).containsExactly("Quantifiers", "Equality");
        assertThat(List.of(0, 1, 2, 3, 4).stream().map(plan::unitOf)).containsExactly(0, 1, 1, 2, 2);
    }

    @Test
    void removingAUnitJoinsItsSectionsToTheOneBefore() {
        assertThat(plan.without(1).units()).extracting(ModulePlan.Unit::title, ModulePlan.Unit::firstSection)
                .containsExactly(tuple("Introduction", 0), tuple("Predicates", 3));
        assertThat(plan.without(0).units()).extracting(ModulePlan.Unit::title, ModulePlan.Unit::firstSection)
                .containsExactly(tuple("Propositions", 0), tuple("Predicates", 3));
        ModulePlan single = new ModulePlan(SECTIONS, List.of(new ModulePlan.Unit("All", "", 0)));
        assertThatThrownBy(() -> single.without(0)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void renamingKeepsTheRest() {
        assertThat(plan.renamed(2, "Quantifiers").units().get(2))
                .isEqualTo(new ModulePlan.Unit("Quantifiers", "", 3));
        assertThat(plan.renamed(2, "Quantifiers").sections()).isEqualTo(SECTIONS);
    }

    @Test
    void unitsMustStartAtTheFirstSectionAndMoveForward() {
        assertThatThrownBy(() -> new ModulePlan(SECTIONS, List.of(new ModulePlan.Unit("A", "", 1))))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ModulePlan(SECTIONS, List.of(new ModulePlan.Unit("A", "", 0),
                new ModulePlan.Unit("B", "", 0)))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ModulePlan(SECTIONS, List.of(new ModulePlan.Unit("A", "", 0),
                new ModulePlan.Unit("B", "", 5)))).isInstanceOf(IllegalArgumentException.class);
    }
}
