package edu.learnsphere.core.model;

import edu.learnsphere.core.error.UserMessage;

/**
 * Why a document import stopped ({@code documents.error_code}), with the sentence shown to the user.
 * Stored as its lowercase {@link #code() code}.
 */
public enum DocumentError implements CodedEnum {

    /** Larger than 300 MB. */
    TOO_LARGE("This PDF is larger than 300 MB. Split it into smaller parts and import them."),
    /** Has more than 2,000 pages. */
    TOO_MANY_PAGES("This PDF has more than 2,000 pages. Split it into smaller parts and import them."),
    /** Cannot be opened or parsed. */
    DAMAGED("This PDF can't be opened. It may be damaged."),
    /** Password-protected. */
    ENCRYPTED("This PDF is password-protected. Remove the password and import it again."),
    /** No page has any text, and none could be read from images. */
    NO_TEXT("No readable text was found in this PDF."),
    /** The imported copy is missing from the profile. */
    MISSING_FILE("This PDF couldn't be found. Import it again."),
    /** Scanned pages could not be read. */
    READ_FAILED("Some scanned pages couldn't be read. Try again."),
    /** Passages could not be prepared or made searchable. */
    PREPARE_FAILED("This PDF couldn't be prepared for questions. Try again."),
    /** Not enough disk space. */
    DISK_FULL("Your computer is out of space. Free some up and try again."),
    /** Anything else. */
    UNEXPECTED("Something went wrong while importing this PDF. Try again.");

    private final String code = CodedEnum.codeOf(this);
    private final UserMessage userMessage;

    DocumentError(String userMessage) {
        this.userMessage = UserMessage.of(userMessage);
    }

    @Override
    public String code() {
        return code;
    }

    /** The sentence shown on the document's status. */
    public UserMessage userMessage() {
        return userMessage;
    }

    /**
     * The constant stored as {@code code}.
     *
     * @throws IllegalArgumentException for an unknown code
     */
    public static DocumentError fromCode(String code) {
        return CodedEnum.fromCode(DocumentError.class, code);
    }
}
