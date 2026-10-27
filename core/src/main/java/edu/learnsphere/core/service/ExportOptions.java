package edu.learnsphere.core.service;

/**
 * Options for exporting a course package.
 *
 * @param protectionCode   the code learners must enter, required for {@code protected} courses and
 *                         ignored otherwise (the service wipes it after use)
 * @param includeDocuments whether the source PDFs travel with the package (needed for page citations)
 */
public record ExportOptions(char[] protectionCode, boolean includeDocuments) {

    /** A shareable package with its PDFs. */
    public static ExportOptions standard() {
        return new ExportOptions(null, true);
    }

    /** A protected package with its PDFs. */
    public static ExportOptions protectedWith(char[] code) {
        return new ExportOptions(code, true);
    }
}
