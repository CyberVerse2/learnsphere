package edu.learnsphere.core.spi;

import edu.learnsphere.core.util.CancellationToken;

/** Grades a short free-text answer against the reference (ai module, {@link ChatPriority#INTERACTIVE}). */
public interface ShortAnswerGrader {

    /**
     * @param context the item's source passage, or null
     */
    GradeResult grade(String question, String referenceAnswer, String learnerAnswer, Passage context, CancellationToken token);
}
