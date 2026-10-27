package edu.learnsphere.app.ui.ask;

/**
 * How Ask shares its width: the source viewer takes a share of the screen when open, the conversation keeps
 * at least a comfortable reading measure, and the list of conversations shows only while both fit beside it.
 * The learner can hide the list, or bring it back while the conversation still has a narrower measure.
 */
final class AskLayout {

    /** The source viewer's share of the width, within its smallest and largest sizes. */
    static final double SOURCE_SHARE = 0.38;
    static final double SOURCE_MIN = 360;
    static final double SOURCE_MAX = 560;
    /** Narrowest conversation that shows the list of conversations on its own. */
    static final double COMFORTABLE_CONVERSATION = 440;
    /** Narrowest conversation beside a list the learner asked for. */
    static final double TIGHT_CONVERSATION = 340;

    /**
     * @param threads true when the list of conversations shows
     * @param source  width of the source viewer, 0 when closed
     */
    record Widths(boolean threads, double source) {
    }

    private AskLayout() {
    }

    /**
     * @param width         the screen's width
     * @param sourceOpen    true while the source viewer is open
     * @param threadsWanted the learner's choice for the list, or null to show it whenever it fits comfortably
     */
    static Widths compute(double width, boolean sourceOpen, Boolean threadsWanted) {
        double source = sourceOpen ? sourceWidth(width) : 0;
        double rest = width - source - ThreadList.WIDTH;
        boolean threads = threadsWanted == null ? rest >= COMFORTABLE_CONVERSATION
                : threadsWanted && rest >= TIGHT_CONVERSATION;
        return new Widths(threads, source);
    }

    /** The source viewer's width on a screen {@code width} wide. */
    static double sourceWidth(double width) {
        return Math.round(Math.max(SOURCE_MIN, Math.min(SOURCE_MAX, width * SOURCE_SHARE)));
    }
}
