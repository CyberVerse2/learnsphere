package edu.learnsphere.app.ui.study;

import edu.learnsphere.core.service.SessionItem;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * The order a session's cards are shown in. Every item starts as one card; an answer rated Again puts the item
 * back {@value #COMEBACK_GAP} cards later (or at the end), up to {@value #MAX_TRIES} tries in all, which is as
 * many answers as the study service takes for one item in one session. The total grows with each comeback, so
 * the counter reads "4 of 18" after one. Not thread-safe: used on the FX thread.
 */
final class StudyQueue {

    /** Answers one item may get in one session: the first plus two comebacks. */
    static final int MAX_TRIES = 3;
    /** Cards shown between a wrong answer and its comeback, when there are that many left. */
    static final int COMEBACK_GAP = 3;

    /** One showing of an item; {@code attempt} is 1 the first time. */
    private record Card(SessionItem item, int attempt) {
    }

    private final List<Card> cards = new ArrayList<>();
    private int index;

    StudyQueue(List<SessionItem> items) {
        Objects.requireNonNull(items, "items").forEach(item -> cards.add(new Card(item, 1)));
    }

    /** True when the session had nothing in it. */
    boolean isEmpty() {
        return cards.isEmpty();
    }

    /** True while there is a card to show. */
    boolean hasCurrent() {
        return index < cards.size();
    }

    /** The item on the current card. */
    SessionItem current() {
        return cards.get(index).item();
    }

    /** 1 the first time the current item is shown, 2 or 3 when it comes back. */
    int attempt() {
        return cards.get(index).attempt();
    }

    /** True when the current card is a comeback after a wrong answer. */
    boolean isComeback() {
        return hasCurrent() && attempt() > 1;
    }

    /** The current card's place, from 1. */
    int position() {
        return Math.min(index + 1, cards.size());
    }

    /** Cards in the session, comebacks included. */
    int total() {
        return cards.size();
    }

    /** Cards finished before the current one. */
    int finished() {
        return Math.min(index, cards.size());
    }

    /** True when the current card is the last one. */
    boolean isLast() {
        return index == cards.size() - 1;
    }

    /**
     * Puts the current item back to come again later in the session.
     *
     * @return false when it has had all its tries
     */
    boolean requeueCurrent() {
        Card card = cards.get(index);
        if (card.attempt() >= MAX_TRIES) {
            return false;
        }
        int at = Math.min(cards.size(), index + 1 + COMEBACK_GAP);
        cards.add(at, new Card(card.item(), card.attempt() + 1));
        return true;
    }

    /**
     * Takes back comebacks of {@code itemId} that are still ahead (its answer was counted as right after all).
     *
     * @return how many were taken back
     */
    int dropComebacks(String itemId) {
        int dropped = 0;
        for (int i = cards.size() - 1; i > index; i--) {
            if (cards.get(i).item().itemId().equals(itemId)) {
                cards.remove(i);
                dropped++;
            }
        }
        return dropped;
    }

    /** True when {@code itemId} comes back later in the session. */
    boolean comesBack(String itemId) {
        for (int i = index + 1; i < cards.size(); i++) {
            if (cards.get(i).item().itemId().equals(itemId)) {
                return true;
            }
        }
        return false;
    }

    /** Moves to the next card; false when the session is done. */
    boolean advance() {
        if (index < cards.size()) {
            index++;
        }
        return hasCurrent();
    }
}
