package edu.learnsphere.core.learning;

import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import java.util.ListIterator;
import java.util.function.BiPredicate;

/**
 * Presentation order for a session. Reviews are interleaved: an item that tests the same concept as the one
 * just shown is swapped with the next item (within a short look-ahead) that does not, so practice alternates
 * between topics while keeping roughly the priority order. New items keep their module order (new material is
 * met in the order the course teaches it) and are spread evenly between the reviews. Deterministic.
 */
final class Interleaving {

    /** How far ahead {@link #spread} looks for an item on another topic. */
    static final int LOOK_AHEAD = 4;

    private Interleaving() {
    }

    /** The session order: {@code reviews} spread across topics, with {@code newItems} merged in evenly. */
    static List<PlannedItem> order(List<PlannedItem> reviews, List<PlannedItem> newItems) {
        return merge(spread(reviews, PlannedItem::clashesWith, LOOK_AHEAD), newItems);
    }

    /**
     * Reorders {@code ordered} so that consecutive items clash as little as possible: whenever the next item
     * clashes with the previous one, the first of the following {@code lookAhead} items that does not clash
     * is taken instead.
     */
    static <T> List<T> spread(List<T> ordered, BiPredicate<T, T> clash, int lookAhead) {
        LinkedList<T> remaining = new LinkedList<>(ordered);
        List<T> result = new ArrayList<>(ordered.size());
        T previous = null;
        while (!remaining.isEmpty()) {
            int pickIndex = 0;
            if (previous != null && clash.test(previous, remaining.getFirst())) {
                ListIterator<T> it = remaining.listIterator(1);
                for (int looked = 0; it.hasNext() && looked < lookAhead; looked++) {
                    int index = it.nextIndex();
                    if (!clash.test(previous, it.next())) {
                        pickIndex = index;
                        break;
                    }
                }
            }
            T pick = remaining.remove(pickIndex);
            result.add(pick);
            previous = pick;
        }
        return result;
    }

    /**
     * Merges two lists keeping each one's order, spreading the shorter evenly through the longer. A tie goes to
     * {@code first}, so a session opens with a review.
     */
    static <T> List<T> merge(List<T> first, List<T> second) {
        int a = first.size();
        int b = second.size();
        List<T> result = new ArrayList<>(a + b);
        int i = 0;
        int j = 0;
        while (i < a || j < b) {
            boolean takeFirst;
            if (i >= a) {
                takeFirst = false;
            } else if (j >= b) {
                takeFirst = true;
            } else {
                // Compare progress i/a with j/b without division.
                takeFirst = (long) i * b <= (long) j * a;
            }
            result.add(takeFirst ? first.get(i++) : second.get(j++));
        }
        return result;
    }
}
