package edu.learnsphere.testbench.metrics;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Citation markers in answers as the answerer writes them: {@code [2]}, {@code [1, 3]}, {@code [2-4]},
 * {@code [1 and 2]}, the same forms the app's citation validator accepts.
 */
public final class Citations {

    private static final Pattern MARKER = Pattern.compile(
            "\\[\\s*(\\d{1,3}(?:\\s*(?:,|;|-|–|—|and)\\s*\\d{1,3})*)\\s*]");
    private static final Pattern NUMBER = Pattern.compile("(\\d{1,3})(?:\\s*[-–—]\\s*(\\d{1,3}))?");

    private Citations() {
    }

    /** Every passage number cited in {@code text}, once per mention, ranges expanded (at most 20 per range). */
    public static List<Integer> markerNumbers(String text) {
        List<Integer> numbers = new ArrayList<>();
        if (text == null) {
            return numbers;
        }
        Matcher marker = MARKER.matcher(text);
        while (marker.find()) {
            Matcher number = NUMBER.matcher(marker.group(1));
            while (number.find()) {
                int from = Integer.parseInt(number.group(1));
                int to = number.group(2) == null ? from : Integer.parseInt(number.group(2));
                if (to < from || to - from > 20) {
                    to = from;
                }
                for (int n = from; n <= to; n++) {
                    numbers.add(n);
                }
            }
        }
        return numbers;
    }

    /** How many of {@code numbers} name one of {@code passageCount} passages (numbered from 1). */
    public static long validCount(List<Integer> numbers, int passageCount) {
        return numbers.stream().filter(n -> n >= 1 && n <= passageCount).count();
    }
}
