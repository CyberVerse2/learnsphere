package edu.learnsphere.core.sharing;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PushbackInputStream;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import java.util.Objects;
import javax.crypto.AEADBadTagException;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;

/**
 * Encryption of protected packages: AES-256-GCM in independently authenticated segments, so PDFs of any
 * size stream through with bounded memory.
 *
 * <p>An encrypted entry is a random {@value #PREFIX_BYTES}-byte nonce prefix followed by segments of up to
 * {@value #SEGMENT_SIZE} plaintext bytes, each sealed with a 16-byte tag. Segment {@code i} uses the 12-byte
 * nonce {@code prefix || i (4 bytes, big-endian) || last (1 byte)} and the entry's associated data (package
 * id, version number and entry name). Because the counter and the last-segment flag are authenticated,
 * reordered, dropped, swapped or truncated segments fail to decrypt. Every entry has its own random prefix
 * and every package its own random PBKDF2 salt, so a nonce is never reused under one key.
 *
 * <p>A protected manifest carries a key check: a constant sealed with the key under its own random nonce,
 * which separates a wrong code from a damaged file.
 */
final class PackageCrypto {

    /** Cipher named in the manifest. */
    static final String CIPHER_NAME = "AES-256-GCM";
    /** Key derivation named in the manifest. */
    static final String KDF_NAME = "PBKDF2-HMAC-SHA256";
    /** PBKDF2 iterations for new packages. */
    static final int ITERATIONS = 310_000;
    /** Fewest iterations a package may declare. */
    static final int MIN_ITERATIONS = 100_000;
    /** Most iterations a package may declare (bounds the time a crafted file can cost). */
    static final int MAX_ITERATIONS = 5_000_000;
    /** Random salt bytes for new packages. */
    static final int SALT_BYTES = 16;
    /** Plaintext bytes per segment. */
    static final int SEGMENT_SIZE = 64 * 1024;
    /** GCM tag bytes per segment. */
    static final int TAG_BYTES = 16;
    /** Random nonce prefix bytes per entry. */
    static final int PREFIX_BYTES = 7;

