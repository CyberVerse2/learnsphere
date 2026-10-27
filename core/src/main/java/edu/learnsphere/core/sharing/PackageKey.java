package edu.learnsphere.core.sharing;

import edu.learnsphere.core.security.PasswordHasher;
import java.util.Arrays;
import java.util.Objects;
import javax.crypto.spec.SecretKeySpec;

/**
 * The AES-256 key of a protected package, derived from the creator's code with PBKDF2-HMAC-SHA256 and a
 * random salt. It lives in memory only; {@link #close()} overwrites it. Codes are compared after trimming
 * surrounding spaces, so "orchid " opens a package protected with "orchid".
 *
 * <p>Thread-safe for use after construction; do not use it after {@link #close()}.
 */
public final class PackageKey implements AutoCloseable {

    /** Shortest code a creator can protect a course with. */
    public static final int MIN_CODE_LENGTH = 4;

    private static final int KEY_BITS = 256;

    private final byte[] key;
    private final byte[] salt;
    private final int iterations;
    private volatile boolean destroyed;

    private PackageKey(byte[] key, byte[] salt, int iterations) {
        this.key = key;
        this.salt = salt.clone();
        this.iterations = iterations;
    }

    /**
     * A key for a new protected package: a fresh random salt and {@value PackageCrypto#ITERATIONS}
     * iterations. Does not wipe {@code code}.
     *
     * @throws IllegalArgumentException when the trimmed code is shorter than {@link #MIN_CODE_LENGTH}
     */
    public static PackageKey create(char[] code) {
        if (!isUsableCode(code)) {
            throw new IllegalArgumentException("Code too short");
        }
        return derive(code, PackageCrypto.randomBytes(PackageCrypto.SALT_BYTES), PackageCrypto.ITERATIONS);
    }

    /** True when {@code code} is long enough to protect a course. */
    public static boolean isUsableCode(char[] code) {
        return code != null && trimmedLength(code) >= MIN_CODE_LENGTH;
    }

    /** True when {@code code} is null or only spaces. */
    static boolean isBlank(char[] code) {
        return code == null || trimmedLength(code) == 0;
    }

    /** Derives the key for {@code code} (trimmed) with a package's salt and iterations. Does not wipe {@code code}. */
    static PackageKey derive(char[] code, byte[] salt, int iterations) {
        Objects.requireNonNull(code, "code");
        int start = 0;
        int end = code.length;
        while (start < end && Character.isWhitespace(code[start])) {
            start++;
        }
        while (end > start && Character.isWhitespace(code[end - 1])) {
            end--;
        }
        char[] trimmed = Arrays.copyOfRange(code, start, end);
        try {
            return new PackageKey(PasswordHasher.deriveKey(trimmed, salt, iterations, KEY_BITS), salt, iterations);
        } finally {
            PasswordHasher.wipe(trimmed);
        }
    }

    private static int trimmedLength(char[] code) {
        int start = 0;
        int end = code.length;
        while (start < end && Character.isWhitespace(code[start])) {
            start++;
        }
        while (end > start && Character.isWhitespace(code[end - 1])) {
            end--;
        }
        return end - start;
    }

    /** The AES key for a cipher. */
    SecretKeySpec secretKey() {
        if (destroyed) {
            throw new IllegalStateException("Package key was closed");
        }
        return new SecretKeySpec(key, "AES");
    }

    byte[] salt() {
        return salt.clone();
    }

    int iterations() {
        return iterations;
    }

    /** Overwrites the key. Idempotent. */
    @Override
    public void close() {
        destroyed = true;
        Arrays.fill(key, (byte) 0);
    }

    @Override
    public String toString() {
        return "PackageKey[" + (destroyed ? "closed" : "open") + "]";
    }
}
