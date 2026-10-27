package edu.learnsphere.core.security;

import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import java.util.Objects;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;

/**
 * PBKDF2-HMAC-SHA256 password hashing with a random 16-byte salt and {@value #ITERATIONS} iterations.
 * Hashes are encoded as {@code pbkdf2-sha256$<iterations>$<base64 salt>$<base64 hash>}, so the iteration
 * count can be raised later without breaking stored hashes. Verification compares in constant time.
 *
 * <p>Thread-safe. Hashing takes a few hundred milliseconds by design; call it off the UI thread.
 */
public final class PasswordHasher {

    /** Iterations for new hashes (OWASP recommendation for PBKDF2-HMAC-SHA256). */
    public static final int ITERATIONS = 310_000;

    private static final String ALGORITHM = "PBKDF2WithHmacSHA256";
    private static final String PREFIX = "pbkdf2-sha256";
    private static final int SALT_BYTES = 16;
    private static final int KEY_BITS = 256;

    private final int iterations;
    private final SecureRandom random = new SecureRandom();

    public PasswordHasher() {
        this(ITERATIONS);
    }

    /** A hasher with another iteration count, for tests that need speed. */
    public PasswordHasher(int iterations) {
        if (iterations < 1) {
            throw new IllegalArgumentException("iterations must be >= 1");
        }
        this.iterations = iterations;
    }

    /** Hashes {@code password} with a new random salt. The caller may wipe the array afterwards. */
    public String hash(char[] password) {
        Objects.requireNonNull(password, "password");
        byte[] salt = new byte[SALT_BYTES];
        random.nextBytes(salt);
        byte[] hash = deriveKey(password, salt, iterations, KEY_BITS);
        Base64.Encoder b64 = Base64.getEncoder().withoutPadding();
        return PREFIX + "$" + iterations + "$" + b64.encodeToString(salt) + "$" + b64.encodeToString(hash);
    }

    /** True when {@code password} matches {@code encoded}. Malformed hashes never match. */
    public boolean verify(char[] password, String encoded) {
        if (password == null || encoded == null) {
            return false;
        }
        String[] parts = encoded.split("\\$");
        if (parts.length != 4 || !PREFIX.equals(parts[0])) {
            return false;
        }
        try {
            int storedIterations = Integer.parseInt(parts[1]);
            byte[] salt = Base64.getDecoder().decode(parts[2]);
            byte[] expected = Base64.getDecoder().decode(parts[3]);
            if (storedIterations < 1 || expected.length == 0) {
                return false;
            }
            byte[] actual = deriveKey(password, salt, storedIterations, expected.length * 8);
            return MessageDigest.isEqual(expected, actual);
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    /** True when {@code encoded} was made with fewer iterations than this hasher uses. */
    public boolean needsRehash(String encoded) {
        String[] parts = encoded == null ? new String[0] : encoded.split("\\$");
        if (parts.length != 4 || !PREFIX.equals(parts[0])) {
            return true;
        }
        try {
            return Integer.parseInt(parts[1]) < iterations;
        } catch (NumberFormatException e) {
            return true;
        }
    }

    /**
     * Derives a key from a secret with PBKDF2-HMAC-SHA256. Also used to derive the AES-256 key that
     * protects shared course packages ({@code keyBits = 256}).
     */
    public static byte[] deriveKey(char[] secret, byte[] salt, int iterations, int keyBits) {
        PBEKeySpec spec = new PBEKeySpec(secret, salt, iterations, keyBits);
        try {
            return SecretKeyFactory.getInstance(ALGORITHM).generateSecret(spec).getEncoded();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(ALGORITHM + " is not available", e);
        } finally {
            spec.clearPassword();
        }
    }

    /** Overwrites a password array so it does not linger in memory. */
    public static void wipe(char[] password) {
        if (password != null) {
            Arrays.fill(password, '\0');
        }
    }
}
