package edu.learnsphere.app.ui.studio.review;

import edu.learnsphere.core.service.JobQueue;
import java.util.Optional;

/**
 * What finished study-assistant jobs report, read from the job queue, and the sentences the review queue
 * shows for them. A job whose result can't be read still gets a plain sentence.
 */
final class JobOutcomes {

    /** What drafting questions produced. */
    record Drafted(int created, int duplicates, int unsupported) {
    }

    /** What finding a module's key concepts produced. */
    record Found(String moduleId, int concepts, int added, int removed) {
    }

    private JobOutcomes() {
    }

    static Optional<Drafted> drafted(JobQueue jobs, String jobId) {
        return read(jobs, jobId, Drafted.class);
    }

    static Optional<Found> found(JobQueue jobs, String jobId) {
        return read(jobs, jobId, Found.class);
    }

    private static <T> Optional<T> read(JobQueue jobs, String jobId, Class<T> type) {
        try {
            return jobs.result(jobId, type);
        } catch (RuntimeException unreadable) {
            // A job that stored something else (or nothing usable) still finished.
            return Optional.empty();
        }
    }

    /** "12 questions drafted, 2 left out because they repeated others." and so on. */
    static String draftedSentence(Drafted outcome) {
        if (outcome == null) {
            return "Your drafts are ready to review.";
        }
        if (outcome.created() == 0) {
            return outcome.duplicates() > 0
                    ? "No questions drafted: every one repeated a question you have."
                    : "No questions drafted. Try other concepts, or a module with more material.";
        }
        StringBuilder text = new StringBuilder(ReviewLabels.questions(outcome.created())).append(" drafted");
        if (outcome.duplicates() > 0) {
            text.append(", ").append(outcome.duplicates())
                    .append(outcome.duplicates() == 1 ? " left out because it repeated another" : " left out because they repeated others");
        }
        text.append('.');
        if (outcome.unsupported() > 0) {
            text.append(outcome.unsupported() == 1
                    ? " Check 1 closely: the source may not back it."
                    : " Check " + outcome.unsupported() + " closely: the source may not back them.");
        }
        return text.toString();
    }

    /** "Found 5 key concepts, 3 of them new." and so on. */
    static String foundSentence(Found outcome) {
        if (outcome == null) {
            return "Key concepts found.";
        }
        String found = "Found " + outcome.concepts() + (outcome.concepts() == 1 ? " key concept" : " key concepts");
        if (outcome.added() == 0) {
            return found + (outcome.concepts() == 1 ? ", one you had already." : ", all ones you had already.");
        }
        if (outcome.added() == outcome.concepts()) {
            return found + ".";
        }
        return found + ", " + outcome.added() + " of them new.";
    }
}
