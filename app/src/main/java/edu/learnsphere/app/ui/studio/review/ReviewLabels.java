package edu.learnsphere.app.ui.studio.review;

import edu.learnsphere.app.ui.components.Formats;
import edu.learnsphere.core.model.Difficulty;
import edu.learnsphere.core.model.FlagReason;
import edu.learnsphere.core.model.FlagSource;
import edu.learnsphere.core.model.ItemOrigin;
import edu.learnsphere.core.model.ItemStatus;
import edu.learnsphere.core.model.ItemType;

/** The words the review queue uses for types, statuses, difficulties and flags, in one place. */
final class ReviewLabels {

    private ReviewLabels() {
    }

    /** "Multiple choice", "True or false", … */
    static String type(ItemType type) {
        return switch (type) {
            case MCQ -> "Multiple choice";
            case TRUE_FALSE -> "True or false";
            case SHORT_ANSWER -> "Short answer";
            case FLASHCARD -> "Flashcard";
            case CLOZE -> "Fill in the blank";
        };
    }

    /** Plural for filters and counts: "Multiple choice questions", "Flashcards", … */
    static String typePlural(ItemType type) {
        return switch (type) {
            case MCQ -> "Multiple choice";
            case TRUE_FALSE -> "True or false";
            case SHORT_ANSWER -> "Short answer";
            case FLASHCARD -> "Flashcards";
            case CLOZE -> "Fill in the blank";
        };
    }

    static String difficulty(Difficulty difficulty) {
        return switch (difficulty) {
            case EASY -> "Easy";
            case MEDIUM -> "Medium";
            case HARD -> "Hard";
        };
    }

    /** The status segment's label, e.g. "Drafts". */
    static String status(ItemStatus status) {
        return switch (status) {
            case DRAFT -> "Drafts";
            case APPROVED -> "Approved";
            case REJECTED -> "Rejected";
            case RETIRED -> "Retired";
        };
    }

    /** The status segment's label with its count, e.g. "Drafts  12". */
    static String status(ItemStatus status, int count) {
        return status(status) + "  " + Formats.number(count);
    }

    static String origin(ItemOrigin origin) {
        return origin == ItemOrigin.AI ? "AI draft" : "Written by hand";
    }

    /** What a flag says is wrong, as a sentence. */
    static String flagReason(FlagReason reason) {
        return switch (reason) {
            case WRONG_ANSWER -> "The answer looks wrong";
            case UNCLEAR -> "The question is unclear";
            case NOT_IN_MATERIAL -> "It isn't covered by the material";
            case TYPO -> "There's a typo";
            case OTHER -> "Something else is wrong";
        };
    }

    /** Who raised a flag. */
    static String flagSource(FlagSource source) {
        return source == FlagSource.LEARNER_FEEDBACK ? "A learner flagged this" : "You flagged this";
    }

    /** "1 question", "3 questions". */
    static String questions(long n) {
        return Formats.count(n, "question", "questions");
    }
}
