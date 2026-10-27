package edu.learnsphere.core.sharing;

import edu.learnsphere.core.error.UserMessage;

/** The sentences sharing failures show. Each follows the in-app copy rules. */
public final class SharingMessages {

    /** The file is not a course package at all. */
    public static final UserMessage NOT_A_COURSE_FILE =
            UserMessage.of("This isn't a LearnSphere course. Check that you picked the right file.");
    /** A package whose contents do not match its manifest, or that ends early. */
    public static final UserMessage COURSE_FILE_DAMAGED =
            UserMessage.of("This course file is damaged or incomplete. Ask the course author to send it again.");
    /** A package written in a format this release cannot read. */
    public static final UserMessage COURSE_FILE_TOO_NEW = UserMessage.of("Update LearnSphere to open this course.");
    /** A progress or feedback file written in a format this release cannot read. */
    public static final UserMessage FILE_TOO_NEW = UserMessage.of("Update LearnSphere to open this file.");
    /** The chosen file cannot be read. */
    public static final UserMessage FILE_NOT_OPENED =
            UserMessage.of("That file can't be opened. Check that it's still there and try again.");
    /** A protected package imported without a code. */
    public static final UserMessage CODE_REQUIRED =
            UserMessage.of("This course is protected. Enter the code from the course author.");
    /** A protected package imported with the wrong code. */
    public static final UserMessage WRONG_CODE = UserMessage.WRONG_COURSE_CODE;
    /** The package belongs to a course created in this profile. */
    public static final UserMessage COURSE_MADE_HERE =
            UserMessage.of("This course was made in this profile, so it's already in your library.");
    /** The package is an older version than the one in the library. */
    public static final UserMessage NEWER_VERSION_INSTALLED =
            UserMessage.of("Your library has a newer version of this course.");
    /** The package reuses records of another course in the library. */
    public static final UserMessage COURSE_CLASHES =
            UserMessage.of("This course overlaps with another course in your library, so it can't be added.");
    /** A protected import whose code was lost when the app closed. */
    public static final UserMessage IMPORT_INTERRUPTED =
            UserMessage.of("This import stopped when LearnSphere closed. Import the course again.");
    /** A private course cannot be exported. */
    public static final UserMessage PRIVATE_COURSE =
            UserMessage.of("This course is private. Make it shareable or protected to share it.");
    /** A course without a published version cannot be exported. */
    public static final UserMessage NOT_PUBLISHED = UserMessage.of("Publish this course before sharing it.");
    /** Only courses created in this profile can be exported. */
    public static final UserMessage NOT_MADE_HERE = UserMessage.of("Only courses made in this profile can be shared.");
    /** A protected course exported without a usable code. */
    public static final UserMessage PROTECTION_CODE_NEEDED =
            UserMessage.of("Choose a code of at least " + PackageKey.MIN_CODE_LENGTH + " characters to protect this course.");
    /** A PDF of the course is missing from the profile. */
    public static final UserMessage PDF_MISSING =
            UserMessage.of("A PDF in this course can't be found. Import it again, then share the course.");
    /** A course whose pages or passages pass the package limits. */
    public static final UserMessage COURSE_TOO_LARGE =
            UserMessage.of("This course is too large to share as one file. Split it into smaller courses, then share them.");
    /** A PDF of the course was processed again or removed while the course was being saved. */
    public static final UserMessage COURSE_CHANGED_WHILE_SAVING =
            UserMessage.of("This course changed while it was being saved. Save it again.");
    /** Writing the chosen file failed. */
    public static final UserMessage SAVE_FAILED = UserMessage.of("The file couldn't be saved. Try again or pick another folder.");
    /** A progress export without a learner name. */
    public static final UserMessage LABEL_REQUIRED = UserMessage.of("Enter the name the course author will see.");
    /** A learner name that is too long. */
    public static final UserMessage LABEL_TOO_LONG =
            UserMessage.of("Use a name of up to " + ProgressFile.MAX_LABEL_LENGTH + " characters.");
    /** A progress export for a course with no answers yet. */
    public static final UserMessage NO_PROGRESS =
            UserMessage.of("Answer some questions in this course, then share your progress.");
    /** The file is not a progress file. */
    public static final UserMessage NOT_A_PROGRESS_FILE =
            UserMessage.of("This isn't a LearnSphere progress file. Check that you picked the right file.");
    /** A progress file for another course. */
    public static final UserMessage PROGRESS_OTHER_COURSE = UserMessage.of("This progress is for a different course.");
    /** Class progress imported into a course not created here. */
    public static final UserMessage INSIGHTS_NEED_OWN_COURSE =
            UserMessage.of("Class progress can only be added to courses made in this profile.");
    /** Class progress import without files. */
    public static final UserMessage NO_PROGRESS_FILES = UserMessage.of("Choose at least one progress file.");
    /** An imported learner's progress that was removed meanwhile. */
    public static final UserMessage INSIGHT_NOT_FOUND = UserMessage.of("This learner's progress isn't in the course.");
    /** A feedback export without flags. */
    public static final UserMessage NO_FEEDBACK = UserMessage.of("You haven't flagged any questions in this course.");
    /** The file is not a feedback file. */
    public static final UserMessage NOT_A_FEEDBACK_FILE =
            UserMessage.of("This isn't a LearnSphere feedback file. Check that you picked the right file.");
    /** Feedback for a course that is not in this profile. */
    public static final UserMessage FEEDBACK_COURSE_MISSING =
            UserMessage.of("This feedback is for a course that isn't in your library.");
    /** Feedback for a course that was imported rather than created here. */
    public static final UserMessage FEEDBACK_NOT_YOUR_COURSE =
            UserMessage.of("This feedback is for a course that wasn't made in this profile.");

    private SharingMessages() {
    }
}