    private static final int NONCE_BYTES = 12;
    private static final long MAX_SEGMENTS = 0xFFFF_FFFFL;
    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    private static final byte[] KEY_CHECK_PLAINTEXT = "learnsphere key check v1".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] KEY_CHECK_AAD = "learnsphere/key-check".getBytes(StandardCharsets.US_ASCII);
    private static final SecureRandom RANDOM = new SecureRandom();

    private PackageCrypto() {
    }

    static byte[] randomBytes(int count) {
        byte[] bytes = new byte[count];
        RANDOM.nextBytes(bytes);
        return bytes;
    }

    /** The associated data that binds an entry to its package, version and name. */
    static byte[] entryAad(String packageId, int versionNumber, String path) {
        return ("learnsphere/" + PackageFormat.VERSION + "\n" + packageId + "\n" + versionNumber + "\n" + path)
                .getBytes(StandardCharsets.UTF_8);
    }

    /** The base64 key check for a manifest. */
    static String keyCheck(PackageKey key) {
        byte[] nonce = randomBytes(NONCE_BYTES);
        try {
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, key.secretKey(), new GCMParameterSpec(TAG_BYTES * 8, nonce));
            cipher.updateAAD(KEY_CHECK_AAD);
            byte[] sealed = cipher.doFinal(KEY_CHECK_PLAINTEXT);
            byte[] out = Arrays.copyOf(nonce, NONCE_BYTES + sealed.length);
            System.arraycopy(sealed, 0, out, NONCE_BYTES, sealed.length);
            return Base64.getEncoder().encodeToString(out);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(TRANSFORMATION + " is not available", e);
        }
    }

    /** True when {@code key} opens {@code keyCheck}; false for a wrong key or a malformed check. */
    static boolean verifyKeyCheck(PackageKey key, String keyCheck) {
        byte[] bytes;
        try {
            bytes = Base64.getDecoder().decode(Objects.requireNonNullElse(keyCheck, ""));
        } catch (IllegalArgumentException e) {
            return false;
        }
        if (bytes.length < NONCE_BYTES + TAG_BYTES) {
            return false;
        }
        try {
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, key.secretKey(),
                    new GCMParameterSpec(TAG_BYTES * 8, bytes, 0, NONCE_BYTES));
            cipher.updateAAD(KEY_CHECK_AAD);
            byte[] plain = cipher.doFinal(bytes, NONCE_BYTES, bytes.length - NONCE_BYTES);
            return MessageDigest.isEqual(plain, KEY_CHECK_PLAINTEXT);
        } catch (AEADBadTagException e) {
            return false;
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(TRANSFORMATION + " is not available", e);
        }
    }

    /** True when {@code keyCheck} decodes to a plausible nonce and sealed constant. */
    static boolean isWellFormedKeyCheck(String keyCheck) {
        try {
            byte[] bytes = Base64.getDecoder().decode(Objects.requireNonNullElse(keyCheck, ""));
            return bytes.length == NONCE_BYTES + KEY_CHECK_PLAINTEXT.length + TAG_BYTES;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    /**
     * A stream that encrypts what is written to it into {@code out}. {@link OutputStream#close()} seals the
     * last segment and closes {@code out}.
     */
    static OutputStream encrypt(OutputStream out, PackageKey key, byte[] aad) throws IOException {
        return new EncryptingOutputStream(out, key, aad);
    }

    /**
     * A stream that decrypts {@code in}, verifying every segment. A segment that fails to authenticate, or an
     * entry that ends early, throws {@link DamagedPackageException}.
     */
    static InputStream decrypt(InputStream in, PackageKey key, byte[] aad) throws IOException {
        return new DecryptingInputStream(in, key, aad);
    }

    private static byte[] nonce(byte[] prefix, long counter, boolean last) {
        byte[] nonce = Arrays.copyOf(prefix, NONCE_BYTES);
        nonce[PREFIX_BYTES] = (byte) (counter >>> 24);
        nonce[PREFIX_BYTES + 1] = (byte) (counter >>> 16);
        nonce[PREFIX_BYTES + 2] = (byte) (counter >>> 8);
        nonce[PREFIX_BYTES + 3] = (byte) counter;
        nonce[PREFIX_BYTES + 4] = (byte) (last ? 1 : 0);
        return nonce;
    }

    private static Cipher newCipher() throws IOException {
        try {
            return Cipher.getInstance(TRANSFORMATION);
        } catch (GeneralSecurityException e) {
            throw new IOException(TRANSFORMATION + " is not available", e);
        }
    }

    private static final class EncryptingOutputStream extends OutputStream {

        private final OutputStream out;
        private final PackageKey key;
        private final byte[] aad;
        private final byte[] prefix = randomBytes(PREFIX_BYTES);
        private final byte[] buffer = new byte[SEGMENT_SIZE];
        private final byte[] sealed = new byte[SEGMENT_SIZE + TAG_BYTES];
        private final Cipher cipher;
        private int filled;
        private long counter;
        private boolean closed;

        EncryptingOutputStream(OutputStream out, PackageKey key, byte[] aad) throws IOException {
            this.out = Objects.requireNonNull(out, "out");
            this.key = Objects.requireNonNull(key, "key");
            this.aad = aad.clone();
            this.cipher = newCipher();
            out.write(prefix);
        }

        @Override
        public void write(int b) throws IOException {
            write(new byte[] {(byte) b}, 0, 1);
        }

        @Override
        public void write(byte[] b, int off, int len) throws IOException {
            Objects.checkFromIndexSize(off, len, b.length);
            if (closed) {
                throw new IOException("Stream closed");
            }
            while (len > 0) {
                if (filled == SEGMENT_SIZE) {
                    seal(false);
                }
                int n = Math.min(len, SEGMENT_SIZE - filled);
                System.arraycopy(b, off, buffer, filled, n);
                filled += n;
                off += n;
                len -= n;
            }
        }

        @Override
        public void flush() throws IOException {
            out.flush();
        }

        @Override
        public void close() throws IOException {
            if (closed) {
                return;
            }
            closed = true;
            try {
                seal(true);
                out.flush();
            } finally {
                Arrays.fill(buffer, (byte) 0);
                out.close();
            }
        }

        private void seal(boolean last) throws IOException {
            if (counter > MAX_SEGMENTS) {
                throw new IOException("Entry too large to encrypt");
            }
            try {
                cipher.init(Cipher.ENCRYPT_MODE, key.secretKey(), new GCMParameterSpec(TAG_BYTES * 8, nonce(prefix, counter, last)));
                cipher.updateAAD(aad);
                int n = cipher.doFinal(buffer, 0, filled, sealed, 0);
                out.write(sealed, 0, n);
            } catch (GeneralSecurityException e) {
                throw new IOException("Encryption failed", e);
            }
            counter++;
            filled = 0;
        }
    }

    private static final class DecryptingInputStream extends InputStream {

        private final PushbackInputStream in;
        private final PackageKey key;
        private final byte[] aad;
        private final byte[] prefix;
        private final byte[] sealed = new byte[SEGMENT_SIZE + TAG_BYTES];
        private final byte[] plain = new byte[SEGMENT_SIZE];
        private final Cipher cipher;
        private int position;
        private int limit;
        private long counter;
        private boolean finished;

        DecryptingInputStream(InputStream in, PackageKey key, byte[] aad) throws IOException {
            this.in = new PushbackInputStream(Objects.requireNonNull(in, "in"), 1);
            this.key = Objects.requireNonNull(key, "key");
            this.aad = aad.clone();
            this.cipher = newCipher();
            this.prefix = this.in.readNBytes(PREFIX_BYTES);
            if (prefix.length != PREFIX_BYTES) {
                throw new DamagedPackageException("Encrypted entry ends before its nonce");
            }
        }

        @Override
        public int read() throws IOException {
            byte[] one = new byte[1];
            int n = read(one, 0, 1);
            return n == -1 ? -1 : one[0] & 0xFF;
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            Objects.checkFromIndexSize(off, len, b.length);
            if (len == 0) {
                return 0;
            }
            while (position == limit) {
                if (finished) {
                    return -1;
                }
                nextSegment();
            }
            int n = Math.min(len, limit - position);
            System.arraycopy(plain, position, b, off, n);
            position += n;
            return n;
        }

        @Override
        public int available() {
            return limit - position;
        }

        private void nextSegment() throws IOException {
            int n = in.readNBytes(sealed, 0, sealed.length);
            int next = in.read();
            boolean last = next == -1;
            if (!last) {
                in.unread(next);
            }
            if (n < TAG_BYTES) {
                throw new DamagedPackageException("Encrypted entry ends inside a segment");
            }
            if (counter > MAX_SEGMENTS) {
                throw new DamagedPackageException("Too many segments");
            }
            try {
                cipher.init(Cipher.DECRYPT_MODE, key.secretKey(), new GCMParameterSpec(TAG_BYTES * 8, nonce(prefix, counter, last)));
                cipher.updateAAD(aad);
                limit = cipher.doFinal(sealed, 0, n, plain, 0);
            } catch (AEADBadTagException e) {
                throw new DamagedPackageException("Segment " + counter + " failed authentication", e);
            } catch (GeneralSecurityException e) {
                throw new IOException("Decryption failed", e);
            }
            position = 0;
            counter++;
            finished = last;
        }

        @Override
        public void close() throws IOException {
            Arrays.fill(plain, (byte) 0);
            in.close();
        }
    }
}
