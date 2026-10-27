package edu.learnsphere.core.sharing;

import java.io.IOException;

/**
 * A package entry that does not match its manifest: wrong size or hash, a failed authentication tag, or
 * content past its limit. An {@link IOException} so it travels through stream chains; readers turn it into
 * {@link SharingMessages#COURSE_FILE_DAMAGED}.
 */
final class DamagedPackageException extends IOException {

    private static final long serialVersionUID = 1L;

    DamagedPackageException(String detail) {
        super(detail);
    }

    DamagedPackageException(String detail, Throwable cause) {
        super(detail, cause);
    }

    /** True when {@code error} or one of its causes is a {@code DamagedPackageException}. */
    static boolean isCause(Throwable error) {
        for (Throwable t = error; t != null; t = t.getCause()) {
            if (t instanceof DamagedPackageException) {
                return true;
            }
        }
        return false;
    }
}
