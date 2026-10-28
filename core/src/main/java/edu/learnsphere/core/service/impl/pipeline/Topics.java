package edu.learnsphere.core.service.impl.pipeline;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Turns names of parts of a course (module titles, concept names, section headings of its PDFs) into topics
 * to offer as questions: numbering removed, headings that are not topics (worked examples, exercises,
 * figures, front and back matter, sentences) left out, repeats dropped.
 */
final class Topics {

    /** Longest topic, in characters. */
    static final int MAX_LENGTH = 48;
    private static final int MAX_WORDS = 6;

    /** "Chapter 3", "2.4", "IV.", "Unit 2:" and similar at the start of a heading (a bare Roman numeral needs a dot). */
    private static final Pattern NUMBERING = Pattern.compile(
            "^(?:(?:chapter|section|unit|lecture|part|module|week|topic)\\s+(?:\\d+(?:\\.\\d+)*|[ivxlc]+)[.:)]?"
                    + "|\\d+(?:\\.\\d+)*[.:)]?|[ivxlc]+[.:)])\\s+",
            Pattern.CASE_INSENSITIVE);
    /** Headings that name a worked example, an exercise or a figure rather than a topic. */
    private static final Pattern NOT_A_TOPIC = Pattern.compile(
            "^(?:example|exercise|exercises|figure|fig\\.|table|solution|solutions|proof|theorem|lemma|corollary|"
                    + "definition|remark|note|question|questions|answer|answers|problem|problems|activity)\\b.*",
            Pattern.CASE_INSENSITIVE);
    private static final Set<String> FRONT_AND_BACK = Set.of("introduction", "summary", "conclusion", "conclusions",
            "preface", "contents", "table of contents", "references", "bibliography", "index", "glossary",
            "acknowledgements", "acknowledgments", "appendix", "foreword", "overview", "abstract", "further reading");

    private Topics() {
    }

    /** True for a heading that names a worked example, an exercise or a figure, which belongs to the section around it. */
    static boolean isPartOfSection(String heading) {
        return NOT_A_TOPIC.matcher(heading.strip()).matches();
    }

    /** {@code name} as a topic, or empty when it is not one. */
    static Optional<String> clean(String name) {
        if (name == null) {
            return Optional.empty();
        }
        String topic = name.strip().replaceAll("\\s+", " ");
        if (NOT_A_TOPIC.matcher(topic).matches()) {
            return Optional.empty();
        }
        topic = NUMBERING.matcher(topic).replaceFirst("").strip();
        topic = topic.replaceAll("[\\s.:;,]+$", "");
        if (topic.length() < 3 || topic.length() > MAX_LENGTH || topic.split(" ").length > MAX_WORDS
                || !topic.chars().anyMatch(Character::isLetter) || NOT_A_TOPIC.matcher(topic).matches()
                || FRONT_AND_BACK.contains(topic.toLowerCase(Locale.ROOT))) {
            return Optional.empty();
        }
        return Optional.of(Character.toUpperCase(topic.charAt(0)) + topic.substring(1));
    }

    /** The distinct topics among {@code names}, in order (the first spelling of each wins). */
    static List<String> distinct(List<String> names) {
        Map<String, String> topics = new LinkedHashMap<>();
        for (String name : names) {
            clean(name).ifPresent(topic -> topics.putIfAbsent(topic.toLowerCase(Locale.ROOT), topic));
        }
        return List.copyOf(topics.values());
    }

    /** At most {@code limit} of {@code topics}, spread evenly from first to last so they cover the whole course. */
    static List<String> spread(List<String> topics, int limit) {
        if (limit <= 0) {
            return List.of();
        }
        if (topics.size() <= limit) {
            return topics;
        }
        List<String> picked = new ArrayList<>(limit);
        for (int i = 0; i < limit; i++) {
            picked.add(topics.get((int) ((long) i * topics.size() / limit)));
        }
        return List.copyOf(picked);
    }
}
