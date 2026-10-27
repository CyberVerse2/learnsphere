package edu.learnsphere.core.sharing;

import edu.learnsphere.core.sharing.SharingQueries.ChunkKey;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * A SHA-256 over everything an imported document is made of (its details, page text and passages), so an
 * update re-imports and re-indexes only documents that changed. Fields are length-prefixed, so different
 * contents never share a fingerprint by concatenation.
 */
final class DocumentFingerprint {

    private final MessageDigest digest = Streams.sha256();

    /** Starts a fingerprint with a document's details. */
    static DocumentFingerprint of(String id, String moduleId, String title, String fileName, String sha256, long byteSize,
                                  int pageCount, long createdAt) {
        DocumentFingerprint f = new DocumentFingerprint();
        f.text(id).text(moduleId).text(title).text(fileName).text(sha256).number(byteSize).number(pageCount)
                .number(createdAt);
        return f;
    }

    DocumentFingerprint page(int pageNumber, String source, String text, String figureNotes, boolean hasImages) {
        return text("page").number(pageNumber).text(source).text(text).text(figureNotes).number(hasImages ? 1 : 0);
    }

    DocumentFingerprint chunk(ChunkKey c) {
        return text("chunk").text(c.id()).number(c.ordinal()).number(c.pageStart()).number(c.pageEnd())
                .text(c.sectionTitle()).text(c.text()).number(c.tokenEstimate()).number(c.searchable() ? 1 : 0);
    }

    String finish() {
        return Streams.hex(digest.digest());
    }

    private DocumentFingerprint text(String value) {
        if (value == null) {
            number(-1);
            return this;
        }
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        number(bytes.length);
        digest.update(bytes);
        return this;
    }

    private DocumentFingerprint number(long value) {
        for (int shift = 56; shift >= 0; shift -= 8) {
            digest.update((byte) (value >>> shift));
        }
        return this;
    }
}
