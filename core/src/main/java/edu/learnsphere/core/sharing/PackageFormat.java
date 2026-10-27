package edu.learnsphere.core.sharing;

import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The {@code .learnsphere} course package: a zip holding
 *
 * <pre>
 * manifest.json        format, format version, package id, version number, title, author, created at,
 *                      counts, protection (salt, iterations, key check) and size + SHA-256 of every other entry
 * course.json          course, version, modules, concepts, approved items of the version, item→concept links,
 *                      documents
 * pages.json           usable page text per document
 * chunks.json          passages with pages and embeddings (float32 LE, base64), so import needs no processing
 * documents/&lt;sha&gt;.pdf  source PDFs, named by the SHA-256 of their content
 * </pre>
 *
 * <p>Sizes and hashes in the manifest describe each entry's bytes as stored (after zip decompression, so
 * the ciphertext of a protected package). In a protected package every entry except the manifest is
 * encrypted with {@link PackageCrypto}'s segmented AES-256-GCM; JSON entries are zlib-compressed before
 * encryption. The manifest stays readable so a package can be inspected without its code.
 *
 * <p>Readers never extract entries by their zip names: they look up the fixed names above (a document only
 * by a validated hash), so an entry can never be written outside the profile folder. Every size is capped.
 */
public final class PackageFormat {

    /** The {@code format} value of a course manifest. */
    public static final String FORMAT = "learnsphere-course";
    /** The format version this release writes and the newest it reads. */
    public static final int VERSION = 1;

    /** The manifest entry. */
    public static final String MANIFEST = "manifest.json";
    /** Course, modules, concepts, items and documents. */
    public static final String COURSE = "course.json";
    /** Page text. */
    public static final String PAGES = "pages.json";
    /** Passages with embeddings. */
    public static final String CHUNKS = "chunks.json";

    private static final String DOCUMENTS_FOLDER = "documents/";
    private static final Pattern DOCUMENT = Pattern.compile("documents/([0-9a-f]{64})\\.pdf");
    private static final Pattern SHA256 = Pattern.compile("[0-9a-f]{64}");

    private static final long MIB = 1024L * 1024L;
    /** Largest manifest. */
    public static final long MAX_MANIFEST_BYTES = MIB;
    /** Largest {@code course.json}. */
    public static final long MAX_COURSE_BYTES = 64 * MIB;
    /** Largest {@code pages.json}. */
    public static final long MAX_PAGES_BYTES = 512 * MIB;
    /** Largest {@code chunks.json}. */
    public static final long MAX_CHUNKS_BYTES = 512 * MIB;
    /** Largest PDF, the same limit as importing a PDF directly. */
    public static final long MAX_DOCUMENT_BYTES = 300 * MIB;
    /** Most documents in one package. */
    public static final int MAX_DOCUMENTS = 1000;
    /** Most zip entries: the manifest, three JSON entries and the documents. */
    public static final int MAX_ENTRIES = MAX_DOCUMENTS + 4;

    private PackageFormat() {
    }

    /** The entry name of the PDF with this SHA-256. */
    public static String documentEntry(String sha256) {
        if (!isSha256(sha256)) {
            throw new IllegalArgumentException("Not a SHA-256 hex digest: " + sha256);
        }
        return DOCUMENTS_FOLDER + sha256 + ".pdf";
    }

    /** The SHA-256 of a document entry name, or empty for any other name. */
    public static Optional<String> documentSha(String entryName) {
        Matcher matcher = DOCUMENT.matcher(entryName);
        return matcher.matches() ? Optional.of(matcher.group(1)) : Optional.empty();
    }

    /** True for a lowercase hex SHA-256. */
    public static boolean isSha256(String value) {
        return value != null && SHA256.matcher(value).matches();
    }

    /** True for the entry names a manifest may list. */
    static boolean isKnownEntry(String name) {
        return COURSE.equals(name) || PAGES.equals(name) || CHUNKS.equals(name) || DOCUMENT.matcher(name).matches();
    }

    /** True for JSON entries (compressed before encryption in protected packages). */
    static boolean isJson(String name) {
        return COURSE.equals(name) || PAGES.equals(name) || CHUNKS.equals(name);
    }

    /** Largest readable size of an entry's content (before encryption). */
    static long plaintextLimit(String name) {
        return switch (name) {
            case COURSE -> MAX_COURSE_BYTES;
            case PAGES -> MAX_PAGES_BYTES;
            case CHUNKS -> MAX_CHUNKS_BYTES;
            default -> MAX_DOCUMENT_BYTES;
        };
    }

    /**
     * Largest stored size of an entry: its content limit plus room for the worst-case growth of compression
     * and the per-segment overhead of encryption.
     */
    static long storedLimit(String name, boolean encrypted) {
        long limit = plaintextLimit(name);
        if (!encrypted) {
            return limit;
        }
        long compressed = limit + limit / 1000 + 1024;
        long segments = compressed / PackageCrypto.SEGMENT_SIZE + 2;
        return PackageCrypto.PREFIX_BYTES + compressed + segments * PackageCrypto.TAG_BYTES;
    }
}
