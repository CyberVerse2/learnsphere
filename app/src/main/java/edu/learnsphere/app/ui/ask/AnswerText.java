package edu.learnsphere.app.ui.ask;

import edu.learnsphere.app.ui.components.Formats;
import edu.learnsphere.core.model.Citation;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Plain-text forms of an answer: the streamed preview without its citation markers, the text put on the
 * clipboard with page references in place of the markers, and thread titles as the list shows them.
 */
final class AnswerText {

    /** One citation marker, {@code [12]}. */
    static final Pattern MARKER = Pattern.compile("\\[(\\d{1,3})]");
    /** A run of markers with the space before it, {@code " [1][3]"}. */
    private static final Pattern MARKER_RUN = Pattern.compile("[ \\t]*((?:\\[\\d{1,3}])+)");
    /** Any bracketed group of numbers as a model may write it, {@code [1, 3]}. */
    private static final Pattern MARKER_GROUP = Pattern.compile("[ \\t]*\\[\\d{1,3}(?:\\s*[,;]\\s*\\d{1,3})*]");
    /** A marker still being written at the end of a stream, {@code "[1"}. */
    private static final Pattern OPEN_MARKER = Pattern.compile("[ \\t]*\\[\\d{0,3}$");

    /** Title of a conversation before its first question names it. */
    static final String UNTITLED = "New conversation";

    private AnswerText() {
    }

    /**
     * The streamed text without citation markers (whole groups and one still being written at the end), so the
     * preview reads cleanly until the final answer brings its page chips.
     */
    static String preview(String streamed) {
        if (streamed == null || streamed.isEmpty()) {
            return "";
        }
        String text = MARKER_GROUP.matcher(streamed).replaceAll("");
        return OPEN_MARKER.matcher(text).replaceAll("");
    }

    /**
     * The answer for the clipboard: Markdown kept, each run of markers replaced by its pages in brackets
     * ({@code "… overhead (p. 17; p. 9)."}), unknown markers dropped, and a list of the sources at the end.
     */
    static String forClipboard(String text, List<Citation> citations) {
        String body = text == null ? "" : text;
        Matcher run = MARKER_RUN.matcher(body);
        StringBuilder out = new StringBuilder();
        while (run.find()) {
            Set<String> pages = new LinkedHashSet<>();
            Matcher marker = MARKER.matcher(run.group(1));
            while (marker.find()) {
                Citation citation = find(citations, Integer.parseInt(marker.group(1)));
                if (citation != null) {
                    pages.add(Formats.pages(citation));
                }
            }
            run.appendReplacement(out, Matcher.quoteReplacement(pages.isEmpty() ? "" : " (" + String.join("; ", pages) + ")"));
        }
        run.appendTail(out);
        String answer = out.toString().strip();
        List<String> sources = sourceLines(citations);
        if (sources.isEmpty()) {
            return answer;
        }
        return answer + "\n\nSources\n" + String.join("\n", sources);
    }

    /** "Lecture 1 – Processes and Threads, p. 17" for each cited passage, without repeats. */
    private static List<String> sourceLines(List<Citation> citations) {
        Set<String> lines = new LinkedHashSet<>();
        for (Citation citation : citations) {
            String title = citation.documentTitle().isBlank() ? "" : citation.documentTitle() + ", ";
            lines.add("- " + title + Formats.pages(citation));
        }
        return new ArrayList<>(lines);
    }

    /** The citation numbered {@code number}, or null. */
    static Citation find(List<Citation> citations, int number) {
        for (Citation citation : citations) {
            if (citation.number() == number) {
                return citation;
            }
        }
        return null;
    }

    /** A conversation's title as the list and the heading show it. */
    static String title(String stored) {
        return stored == null || stored.isBlank() ? UNTITLED : stored.strip();
    }
}
