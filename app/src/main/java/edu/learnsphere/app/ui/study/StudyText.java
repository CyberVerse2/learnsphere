package edu.learnsphere.app.ui.study;

import edu.learnsphere.app.ui.components.Formats;
import edu.learnsphere.core.model.GradedBy;
import edu.learnsphere.core.model.ItemType;
import edu.learnsphere.core.model.Rating;
import edu.learnsphere.core.model.SessionKind;
import edu.learnsphere.core.service.Feedback;
import edu.learnsphere.core.service.ForecastDay;
import edu.learnsphere.core.service.SessionSummary;
import edu.learnsphere.core.service.StudySession;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Locale;

/**
 * The words of a study session in one place: what a session and an item are called, verdicts, how long until
 * an item comes back, and the sentences of the summary. Everything is said the way a learner would say it
 * ("You'll see this again in 4 days"), never in scheduling terms. Stateless.
 */
final class StudyText {

    private static final long MINUTE = Duration.ofMinutes(1).toMillis();
    private static final long HOUR = Duration.ofHours(1).toMillis();
    private static final long DAY = Duration.ofDays(1).toMillis();

    private StudyText() {
    }

    /** What a kind of session is called, e.g. "Flashcards". */
    static String kindTitle(SessionKind kind) {
        return switch (kind) {
            case STUDY_NEXT -> "Study next";
            case MODULE_QUIZ -> "Module quiz";
            case FLASHCARDS -> "Flashcards";
            case WEAK_CONCEPTS -> "Weak spots";
        };
    }

    /** The session's name in the top bar: its course and module, with the kind when it is not Study next. */
    static String sessionTitle(StudySession session) {
        String place = session.moduleTitle() != null && session.courseTitle() != null
                ? session.courseTitle() + "  ·  " + session.moduleTitle()
                : session.courseTitle() != null ? session.courseTitle() : session.moduleTitle();
        if (place == null) {
            return kindTitle(session.kind());
        }
        return session.kind() == SessionKind.STUDY_NEXT ? place : kindTitle(session.kind()) + "  ·  " + place;
    }

    /** What a kind of item is called, for the line above the question. */
    static String typeLabel(ItemType type) {
        return switch (type) {
            case MCQ -> "Multiple choice";
            case TRUE_FALSE -> "True or false";
            case SHORT_ANSWER -> "Short answer";
            case FLASHCARD -> "Flashcard";
            case CLOZE -> "Fill in the blank";
        };
    }

    /** A rating's name on the flashcard buttons. */
    static String ratingLabel(Rating rating) {
        return switch (rating) {
            case AGAIN -> "Again";
            case HARD -> "Hard";
            case GOOD -> "Good";
            case EASY -> "Easy";
        };
    }

    /** An answer key as the learner reads it: "True" for {@code "true"}, otherwise the text itself. */
    static String answerText(ItemType type, String answer) {
        if (type == ItemType.TRUE_FALSE) {
            String key = answer.strip().toLowerCase(Locale.ROOT);
            if (key.equals("true") || key.equals("false")) {
                return Character.toUpperCase(key.charAt(0)) + key.substring(1);
            }
        }
        return answer.strip();
    }

    /** The headline of the feedback. */
    static String verdict(ItemType type, Feedback feedback) {
        if (feedback.disputed()) {
            return "Counted as correct";
        }
        if (type == ItemType.FLASHCARD) {
            return "Rated " + ratingLabel(feedback.rating());
        }
        if (feedback.gradedBy() == GradedBy.AI) {
            if (feedback.rating() == Rating.AGAIN) {
                return "Not quite";
            }
            return feedback.rating() == Rating.HARD ? "Partly right" : "Correct";
        }
        return feedback.correct() ? "Correct" : "Not quite";
    }

    /**
     * How long until something {@code dueAt} comes back, short enough for a button: "1 min", "10 min",
     * "5 hours", "4 days", "3 weeks", "2 months", "1 year".
     */
    static String interval(long now, long dueAt) {
        long ms = Math.max(0, dueAt - now);
        if (ms < HOUR) {
            return Math.max(1, Math.round(ms / (double) MINUTE)) + " min";
        }
        if (ms < DAY) {
            long hours = Math.round(ms / (double) HOUR);
            return hours >= 24 ? "1 day" : plural(hours, "hour");
        }
        long days = Math.round(ms / (double) DAY);
        if (days < 14) {
            return plural(days, "day");
        }
        if (days < 60) {
            return plural(Math.round(days / 7.0), "week");
        }
        if (days < 365) {
            return plural(Math.round(days / 30.0), "month");
        }
        return plural(Math.round(days / 365.0), "year");
    }

