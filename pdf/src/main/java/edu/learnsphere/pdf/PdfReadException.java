package edu.learnsphere.pdf;

import edu.learnsphere.core.error.UserMessage;
import edu.learnsphere.core.model.DocumentError;
import java.io.IOException;
import java.util.Objects;

/**
 * A PDF could not be read: it is missing, password-protected, damaged, or has no such page. Thrown by the
 * extractor, renderer and locator wherever their interfaces declare {@link IOException}, so callers that only
 * catch {@code IOException} keep working, while callers that want to tell the user why can read
 * {@link #reason()}, {@link #documentError()} and {@link #userMessage()}.
 *
 * <p>The exception message is technical (for logs); show only {@link #userMessage()}.
 */
public final class PdfReadException extends IOException {

    private static final long serialVersionUID = 1L;

    /** Why the PDF could not be read. */
    public enum Reason {
        /** The file does not exist or cannot be opened for reading. */
        MISSING(DocumentError.MISSING_FILE, DocumentError.MISSING_FILE.userMessage()),
        /** The file needs a password to open. */
        ENCRYPTED(DocumentError.ENCRYPTED, DocumentError.ENCRYPTED.userMessage()),
        /** The file is not a PDF or is too damaged to parse. */
        DAMAGED(DocumentError.DAMAGED, DocumentError.DAMAGED.userMessage()),
        /** The requested page number is outside the document. */
        NO_SUCH_PAGE(DocumentError.UNEXPECTED, UserMessage.of("This page isn't in the PDF."));

        private final DocumentError documentError;
        private final UserMessage userMessage;

        Reason(DocumentError documentError, UserMessage userMessage) {
            this.documentError = documentError;
            this.userMessage = userMessage;
        }
    }

    private final Reason reason;

    PdfReadException(Reason reason, String technicalDetail, Throwable cause) {
        super(technicalDetail, cause);
        this.reason = Objects.requireNonNull(reason, "reason");
    }

    PdfReadException(Reason reason, String technicalDetail) {
        this(reason, technicalDetail, null);
    }

    /** Why the PDF could not be read. */
    public Reason reason() {
        return reason;
    }

    /** The import error to record for a document that failed with this exception. */
    public DocumentError documentError() {
        return reason.documentError;
    }

    /** The sentence to show the user. */
    public UserMessage userMessage() {
        return reason.userMessage;
    }
}
