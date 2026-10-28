package edu.learnsphere.core.service;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Modules suggested for a course that has none ({@link CourseService#suggestModules}): the course's sections in
 * reading order, and units that each start at one section and run until the next unit starts. The creator renames
 * or removes units, then {@link CourseService#applyModules} creates them.
 *
 * @param sections the headings of the course's PDFs, in reading order
 * @param units    the modules, in order; the first starts at section 0 and each starts after the one before
 */
public record ModulePlan(List<String> sections, List<Unit> units) {

    /** One module: its title, a sentence about it, and the index of its first section. */
    public record Unit(String title, String summary, int firstSection) {
        public Unit {
            Objects.requireNonNull(title, "title");
            summary = summary == null ? "" : summary;
        }
    }

    public ModulePlan {
        sections = List.copyOf(sections);
        units = List.copyOf(units);
        if (units.isEmpty() || units.getFirst().firstSection() != 0) {
            throw new IllegalArgumentException("The first unit must start at the first section");
        }
        for (int i = 1; i < units.size(); i++) {
            int first = units.get(i).firstSection();
            if (first <= units.get(i - 1).firstSection() || first >= sections.size()) {
                throw new IllegalArgumentException("Units must start at increasing sections: " + first);
            }
        }
    }

    /** The headings unit {@code index} covers. */
    public List<String> sectionsOf(int index) {
        int end = index + 1 < units.size() ? units.get(index + 1).firstSection() : sections.size();
        return sections.subList(units.get(index).firstSection(), end);
    }

    /** The index of the unit that covers section {@code section}. */
    public int unitOf(int section) {
        int unit = 0;
        while (unit + 1 < units.size() && units.get(unit + 1).firstSection() <= section) {
            unit++;
        }
        return unit;
    }

    /** A copy with unit {@code index} titled {@code title}. */
    public ModulePlan renamed(int index, String title) {
        List<Unit> changed = new ArrayList<>(units);
        Unit unit = units.get(index);
        changed.set(index, new Unit(title, unit.summary(), unit.firstSection()));
        return new ModulePlan(sections, changed);
    }

    /**
     * A copy without unit {@code index}: its sections join the unit before it, or the next unit when it is the
     * first. The only unit can't be removed.
     */
    public ModulePlan without(int index) {
        if (units.size() == 1) {
            throw new IllegalStateException("A plan keeps at least one unit");
        }
        List<Unit> changed = new ArrayList<>(units);
        changed.remove(index);
        if (index == 0) {
            Unit next = changed.getFirst();
            changed.set(0, new Unit(next.title(), next.summary(), 0));
        }
        return new ModulePlan(sections, changed);
    }
}
