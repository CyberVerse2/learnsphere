package edu.learnsphere.core.service.impl.pipeline;

import edu.learnsphere.core.db.Repositories;
import edu.learnsphere.core.model.Chunk;
import edu.learnsphere.core.model.Document;
import edu.learnsphere.core.model.DocumentStatus;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * A course's sections in reading order: its ready PDFs by import time, and within each the runs of passages under
 * one heading. Passages headed by a worked example, an exercise or a figure, or by no heading, belong to the
 * section before them, so each section names a topic.
 */
public final class CourseOutline {

    /** Longest heading kept, in characters. */
    static final int MAX_HEADING = 80;

    /** One section: its PDF, its heading, and its passages in order. */
    public record Section(String documentId, String documentTitle, String heading, List<String> chunkIds) {
        public Section {
            Objects.requireNonNull(heading, "heading");
            chunkIds = List.copyOf(chunkIds);
        }
    }

    private CourseOutline() {
    }

    /** The sections of {@code courseId}'s ready PDFs. */
    public static List<Section> of(Repositories repos, String courseId) {
        List<Section> sections = new ArrayList<>();
        for (Document document : repos.documents().listByCourse(courseId)) {
            if (document.status() != DocumentStatus.READY) {
                continue;
            }
            String heading = null;
            String current = null;
            List<String> chunkIds = new ArrayList<>();
            for (Chunk chunk : repos.chunks().listByDocument(document.id())) {
                String title = chunk.sectionTitle() == null ? "" : chunk.sectionTitle().strip().replaceAll("\\s+", " ");
                boolean continues = title.isEmpty() || title.equals(current) || Topics.isPartOfSection(title);
                if (!continues || heading == null) {
                    if (heading != null) {
                        sections.add(new Section(document.id(), document.title(), heading, chunkIds));
                        chunkIds = new ArrayList<>();
                    }
                    heading = continues ? document.title().strip() : shorten(title);
                    current = continues ? null : title;
                }
                chunkIds.add(chunk.id());
            }
            if (heading != null) {
                sections.add(new Section(document.id(), document.title(), heading, chunkIds));
            }
        }
        return List.copyOf(sections);
    }

    private static String shorten(String heading) {
        return heading.length() <= MAX_HEADING ? heading : heading.substring(0, MAX_HEADING - 1).strip() + "…";
    }
}
