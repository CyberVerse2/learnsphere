package edu.learnsphere.ai.prompt;

import edu.learnsphere.core.spi.Passage;
import java.util.ArrayList;
import java.util.List;

/**
 * Writes numbered passages into prompts and keeps a prompt's passages within a size budget.
 *
 * <pre>
 * [2] Operating Systems notes · p. 4 · CPU scheduling
 * Round robin gives each process a fixed time slice …
 * </pre>
 */
public final class PassageFormat {

    private PassageFormat() {
    }

    /** One passage with its {@code [n]} label, source line and text. */
    public static String format(Passage passage) {
        StringBuilder header = new StringBuilder("[").append(passage.number()).append("]");
        if (!passage.documentTitle().isBlank()) {
            header.append(' ').append(passage.documentTitle().strip());
            header.append(" · ").append(passage.pageLabel());
        } else {
            header.append(' ').append(passage.pageLabel());
        }
        if (passage.sectionTitle() != null && !passage.sectionTitle().isBlank()) {
            header.append(" · ").append(passage.sectionTitle().strip());
        }
        return header + "\n" + clean(passage.text());
    }

    /** Passages separated by blank lines. */
    public static String format(List<Passage> passages) {
        List<String> parts = new ArrayList<>(passages.size());
        for (Passage passage : passages) {
            parts.add(format(passage));
        }
        return String.join("\n\n", parts);
    }

    /**
     * The passages to send when all of them would exceed {@code maxChars}: an evenly spread selection in
     * the original order (so a long module is sampled from start to end), each passage cut to at most
     * {@code maxChars} characters. Passage numbers are kept, so citations still map to the caller's list.
     */
    public static List<Passage> fit(List<Passage> passages, int maxChars) {
        if (passages.isEmpty()) {
            return List.of();
        }
        List<Passage> cut = passages.stream().map(p -> truncate(p, maxChars)).toList();
        int total = cut.stream().mapToInt(PassageFormat::size).sum();
        if (total <= maxChars) {
            return cut;
        }
        int average = Math.max(1, total / cut.size());
        int keep = Math.max(1, Math.min(cut.size(), maxChars / average));
        while (keep > 1) {
            List<Passage> picked = spread(cut, keep);
            if (picked.stream().mapToInt(PassageFormat::size).sum() <= maxChars) {
                return picked;
            }
            keep--;
        }
        return List.of(cut.getFirst());
    }

    private static List<Passage> spread(List<Passage> passages, int keep) {
        List<Passage> picked = new ArrayList<>(keep);
        double step = (double) passages.size() / keep;
        for (int i = 0; i < keep; i++) {
            picked.add(passages.get((int) Math.floor(i * step)));
        }
        return picked;
    }

    private static int size(Passage passage) {
        return passage.text().length() + passage.documentTitle().length() + 40;
    }

    private static Passage truncate(Passage passage, int maxChars) {
        int limit = Math.max(200, maxChars - 200);
        if (passage.text().length() <= limit) {
            return passage;
        }
        String text = passage.text().substring(0, limit);
        int space = text.lastIndexOf(' ');
        if (space > limit / 2) {
            text = text.substring(0, space);
        }
        return new Passage(passage.number(), passage.chunkId(), passage.documentId(), passage.documentTitle(),
                passage.pageStart(), passage.pageEnd(), passage.sectionTitle(), text + " …", passage.relevance());
    }

    /** Collapses runs of spaces and blank lines so passages cost fewer tokens. */
    static String clean(String text) {
        return text.replace("\r\n", "\n")
                .replaceAll("[ \\t\\x0B\\f]+", " ")
                .replaceAll(" *\\n *", "\n")
                .replaceAll("\\n{3,}", "\n\n")
                .strip();
    }
}
