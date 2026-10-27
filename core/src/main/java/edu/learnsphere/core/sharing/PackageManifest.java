package edu.learnsphere.core.sharing;

import com.fasterxml.jackson.annotation.JsonIgnore;
import java.util.List;
import java.util.Optional;

/**
 * {@code manifest.json} of a course package, readable without the code of a protected package.
 *
 * @param format        always {@link PackageFormat#FORMAT}
 * @param formatVersion the package format version
 * @param packageId     the course's stable id across copies
 * @param versionNumber the published version the package holds
 * @param createdAt     when the package was written
 * @param protection    how the entries are encrypted, or null for a shareable package
 * @param entries       size and SHA-256 of every entry except the manifest
 */
public record PackageManifest(String format, int formatVersion, String packageId, int versionNumber, String title,
                              String subtitle, String authorName, int coverHue, long createdAt, int moduleCount,
                              int itemCount, int documentCount, Protection protection, List<Entry> entries) {

    public PackageManifest {
        entries = entries == null ? List.of() : List.copyOf(entries);
    }

    /** True when the entries are encrypted and importing needs the creator's code. */
    @JsonIgnore
    public boolean isProtected() {
        return protection != null;
    }

    /** The entry with this name. */
    public Optional<Entry> entry(String path) {
        return entries.stream().filter(e -> e.path().equals(path)).findFirst();
    }

    /** A copy with the entries a writer recorded. */
    PackageManifest withEntries(Protection newProtection, List<Entry> newEntries) {
        return new PackageManifest(format, formatVersion, packageId, versionNumber, title, subtitle, authorName, coverHue,
                createdAt, moduleCount, itemCount, documentCount, newProtection, newEntries);
    }

    /**
     * One entry of the package.
     *
     * @param size   bytes as stored in the zip entry (after zip decompression)
     * @param sha256 lowercase hex SHA-256 of those bytes
     */
    public record Entry(String path, long size, String sha256) {
    }

    /**
     * How a protected package is encrypted.
     *
     * @param cipher      always {@value PackageCrypto#CIPHER_NAME}
     * @param kdf         always {@value PackageCrypto#KDF_NAME}
     * @param iterations  PBKDF2 iterations
     * @param salt        base64 PBKDF2 salt
     * @param keyCheck    base64 nonce and AES-GCM sealed constant that tells a wrong code from a damaged file
     * @param segmentSize plaintext bytes per encrypted segment
     */
    public record Protection(String cipher, String kdf, int iterations, String salt, String keyCheck, int segmentSize) {
    }
}
