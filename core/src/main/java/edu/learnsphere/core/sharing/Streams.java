package edu.learnsphere.core.sharing;

import java.io.FilterInputStream;
import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** Stream helpers for reading and writing packages: hashing, counting, size limits. */
final class Streams {

    private static final int BUFFER = 64 * 1024;

    private Streams() {
    }

    static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }

    static String hex(byte[] bytes) {
        return HexFormat.of().formatHex(bytes);
    }

    /** Reads {@code in} to its end, discarding the bytes. */
    static void drain(InputStream in) throws IOException {
        byte[] buffer = new byte[BUFFER];
        while (in.read(buffer) != -1) {
            // discard
        }
    }

    /** {@code out} with {@code close()} reduced to {@code flush()}, for writing into a zip entry. */
    static OutputStream nonClosing(OutputStream out) {
        return new FilterOutputStream(out) {
            @Override
            public void write(byte[] b, int off, int len) throws IOException {
                out.write(b, off, len);
            }

            @Override
            public void close() throws IOException {
                out.flush();
            }
        };
    }

    /** {@code in} with {@code close()} doing nothing, so a parser cannot close a stream still being verified. */
    static InputStream nonClosing(InputStream in) {
        return new FilterInputStream(in) {
            @Override
            public void close() {
                // the owner closes it
            }
        };
    }

    /**
     * Counts and hashes the bytes read, and checks them against the manifest when the stream ends: more
     * bytes than declared, fewer, or another hash throw {@link DamagedPackageException}.
     */
    static final class VerifyingInputStream extends FilterInputStream {

        private final String name;
        private final long expectedSize;
        private final String expectedSha256;
        private final MessageDigest digest = sha256();
        private long count;
        private boolean verified;

        VerifyingInputStream(InputStream in, String name, long expectedSize, String expectedSha256) {
            super(in);
            this.name = name;
            this.expectedSize = expectedSize;
            this.expectedSha256 = expectedSha256;
        }

        @Override
        public int read() throws IOException {
            int b = in.read();
            if (b == -1) {
                verifyAtEnd();
                return -1;
            }
            counted(1);
            digest.update((byte) b);
            return b;
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            int n = in.read(b, off, len);
            if (n == -1) {
                verifyAtEnd();
                return -1;
            }
            counted(n);
            digest.update(b, off, n);
            return n;
        }

        @Override
        public long skip(long n) throws IOException {
            byte[] buffer = new byte[(int) Math.min(BUFFER, Math.max(n, 0))];
            long skipped = 0;
            while (skipped < n) {
                int read = read(buffer, 0, (int) Math.min(buffer.length, n - skipped));
                if (read == -1) {
                    break;
                }
                skipped += read;
            }
            return skipped;
        }

        @Override
        public boolean markSupported() {
            return false;
        }

        /** Bytes read so far. */
        long count() {
            return count;
        }

        /** True once the whole entry was read and matched the manifest. */
        boolean isVerified() {
            return verified;
        }

        private void counted(int n) throws DamagedPackageException {
            count += n;
            if (count > expectedSize) {
                throw new DamagedPackageException(name + " is longer than its manifest says");
            }
        }

        private void verifyAtEnd() throws DamagedPackageException {
            if (verified) {
                return;
            }
            if (count != expectedSize) {
                throw new DamagedPackageException(name + " has " + count + " bytes, manifest says " + expectedSize);
            }
            String actual = hex(digest.digest());
            if (!MessageDigest.isEqual(actual.getBytes(java.nio.charset.StandardCharsets.US_ASCII),
                    expectedSha256.getBytes(java.nio.charset.StandardCharsets.US_ASCII))) {
                throw new DamagedPackageException(name + " does not match its SHA-256");
            }
            verified = true;
        }
    }

    /** Fails with {@link DamagedPackageException} once more than {@code limit} bytes are read. */
    static final class LimitedInputStream extends FilterInputStream {

        private final String name;
        private final long limit;
        private long count;

        LimitedInputStream(InputStream in, String name, long limit) {
            super(in);
            this.name = name;
            this.limit = limit;
        }

        @Override
        public int read() throws IOException {
            int b = in.read();
            if (b != -1) {
                counted(1);
            }
            return b;
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            int n = in.read(b, off, len);
            if (n > 0) {
                counted(n);
            }
            return n;
        }

        @Override
        public boolean markSupported() {
            return false;
        }

        private void counted(int n) throws DamagedPackageException {
            count += n;
            if (count > limit) {
                throw new DamagedPackageException(name + " is larger than " + limit + " bytes");
            }
        }
    }

    /** Counts and hashes the bytes written, as the manifest records them. */
    static final class DigestingOutputStream extends FilterOutputStream {

        private final MessageDigest digest = sha256();
        private long count;

        DigestingOutputStream(OutputStream out) {
            super(out);
        }

        @Override
        public void write(int b) throws IOException {
            out.write(b);
            digest.update((byte) b);
            count++;
        }

        @Override
        public void write(byte[] b, int off, int len) throws IOException {
            out.write(b, off, len);
            digest.update(b, off, len);
            count += len;
        }

        long count() {
            return count;
        }

        /** The hex SHA-256 of everything written; call once, after the last write. */
        String sha256Hex() {
            return hex(digest.digest());
        }
    }
}