    /**
     * The line after an answer saying when the item comes back: later in this session when it was queued
     * again, in minutes when that is soon, otherwise in plain days ("You'll see this again in 4 days.").
     */
    static String comesBack(long dueAt, boolean againThisSession, Clock clock) {
        if (againThisSession) {
            return "You'll see this again later in this session.";
        }
        return "You'll see this again " + when(dueAt, clock) + ".";
    }

    /**
     * When something happens, from now: "in 5 minutes", "in 3 hours", "later today", "tomorrow", "in 4 days",
     * "in 3 weeks".
     */
    static String when(long at, Clock clock) {
        long now = clock.millis();
        long ms = at - now;
        if (ms <= MINUTE) {
            return "in a minute";
        }
        if (ms < HOUR) {
            return "in " + plural(Math.round(ms / (double) MINUTE), "minute");
        }
        LocalDate today = LocalDate.now(clock);
        LocalDate day = Instant.ofEpochMilli(at).atZone(clock.getZone()).toLocalDate();
        if (day.equals(today)) {
            return ms < 6 * HOUR ? "in " + plural(Math.round(ms / (double) HOUR), "hour") : "later today";
        }
        return Formats.inTime(at, clock);
    }

    /** "Your next review is tomorrow.", or null when nothing is scheduled. */
    static String nextReview(Long at, Clock clock) {
        return at == null ? null : "Your next review is " + when(at, clock) + ".";
    }

    /**
     * How many questions Study next has ready, e.g. "5 questions are ready in this course."
     *
     * @param inCourse true for one course, false across every course being studied
     */
    static String readyToStudy(int count, boolean inCourse) {
        return Formats.count(count, "question is", "questions are") + " ready "
                + (inCourse ? "in this course." : "across your courses.");
    }

    /** The summary's headline. */
    static String headline(SessionSummary summary) {
        if (summary.answered() == 0) {
            return "Session ended";
        }
        if (summary.accuracy() >= 0.85) {
            return "Excellent work";
        }
        if (summary.accuracy() >= 0.6) {
            return "Good session";
        }
        return "Every review counts";
    }

    /** The line under the summary's headline, e.g. "You answered 17 questions in 12 min." */
    static String recap(SessionSummary summary) {
        if (summary.answered() == 0) {
            return "You didn't answer any questions this time. They stay ready for your next session.";
        }
        String answered = summary.answered() < summary.itemCount()
                ? summary.answered() + " of " + Formats.count(summary.itemCount(), "question", "questions")
                : Formats.count(summary.answered(), "question", "questions");
        return "You answered " + answered + " in " + Formats.duration(summary.durationMs()) + ".";
    }

    /**
     * The reviews coming up, from a 14-day forecast that starts today: "4 reviews tomorrow, then 11 more this
     * week." Falls back to when the next review is; null when nothing is scheduled at all.
     *
     * @param forecast    reviews due per day, today first (may be empty)
     * @param nextReview  when the next review is due, or null
     */
    static String comingUp(List<ForecastDay> forecast, Long nextReview, Clock clock) {
        int tomorrow = forecast.size() > 1 ? forecast.get(1).dueCount() : 0;
        int week = 0;
        for (int i = 2; i < Math.min(7, forecast.size()); i++) {
            week += forecast.get(i).dueCount();
        }
        if (tomorrow > 0 && week > 0) {
            return Formats.count(tomorrow, "review", "reviews") + " tomorrow, then " + Formats.number(week)
                    + " more this week.";
        }
        if (tomorrow > 0) {
            return Formats.count(tomorrow, "review", "reviews") + " tomorrow.";
        }
        if (week > 0) {
            return "Nothing tomorrow, then " + Formats.count(week, "review", "reviews") + " later this week.";
        }
        return nextReview(nextReview, clock);
    }

    private static String plural(long n, String unit) {
        return n + " " + (n == 1 ? unit : unit + "s");
    }
}
