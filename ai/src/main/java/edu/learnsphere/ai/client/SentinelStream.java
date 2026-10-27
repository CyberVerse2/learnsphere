package edu.learnsphere.ai.client;

import edu.learnsphere.core.spi.TokenSink;
import java.util.Locale;
import java.util.Objects;

/**
 * Passes streamed text on while keeping a sentinel word (such as {@code NOT_IN_MATERIAL}) out of it.
 *
 * <ul>
 *   <li>A reply that <em>starts</em> with the sentinel (in any case, with spaces or underscores, inside
 *       markdown emphasis) is a refusal: nothing is passed on and {@link #refused()} is true. Text is held
 *       back at the start only until it can no longer turn into the sentinel.</li>
 *   <li>The exact sentinel appearing later is removed, and a tail that could be its beginning is held
 *       back until the next piece shows it is not.</li>
 * </ul>
 *
 * Not thread-safe: one generation feeds it from one thread.
 */
final class SentinelStream implements TokenSink {

    private final String sentinel;
    private final String sentinelLetters;
    private final TokenSink downstream;
    private final StringBuilder text = new StringBuilder();
    private int passed;
    private boolean startDecided;
    private boolean refused;
    private boolean sentinelRemoved;

    SentinelStream(String sentinel, TokenSink downstream) {
        this.sentinel = Objects.requireNonNull(sentinel, "sentinel");
        this.sentinelLetters = letters(sentinel);
        this.downstream = Objects.requireNonNull(downstream, "downstream");
    }

    @Override
    public void accept(String piece) {
        if (refused || piece.isEmpty()) {
            return;
        }
        text.append(piece);
        drain(false);
    }

    /** Passes on whatever was held back; call once after the last piece. */
    void finish() {
        if (!refused) {
            drain(true);
        }
    }

    /** True when the reply was the sentinel. */
    boolean refused() {
        return refused;
    }

    /** True when the sentinel appeared after some answer text and was removed. */
    boolean sentinelRemoved() {
        return sentinelRemoved;
    }

    /** Everything received, minus the sentinel. Empty when refused. */
    String text() {
        return refused ? "" : text.toString();
    }

    private void drain(boolean end) {
        if (!startDecided) {
            String lead = letters(text);
            if (lead.startsWith(sentinelLetters)) {
                refused = true;
                return;
            }
            if (!end && sentinelLetters.startsWith(lead)) {
                return; // could still become the sentinel
            }
            startDecided = true;
        }
        int at;
        while ((at = text.indexOf(sentinel, passed)) >= 0) {
            text.delete(at, at + sentinel.length());
            sentinelRemoved = true;
        }
        int safeEnd = end ? text.length() : text.length() - heldTail();
        if (safeEnd > passed) {
            downstream.accept(text.substring(passed, safeEnd));
            passed = safeEnd;
        }
    }

    /** Length of the longest end of the text that is a proper beginning of the sentinel. */
    private int heldTail() {
        int max = Math.min(sentinel.length() - 1, text.length() - passed);
        for (int length = max; length > 0; length--) {
            if (sentinel.startsWith(text.substring(text.length() - length))) {
                return length;
            }
        }
        return 0;
    }

    private static String letters(CharSequence text) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < text.length() && out.length() < 64; i++) {
            char c = text.charAt(i);
            if (Character.isLetter(c)) {
                out.append(Character.toUpperCase(c));
            } else if (Character.isDigit(c)) {
                out.append(c);
            }
        }
        return out.toString().toUpperCase(Locale.ROOT);
    }
}
