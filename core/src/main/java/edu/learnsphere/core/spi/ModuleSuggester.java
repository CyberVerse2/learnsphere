package edu.learnsphere.core.spi;

import edu.learnsphere.core.error.UnavailableException;
import edu.learnsphere.core.error.UserMessage;
import edu.learnsphere.core.util.CancellationToken;
import java.util.List;
import java.util.Objects;

/**
 * Groups a course's sections, in reading order, into modules (implemented in the ai module, priority
 * {@link ChatPriority#INTERACTIVE}: the creator is waiting). Each module starts at one section and runs until the
 * next module starts.
 */
public interface ModuleSuggester {

    /** For workspaces without the study assistant: always unavailable. */
    ModuleSuggester NONE = (course, sections, token) -> {
        throw new UnavailableException(UserMessage.of("The study assistant can't suggest modules right now."),
                "no module suggester");
    };

    /**
     * Modules for {@code sections}, in order. The result may be unordered or out of range; the caller keeps what
     * makes sense.
     *
     * @param course   the course title
     * @param sections the course's sections in reading order
     * @throws java.util.concurrent.CancellationException when {@code token} is cancelled
     */
    List<Suggestion> suggest(String course, List<Section> sections, CancellationToken token);

    /** A section of the course: a heading of one of its PDFs. */
    record Section(String documentTitle, String heading) {
        public Section {
            Objects.requireNonNull(documentTitle, "documentTitle");
            Objects.requireNonNull(heading, "heading");
        }
    }

    /**
     * One suggested module.
     *
     * @param firstSection index into the sections of the first one the module covers
     */
    record Suggestion(String title, String summary, int firstSection) {
        public Suggestion {
            Objects.requireNonNull(title, "title");
            summary = summary == null ? "" : summary;
        }
    }
}
