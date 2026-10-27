package edu.learnsphere.app.ui.studio.review;

import java.util.ArrayList;
import java.util.List;

/**
 * A short-answer item's reference answer: the model answer, optionally followed by the key points a good
 * answer covers. Both live in the item's answer text so learners see them after answering and grading
 * compares against both:
 *
 * <pre>
 * Osmosis is the movement of water across a membrane.
 *
 * Key points:
 * • water moves
 * • from low to high solute concentration
 * </pre>
 *
 * @param modelAnswer the answer itself, stripped
 * @param keyPoints   the points, stripped and without blanks
 */
record ShortAnswerText(String modelAnswer, List<String> keyPoints) {

    /** The line that starts the key points. */
    static final String HEADING = "Key points:";
    private static final String SEPARATOR = "\n\n" + HEADING + "\n";
    private static final String BULLET = "• ";

    ShortAnswerText {
        modelAnswer = modelAnswer == null ? "" : modelAnswer.strip();
        List<String> points = new ArrayList<>();
        if (keyPoints != null) {
            for (String point : keyPoints) {
                String clean = point == null ? "" : point.strip();
                if (!clean.isEmpty()) {
                    points.add(clean);
                }
            }
        }
        keyPoints = List.copyOf(points);
    }

    /** Splits a stored answer; an answer without key points is all model answer. */
    static ShortAnswerText parse(String answer) {
        String text = answer == null ? "" : answer.strip().replace("\r\n", "\n");
        int at;
        int pointsFrom;
        if (text.startsWith(HEADING + "\n")) {
            // Key points without a model answer.
            at = 0;
            pointsFrom = HEADING.length() + 1;
        } else {
            at = text.indexOf(SEPARATOR);
            pointsFrom = at + SEPARATOR.length();
        }
        if (at < 0) {
            return new ShortAnswerText(text, List.of());
        }
        List<String> points = new ArrayList<>();
        for (String line : text.substring(pointsFrom).split("\n")) {
            String point = line.strip();
            for (String bullet : List.of("•", "-", "*")) {
                if (point.startsWith(bullet)) {
                    point = point.substring(bullet.length()).strip();
                    break;
                }
            }
            points.add(point);
        }
        return new ShortAnswerText(text.substring(0, at), points);
    }

    /** The answer text to store. */
    String compose() {
        if (keyPoints.isEmpty()) {
            return modelAnswer;
        }
        StringBuilder text = new StringBuilder(modelAnswer).append(SEPARATOR);
        for (int i = 0; i < keyPoints.size(); i++) {
            if (i > 0) {
                text.append('\n');
            }
            text.append(BULLET).append(keyPoints.get(i));
        }
        return text.toString();
    }
}
