package edu.learnsphere.ai.client;

import edu.learnsphere.core.spi.TokenSink;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Holds back the start of a streamed answer until it is clear the answer does not open by saying, in the
 * model's own words, that the material doesn't cover the question (a refusal without the sentinel).
 *
 * <ul>
 *   <li>Text is held until the opening sentence is complete: a sentence end followed by a space, a
 *       citation bracket, {@value #OPENING_LIMIT} characters, or the end of the reply.</li>
 *   <li>An ordinary opening is passed on, and everything after it streams straight through.</li>
 *   <li>An opening that says the material doesn't cover something keeps the whole reply held back until a
 *       citation such as {@code [2]} appears: then it was a remark before a cited answer, and the held text
 *       is passed on. A reply that ends without any citation is a refusal ({@link #refused()}), and the
 *       reader never saw it.</li>
 * </ul>
 *
 * Not thread-safe: one generation feeds it from one thread.
 */
final class RefusalOpeningFilter implements TokenSink {

    /** Characters after which the opening is judged even without a sentence end. */
    static final int OPENING_LIMIT = 300;

    /**
     * An opening sentence (before any citation) saying the material doesn't cover the question, such as
     * "Your notes don't explain the banker's algorithm."
     */
    private static final Pattern REFUSAL_OPENING = Pattern.compile(
            "(?is)^[^\\[.!?]{0,200}?\\b(your|the|this|provided|course) (provided |course )?(material|materials|passages?|notes|text)"
                    + " (does ?n[o']t|do ?n[o']t|does not|do not|never|only) "
                    + "(explain|cover|mention|contain|describe|say|include|discuss|address|talk)[^.\\[]*\\.");
    private static final Pattern CITATION = Pattern.compile("\\[\\d+]");
    private static final Pattern OPENING_END = Pattern.compile("[.!?]\\s|\\[");

    private final TokenSink downstream;
    private final StringBuilder held = new StringBuilder();
    private final StringBuilder text = new StringBuilder();
    private boolean passing;
    private boolean openingJudged;

    RefusalOpeningFilter(TokenSink downstream) {
        this.downstream = Objects.requireNonNull(downstream, "downstream");
    }

    /**
     * True when {@code answer} opens, before any citation, by saying the material doesn't cover the question,
     * and cites no passage anywhere.
     */
    static boolean refuses(String answer) {
        String stripped = answer.strip();
        return REFUSAL_OPENING.matcher(stripped).lookingAt() && !CITATION.matcher(stripped).find();
    }

    @Override
    public void accept(String piece) {
        if (piece.isEmpty()) {
            return;
        }
        text.append(piece);
        if (passing) {
            downstream.accept(piece);
            return;
        }
        held.append(piece);
        if (!openingJudged) {
            String opening = held.toString().stripLeading();
            if (!OPENING_END.matcher(opening).find() && opening.length() < OPENING_LIMIT) {
                return; // the opening sentence is not complete yet
            }
            openingJudged = true;
            if (!REFUSAL_OPENING.matcher(opening).lookingAt()) {
                release();
                return;
            }
        }
        // The opening says the material doesn't cover something: show it only once a citation follows.
        if (CITATION.matcher(held).find()) {
            release();
        }
    }

    /** Passes on what was held back, unless the reply turned out to be a refusal; call once after the last piece. */
    void finish() {
        if (passing) {
            return;
        }
        if (!refused()) {
            release();
        }
    }

    /** True when the reply opens by saying the material doesn't cover the question and cites nothing. */
    boolean refused() {
        return !passing && refuses(text.toString());
    }

    /** Everything received. */
    String text() {
        return text.toString();
    }

    private void release() {
        passing = true;
        if (!held.isEmpty()) {
            downstream.accept(held.toString());
            held.setLength(0);
        }
    }
}
