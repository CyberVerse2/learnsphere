package edu.learnsphere.core.error;

import java.util.Objects;

/**
 * One short sentence a first-time user can understand and act on, shown in the interface in place of
 * any technical error text.
 *
 * <p>Copy rules: describe what the person sees and can do; no implementation words (model, server,
 * index, embedding, chunk, file paths, codes); no build history ("now", "new", "no longer"). For example
 * "This PDF is password-protected. Remove the password and import it again."
 *
 * <p>Keep the learner's own content (course titles, file names, question text) out of messages: the
 * interface shows it next to the message anyway. Text longer than {@value #MAX_LENGTH} characters is
 * shortened at a word boundary and ends with an ellipsis instead of failing, so building a message can
 * never break the error path it is part of.
 *
 * @param text the sentence (or two very short sentences), at most {@value #MAX_LENGTH} characters
 */
public record UserMessage(String text) {

    /** Longest allowed message. */
    public static final int MAX_LENGTH = 160;

    /** Anything unexpected. */
    public static final UserMessage SOMETHING_WENT_WRONG = new UserMessage("Something went wrong. Try again.");
    /** Something the user picked was removed in the meantime. */
    public static final UserMessage NOT_AVAILABLE = new UserMessage("This isn't available.");
    /** A course id that does not exist in this profile. */
    public static final UserMessage COURSE_NOT_FOUND = new UserMessage("This course isn't in your library.");
    /** A module id that does not exist. */
    public static final UserMessage MODULE_NOT_FOUND = new UserMessage("This module isn't in the course.");
    /** A document id that does not exist. */
    public static final UserMessage DOCUMENT_NOT_FOUND = new UserMessage("This PDF isn't in the course.");
    /** A learning item id that does not exist. */
    public static final UserMessage ITEM_NOT_FOUND = new UserMessage("This question isn't in the course.");
    /** A profile id that does not exist. */
    public static final UserMessage PROFILE_NOT_FOUND = new UserMessage("This profile isn't on this computer.");
    /** The study assistant is still starting. */
    public static final UserMessage ASSISTANT_STARTING =
            new UserMessage("Your study assistant is getting ready. Try again in a moment.");
    /** The study assistant cannot run on this computer or stopped. */
    public static final UserMessage ASSISTANT_UNAVAILABLE =
            new UserMessage("Your study assistant isn't available. Restart it from Settings.");
    /** A wrong profile password. */
    public static final UserMessage WRONG_PASSWORD = new UserMessage("That password isn't right. Try again.");
    /** A wrong code for a protected course. */
    public static final UserMessage WRONG_COURSE_CODE = new UserMessage("That code isn't right. Check it with the course author.");
    /** A profile's data could not be opened or prepared. */
    public static final UserMessage PROFILE_NOT_OPENED =
            new UserMessage("This profile couldn't be opened. Restart LearnSphere and try again.");
    /** Not enough free disk space. */
    public static final UserMessage DISK_FULL = new UserMessage("Your computer is out of space. Free some up and try again.");

    public UserMessage {
        Objects.requireNonNull(text, "text");
        text = text.strip();
        if (text.isEmpty()) {
            throw new IllegalArgumentException("A user message needs text");
        }
        if (text.length() > MAX_LENGTH) {
            text = shorten(text);
        }
    }

    private static String shorten(String text) {
        int limit = MAX_LENGTH - 1;
        int cut = text.lastIndexOf(' ', limit);
        if (cut < MAX_LENGTH / 2) {
            cut = limit;
        }
        if (Character.isLowSurrogate(text.charAt(cut))) {
            cut--;
        }
        return text.substring(0, cut).stripTrailing() + "…";
    }

    /** Shorthand for {@code new UserMessage(text)}. */
    public static UserMessage of(String text) {
        return new UserMessage(text);
    }

    @Override
    public String toString() {
        return text;
    }
}
