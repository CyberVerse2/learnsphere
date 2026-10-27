package edu.learnsphere.core.service.impl;

import edu.learnsphere.core.AppPaths;
import edu.learnsphere.core.db.DocumentRepository;
import edu.learnsphere.core.error.LearnSphereException;
import edu.learnsphere.core.error.UserMessage;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The imported PDFs of one profile, content-addressed as {@code files/<sha256>.pdf}, and their cached page
 * images under {@code renders/}. One instance per open profile is shared by every service that adds or
 * removes documents, because a stored file is shared by every document with the same content.
 *
 * <p>Adding works in two steps: {@link #stage} copies the source next to the stored files while hashing it
 * (slow, no lock held), then {@link #commit} moves the copy into place and runs the caller's database writes
 * under the store's lock. {@link #delete} runs the caller's database deletes under the same lock and then
 * removes the files no document uses any more. So a file is never deleted while a document that needs it is
 * being added.
 *
 * <p>Thread-safe.
 */
public final class PdfStore {

    private static final Logger LOG = LoggerFactory.getLogger(PdfStore.class);

    /** Free space kept on top of a file's size before copying it. */
    static final long SPARE_BYTES = 64L * 1024 * 1024;

    static final UserMessage COPY_FAILED = UserMessage.of("This PDF couldn't be added. Try again.");

    private final AppPaths.ProfilePaths paths;
    private final DocumentRepository documents;
    private final ReentrantLock lock = new ReentrantLock();

    public PdfStore(AppPaths.ProfilePaths paths, DocumentRepository documents) {
        this.paths = Objects.requireNonNull(paths, "paths");
        this.documents = Objects.requireNonNull(documents, "documents");
    }

    /**
     * A copy of a PDF next to the stored files, with its hash and size. Closing deletes the copy when
     * {@link #commit} did not move it into place.
     */
    public static final class Staged implements AutoCloseable {

        private final Path copy;
        private final String sha256;
        private final long byteSize;

        private Staged(Path copy, String sha256, long byteSize) {
            this.copy = copy;
            this.sha256 = sha256;
            this.byteSize = byteSize;
        }

        /** Lowercase hex SHA-256 of the content. */
        public String sha256() {
            return sha256;
        }

        /** Size in bytes. */
        public long byteSize() {
            return byteSize;
        }

        @Override
        public void close() {
            try {
                Files.deleteIfExists(copy);
            } catch (IOException e) {
                LOG.debug("Could not delete {}", copy, e);
            }
        }
    }

    /**
     * Copies {@code source} into the files folder under a temporary name, hashing it on the way.
     *
     * @throws LearnSphereException with {@link UserMessage#DISK_FULL} when there is not enough free space,
     *                              or a general message when the copy fails
     */
    public Staged stage(Path source) {
        Path folder = paths.filesDir();
        Path copy = null;
        try {
            long size = Files.size(source);
            requireSpace(folder, size);
            copy = Files.createTempFile(folder, ".import-", ".partial");
            MessageDigest digest = sha256Digest();
            long copied;
            try (InputStream in = new DigestInputStream(Files.newInputStream(source), digest);
                 OutputStream out = Files.newOutputStream(copy)) {
                copied = in.transferTo(out);
            }
            return new Staged(copy, hex(digest.digest()), copied);
        } catch (IOException | UncheckedIOException e) {
            deleteQuietly(copy);
            UserMessage message = hasLittleSpace(folder) ? UserMessage.DISK_FULL : COPY_FAILED;
            throw new LearnSphereException(message, "Could not copy " + source + " into " + folder, e);
        }
    }

    /**
     * Moves the staged copy into place as {@code files/<sha256>.pdf} (or drops it when that file is already
     * stored), then runs {@code write} while holding the store's lock. When {@code write} throws, a file this
     * call stored is removed again unless a document uses it.
     */
    public <T> T commit(Staged staged, Supplier<T> write) {
        Objects.requireNonNull(staged, "staged");
        Objects.requireNonNull(write, "write");
        lock.lock();
        try {
            Path target = file(staged.sha256);
            boolean placed = false;
            try {
                if (!Files.exists(target)) {
                    move(staged.copy, target);
                    placed = true;
                }
            } catch (IOException | UncheckedIOException e) {
                throw new LearnSphereException(COPY_FAILED, "Could not store " + target, e);
            }
            try {
                return write.get();
            } catch (RuntimeException | Error e) {
                if (placed) {
                    deleteUnused(Set.of(staged.sha256));
                }
                throw e;
            }
        } finally {
            lock.unlock();
            staged.close();
        }
    }

    /**
     * Runs {@code deleteRows} under the store's lock, then deletes the stored files among {@code sha256s}
     * that no document uses any more and the cached page images of {@code documentIds}. File deletion
     * problems are logged, never thrown: the rows are gone either way.
     */
    public <T> T delete(Collection<String> sha256s, Collection<String> documentIds, Supplier<T> deleteRows) {
        Objects.requireNonNull(deleteRows, "deleteRows");
        Set<String> hashes = new LinkedHashSet<>(sha256s);
        lock.lock();
        try {
            T result = deleteRows.get();
            deleteUnused(hashes);
            return result;
        } finally {
            lock.unlock();
            documentIds.forEach(this::deleteRenders);
        }
    }

    /** The stored copy of the PDF with this hash (it may not exist). */
    public Path file(String sha256) {
        return paths.pdfFile(sha256);
    }

    /** True when the PDF with this hash is stored. */
    public boolean contains(String sha256) {
        return Files.isRegularFile(file(sha256));
    }

    /** The cached image of a page at a scale. */
    public Path renderFile(String documentId, int pageNumber, float scale) {
        return paths.renderFile(documentId, pageNumber, scale);
    }

    /** Deletes the cached page images of a document. */
    public void deleteRenders(String documentId) {
        Path folder = paths.rendersDir().resolve(documentId);
        if (!Files.isDirectory(folder)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(folder)) {
            for (Path path : walk.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        } catch (IOException | UncheckedIOException e) {
            LOG.warn("Could not delete the page images of document {}", documentId, e);
        }
    }

    /** The SHA-256 of a file as lowercase hex, without copying it. */
    public static String sha256(Path file) throws IOException {
        MessageDigest digest = sha256Digest();
        try (InputStream in = new DigestInputStream(Files.newInputStream(file), digest)) {
            in.transferTo(OutputStream.nullOutputStream());
        }
        return hex(digest.digest());
    }

    private void deleteUnused(Set<String> hashes) {
        for (String sha : hashes) {
            if (documents.countBySha(sha) == 0) {
                deleteQuietly(file(sha));
            }
        }
    }

    private static void requireSpace(Path folder, long size) throws IOException {
        long usable = Files.getFileStore(folder).getUsableSpace();
        if (usable < size + SPARE_BYTES) {
            throw new LearnSphereException(UserMessage.DISK_FULL,
                    "Only " + usable + " bytes free for a " + size + " byte PDF in " + folder);
        }
    }

    private static boolean hasLittleSpace(Path folder) {
        try {
            return Files.getFileStore(folder).getUsableSpace() < SPARE_BYTES;
        } catch (IOException e) {
            return false;
        }
    }

    private static void move(Path from, Path to) throws IOException {
        try {
            Files.move(from, to, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(from, to);
        } catch (FileAlreadyExistsException e) {
            // Another copy of the same content got there first; both are identical.
            Files.deleteIfExists(from);
        }
    }

    private static MessageDigest sha256Digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }

    private static String hex(byte[] bytes) {
        return java.util.HexFormat.of().formatHex(bytes);
    }

    private static void deleteQuietly(Path file) {
        if (file == null) {
            return;
        }
        try {
            Files.deleteIfExists(file);
        } catch (IOException e) {
            LOG.warn("Could not delete {}", file, e);
        }
    }
}
