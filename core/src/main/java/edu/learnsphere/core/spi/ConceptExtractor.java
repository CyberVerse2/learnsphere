package edu.learnsphere.core.spi;

import edu.learnsphere.core.util.CancellationToken;
import java.util.List;

/** Finds 5–15 key concepts of a module from its passages (ai module, {@link ChatPriority#USER_WAITING}). */
public interface ConceptExtractor {

    /** Concepts in order of importance, without duplicates. */
    List<ConceptDraft> extract(String moduleTitle, List<Passage> passages, CancellationToken token);
}
