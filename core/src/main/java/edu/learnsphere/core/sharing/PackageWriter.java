package edu.learnsphere.core.sharing;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.ObjectWriter;
import com.fasterxml.jackson.databind.SerializationFeature;
import edu.learnsphere.core.sharing.PackageManifest.Entry;
import edu.learnsphere.core.sharing.PackageManifest.Protection;
import edu.learnsphere.core.util.CancellationToken;
import edu.learnsphere.core.util.Json;
import java.io.BufferedOutputStream;
import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.ToLongFunction;
import java.util.zip.Deflater;
import java.util.zip.DeflaterOutputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Writes a course package into a temporary file next to the target and moves it into place on
 * {@link #commit}, so a failed export never leaves a partial file under the chosen name. Records the size
 * and SHA-256 of each entry as stored; the manifest is written last. An entry whose content goes past
 * {@link PackageFormat}'s limit for it fails with {@link TooLargeException}, so no package is written that
 * an import would refuse.
 *
 * <p>Not thread-safe.
 */
final class PackageWriter implements AutoCloseable {

    /** Writes the body of one JSON entry with a streaming generator. */
    @FunctionalInterface
    interface JsonBody {
        void write(JsonGenerator generator, ObjectWriter values) throws IOException;
    }

    @FunctionalInterface
    private interface EntryBody {
        void write(OutputStream out) throws IOException;
    }

    private static final ObjectWriter VALUES = Json.mapper().writer()
            .without(SerializationFeature.FLUSH_AFTER_WRITE_VALUE);
    private static final int BUFFER = 64 * 1024;

    private final Path target;
    private final Path temp;
    private final ZipOutputStream zip;
    private final PackageKey key;
    private final String packageId;
    private final int versionNumber;
    private final ToLongFunction<String> limits;
    private final List<Entry> entries = new ArrayList<>();
    private final Set<String> names = new HashSet<>();
    private boolean committed;
    private boolean closed;

    private PackageWriter(Path target, Path temp, ZipOutputStream zip, PackageKey key, String packageId, int versionNumber,
                          ToLongFunction<String> limits) {
        this.target = target;
        this.temp = temp;
        this.zip = zip;
        this.key = key;
        this.packageId = packageId;
        this.versionNumber = versionNumber;
        this.limits = limits;
    }

    /**
     * Starts a package for {@code target}.
     *
     * @param key the key of a protected package, or null
     */
    static PackageWriter create(Path target, String packageId, int versionNumber, PackageKey key) throws IOException {
        return create(target, packageId, versionNumber, key, PackageFormat::plaintextLimit);
    }

    /**
     * Starts a package for {@code target} with other content limits per entry name (tests use small ones).
     *
     * @param key the key of a protected package, or null
     */
    static PackageWriter create(Path target, String packageId, int versionNumber, PackageKey key,
                                ToLongFunction<String> limits) throws IOException {
        Objects.requireNonNull(limits, "limits");
        Path absolute = target.toAbsolutePath();
        Path folder = Files.createDirectories(absolute.getParent());
        Path temp = Files.createTempFile(folder, ".learnsphere-", ".partial");
        try {
            ZipOutputStream zip = new ZipOutputStream(new BufferedOutputStream(Files.newOutputStream(temp), BUFFER),
                    StandardCharsets.UTF_8);
            return new PackageWriter(absolute, temp, zip, key, packageId, versionNumber, limits);
        } catch (IOException | RuntimeException e) {
            Files.deleteIfExists(temp);
            throw e;
        }
    }

    /** Writes {@code value} as a JSON entry. */
    void writeJson(String path, Object value) throws IOException {
        writeEntry(path, out -> VALUES.writeValue(out, value));
    }

    /** Writes a JSON entry with a streaming generator, for entries too large to build in memory first. */
    void writeJson(String path, JsonBody body) throws IOException {
        writeEntry(path, out -> {
            try (JsonGenerator generator = Json.mapper().getFactory().createGenerator(out)) {
                body.write(generator, VALUES);
            }
        });
    }

    /**
     * Streams a PDF into {@code documents/<sha256>.pdf}, checking that its content still has that hash.
     *
     * @throws java.util.concurrent.CancellationException when {@code token} is cancelled
     * @throws ChangedFileException when the file's content no longer matches {@code sha256}
     */
    void writeDocument(String sha256, Path source, CancellationToken token) throws IOException {
        String path = PackageFormat.documentEntry(sha256);
        MessageDigest digest = Streams.sha256();
        writeEntry(path, out -> {
            try (InputStream in = Files.newInputStream(source)) {
                byte[] buffer = new byte[BUFFER];
                int n;
                while ((n = in.read(buffer)) != -1) {
                    digest.update(buffer, 0, n);
                    out.write(buffer, 0, n);
                    token.throwIfCancelled();
                }
            }
        });
        if (!Streams.hex(digest.digest()).equals(sha256)) {
            throw new ChangedFileException(source + " does not match " + sha256);
        }
    }

    private void writeEntry(String path, EntryBody body) throws IOException {
        if (closed || committed) {
            throw new IllegalStateException("Package already finished");
        }
        if (!PackageFormat.isKnownEntry(path) || !names.add(path)) {
            throw new IllegalArgumentException("Unexpected or repeated entry " + path);
        }
        boolean json = PackageFormat.isJson(path);
        zip.setLevel(json && key == null ? Deflater.DEFAULT_COMPRESSION : Deflater.NO_COMPRESSION);
        zip.putNextEntry(new ZipEntry(path));
        Streams.DigestingOutputStream stored = new Streams.DigestingOutputStream(Streams.nonClosing(zip));
        Deflater deflater = null;
        try {
            OutputStream content = stored;
            if (key != null) {
                content = PackageCrypto.encrypt(stored, key, PackageCrypto.entryAad(packageId, versionNumber, path));
                if (json) {
                    deflater = new Deflater(Deflater.DEFAULT_COMPRESSION);
                    content = new DeflaterOutputStream(content, deflater, BUFFER);
                }
            }
            try (OutputStream out = content) {
                body.write(new LimitedOutputStream(Streams.nonClosing(out), path, limits.applyAsLong(path)));
            }
        } finally {
            if (deflater != null) {
                deflater.end();
            }
        }
        zip.closeEntry();
        entries.add(new Entry(path, stored.count(), stored.sha256Hex()));
    }

    /**
     * Writes the manifest (with the protection and the entries recorded so far), closes the zip and moves
     * it to the target, replacing an existing file.
     *
     * @return the manifest as written
     */
    PackageManifest commit(PackageManifest header) throws IOException {
        Objects.requireNonNull(header, "header");
        Protection protection = key == null ? null : new Protection(PackageCrypto.CIPHER_NAME, PackageCrypto.KDF_NAME,
                key.iterations(), Base64.getEncoder().encodeToString(key.salt()), PackageCrypto.keyCheck(key),
                PackageCrypto.SEGMENT_SIZE);
        PackageManifest manifest = header.withEntries(protection, entries);
        zip.setLevel(Deflater.DEFAULT_COMPRESSION);
        zip.putNextEntry(new ZipEntry(PackageFormat.MANIFEST));
        zip.write(Json.writePretty(manifest).getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
        zip.close();
        closed = true;
        try {
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
        }
        committed = true;
        return manifest;
    }

    /** Discards the package unless it was committed. Idempotent. */
    @Override
    public void close() {
        if (!closed) {
            closed = true;
            try {
                zip.close();
            } catch (IOException e) {
                // discarding anyway
            }
        }
        if (!committed) {
            try {
                Files.deleteIfExists(temp);
            } catch (IOException e) {
                // a stray temporary file is harmless
            }
        }
    }

    /** An entry whose content went past its limit. */
    static final class TooLargeException extends IOException {

        private static final long serialVersionUID = 1L;

        TooLargeException(String detail) {
            super(detail);
        }

        /** True when {@code error} or one of its causes is a {@code TooLargeException}. */
        static boolean isCause(Throwable error) {
            for (Throwable t = error; t != null; t = t.getCause()) {
                if (t instanceof TooLargeException) {
                    return true;
                }
            }
            return false;
        }
    }

    /** Counts the content written to an entry and fails once it passes the entry's limit. */
    private static final class LimitedOutputStream extends FilterOutputStream {

        private final String name;
        private final long limit;
        private long count;

        LimitedOutputStream(OutputStream out, String name, long limit) {
            super(out);
            this.name = name;
            this.limit = limit;
        }

        @Override
        public void write(int b) throws IOException {
            counted(1);
            out.write(b);
        }

        @Override
        public void write(byte[] b, int off, int len) throws IOException {
            counted(len);
            out.write(b, off, len);
        }

        private void counted(int n) throws TooLargeException {
            count += n;
            if (count > limit) {
                throw new TooLargeException(name + " is larger than " + limit + " bytes");
            }
        }
    }

    /** A PDF in the profile whose content no longer matches its name. */
    static final class ChangedFileException extends IOException {

        private static final long serialVersionUID = 1L;

        ChangedFileException(String detail) {
            super(detail);
        }
    }
}
