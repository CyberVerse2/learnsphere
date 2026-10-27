package edu.learnsphere.core.spi;

import edu.learnsphere.core.util.CancellationToken;

/** Checks that a passage supports an item's answer (ai module, {@link ChatPriority#USER_WAITING}). */
public interface SupportChecker {

    SupportVerdict check(String prompt, String answer, Passage passage, CancellationToken token);
}
