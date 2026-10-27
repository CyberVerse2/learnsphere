package edu.learnsphere.ai.fake;

import edu.learnsphere.core.spi.Passage;
import edu.learnsphere.core.spi.SupportChecker;
import edu.learnsphere.core.spi.SupportVerdict;
import edu.learnsphere.core.util.CancellationToken;

/** Supported when at least half of the answer's content words appear in the passage. */
public final class FakeSupportChecker implements SupportChecker {

    public static final double THRESHOLD = 0.5;

    @Override
    public SupportVerdict check(String prompt, String answer, Passage passage, CancellationToken token) {
        token.throwIfCancelled();
        if (passage == null) {
            return new SupportVerdict(false, "There's no source passage for this item.");
        }
        double coverage = FakeText.coverage(answer, passage.text());
        return coverage >= THRESHOLD
                ? new SupportVerdict(true, "The source passage on " + passage.pageLabel() + " backs this answer.")
                : new SupportVerdict(false, "The source passage on " + passage.pageLabel() + " doesn't mention this answer.");
    }
}
