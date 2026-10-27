package edu.learnsphere.core.sharing;

import com.fasterxml.jackson.core.JacksonException;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectReader;
import edu.learnsphere.core.error.InvalidInputException;
import edu.learnsphere.core.error.LearnSphereException;
import edu.learnsphere.core.sharing.PackageData.ChunksFile;
import edu.learnsphere.core.sharing.PackageData.CourseFile;
import edu.learnsphere.core.sharing.PackageData.PagesFile;
import edu.learnsphere.core.sharing.PackageManifest.Entry;
import edu.learnsphere.core.sharing.PackageManifest.Protection;
import edu.learnsphere.core.util.CancellationToken;
import edu.learnsphere.core.util.Ids;
import edu.learnsphere.core.util.Json;
import java.io.BufferedOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import java.util.function.LongConsumer;
import java.util.zip.Inflater;
import java.util.zip.InflaterInputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipException;
import java.util.zip.ZipFile;

/**
 * Reads a course package. {@link #open} checks the zip and the manifest (format, version, entry names,
 * sizes, protection parameters) without reading the entries; the other methods read one entry each and
 * verify its size and SHA-256 (and, for a protected package, every segment's tag) before the result is
 * returned or the copied file is moved into place.
 *
 * <p>Entries are only ever looked up by fixed names or a validated document hash, never extracted by the
 * names inside the zip. Not thread-safe; use one reader per thread.
 */
public final class PackageReader implements AutoCloseable {

    private static final ObjectReader JSON = Json.mapper().reader().without(StreamReadFeature.AUTO_CLOSE_SOURCE);
    private static final int BUFFER = 64 * 1024;
    private static final int MIN_SALT_BYTES = 16;
    private static final int MAX_SALT_BYTES = 64;

    private final Path file;
    private final ZipFile zip;
    private final PackageManifest manifest;
    private final long byteSize;

    private PackageReader(Path file, ZipFile zip, PackageManifest manifest, long byteSize) {
        this.file = file;
        this.zip = zip;
        this.manifest = manifest;
        this.byteSize = byteSize;
    }

    /**
     * Opens a package and checks its manifest.
     *
     * @throws InvalidInputException {@link SharingMessages#FILE_NOT_OPENED} when the file cannot be read,
     *                               {@link SharingMessages#NOT_A_COURSE_FILE} when it is no course package,
     *                               {@link SharingMessages#COURSE_FILE_TOO_NEW} for a newer format, and
     *                               {@link SharingMessages#COURSE_FILE_DAMAGED} when the manifest and the
     *                               zip disagree
     */
    public static PackageReader open(Path file) {
        Objects.requireNonNull(file, "file");
        long size;
        ZipFile zip;
        try {
            if (!Files.isRegularFile(file)) {
                throw new InvalidInputException(SharingMessages.FILE_NOT_OPENED, "Not a regular file: " + file);
            }
            size = Files.size(file);
            zip = new ZipFile(file.toFile());
        } catch (ZipException e) {
            if (looksLikeZip(file)) {
                throw PackageContent.damaged("Unreadable zip " + file, e);
            }
            throw new InvalidInputException(SharingMessages.NOT_A_COURSE_FILE, "Not a zip file: " + file, e);
        } catch (IOException e) {
            throw new InvalidInputException(SharingMessages.FILE_NOT_OPENED, "Cannot read " + file, e);
        }
        try {
            PackageManifest manifest = readManifest(zip);
            checkEntries(zip, manifest);
            return new PackageReader(file, zip, manifest, size);
        } catch (RuntimeException e) {
            closeQuietly(zip);
            throw e;
        }
    }

    /** True when the file starts like a zip archive, so a failure to open it means it is damaged or cut short. */
    private static boolean looksLikeZip(Path file) {
        try (InputStream in = Files.newInputStream(file)) {
            byte[] magic = in.readNBytes(4);
            return magic.length == 4 && magic[0] == 'P' && magic[1] == 'K' && magic[2] == 3 && magic[3] == 4;
        } catch (IOException e) {
            return false;
        }
    }

    public PackageManifest manifest() {
        return manifest;
    }

    /** Size of the package file in bytes. */
    public long byteSize() {
        return byteSize;
    }

    public Path file() {
        return file;
    }

    /**
     * The key of a protected package, or null for a shareable one. Does not wipe {@code code}.
     *
     * @throws InvalidInputException {@link SharingMessages#CODE_REQUIRED} without a code,
     *                               {@link SharingMessages#WRONG_CODE} for a wrong one
     */
    public PackageKey unlock(char[] code) {
        Protection protection = manifest.protection();
        if (protection == null) {
            return null;
        }
        if (PackageKey.isBlank(code)) {
            throw new InvalidInputException(SharingMessages.CODE_REQUIRED, "Protected package opened without a code");
        }
        PackageKey key = PackageKey.derive(code, Base64.getDecoder().decode(protection.salt()), protection.iterations());
        if (!PackageCrypto.verifyKeyCheck(key, protection.keyCheck())) {
            key.close();
            throw new InvalidInputException(SharingMessages.WRONG_CODE, "Wrong code for package " + manifest.packageId());
        }
        return key;
    }

    /** Reads and verifies {@code course.json}. */
    public CourseFile readCourse(PackageKey key) {
        return readJson(PackageFormat.COURSE, key, CourseFile.class);
    }

    /** Reads and verifies {@code pages.json}. */
    public PagesFile readPages(PackageKey key) {
        return readJson(PackageFormat.PAGES, key, PagesFile.class);
    }

    /** Reads and verifies {@code chunks.json}. */
    public ChunksFile readChunks(PackageKey key) {
        return readJson(PackageFormat.CHUNKS, key, ChunksFile.class);
    }

    /** Reads an entry to its end, verifying it, and discards it. */
    public void verify(String path, PackageKey key) {
        try (EntryInput in = openEntry(path, key)) {
            Streams.drain(in);
            in.finish();
        } catch (IOException e) {
            throw failure(path, e);
        }
    }

    /**
     * Copies the PDF with {@code sha256} to {@code target} through a temporary file in the same folder,
     * verifying the entry and the PDF's own hash before moving it into place. When {@code target} already
     * exists the entry is still verified but nothing is written.
     *
     * @param progress receives the number of stored bytes as they are read
     * @return true when this call created {@code target}
     * @throws java.util.concurrent.CancellationException when {@code token} is cancelled
     */
    public boolean copyDocument(String sha256, PackageKey key, Path target, CancellationToken token, LongConsumer progress) {
        String path = PackageFormat.documentEntry(sha256);
        boolean exists = Files.exists(target);
        Path temp = null;
        try {
            if (!exists) {
                temp = Files.createTempFile(target.getParent(), ".import-", ".partial");
            }
            MessageDigest digest = Streams.sha256();
            try (EntryInput in = openEntry(path, key);
                 OutputStream out = temp == null ? OutputStream.nullOutputStream()
                         : new BufferedOutputStream(Files.newOutputStream(temp))) {
                byte[] buffer = new byte[BUFFER];
                long total = 0;
                long reported = 0;
                int n;
                while ((n = in.read(buffer)) != -1) {
                    total += n;
                    if (total > PackageFormat.MAX_DOCUMENT_BYTES) {
                        throw new DamagedPackageException(path + " is larger than a PDF may be");
                    }
                    digest.update(buffer, 0, n);
                    out.write(buffer, 0, n);
                    long stored = in.storedBytes();
                    progress.accept(stored - reported);
                    reported = stored;
                    token.throwIfCancelled();
                }
                in.finish();
                progress.accept(in.storedBytes() - reported);
            }
            if (!Streams.hex(digest.digest()).equals(sha256)) {
                throw new DamagedPackageException(path + " does not match its name");
            }
            if (temp == null) {
                return false;
            }
            return moveIntoPlace(temp, target);
        } catch (IOException e) {
            throw failure(path, e);
        } finally {
            deleteQuietly(temp);
        }
    }

    private static boolean moveIntoPlace(Path temp, Path target) throws IOException {
        try {
            Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE);
            return true;
        } catch (FileAlreadyExistsException e) {
            return false;
        } catch (AtomicMoveNotSupportedException e) {
            try {
                Files.move(temp, target);
                return true;
            } catch (FileAlreadyExistsException e2) {
                return false;
            }
        }
    }

    private <T> T readJson(String path, PackageKey key, Class<T> type) {
        try (EntryInput in = openEntry(path, key)) {
            T value = JSON.forType(type).readValue(in);
            if (value == null) {
                throw new DamagedPackageException(path + " is empty");
            }
            in.finish();
            return value;
        } catch (IOException e) {
            throw failure(path, e);
        }
    }

    private EntryInput openEntry(String path, PackageKey key) throws IOException {
        Entry entry = manifest.entry(path)
                .orElseThrow(() -> PackageContent.damaged("No " + path + " in the manifest"));
        if (manifest.isProtected() && key == null) {
            throw new IllegalStateException("A protected package needs its key");
        }
        ZipEntry zipEntry = zip.getEntry(path);
        if (zipEntry == null) {
            throw PackageContent.damaged("No " + path + " in the zip");
        }
        InputStream raw = zip.getInputStream(zipEntry);
        Streams.VerifyingInputStream verifying = new Streams.VerifyingInputStream(raw, path, entry.size(), entry.sha256());
        if (!manifest.isProtected()) {
            return new EntryInput(verifying, null, null, verifying);
        }
        InputStream decrypted;
        try {
            decrypted = PackageCrypto.decrypt(verifying,
                    key, PackageCrypto.entryAad(manifest.packageId(), manifest.versionNumber(), path));
        } catch (IOException | RuntimeException e) {
            verifying.close();
            throw e;
        }
        Inflater inflater = PackageFormat.isJson(path) ? new Inflater() : null;
        InputStream plain = inflater != null
                ? new InflaterInputStream(Streams.nonClosing(decrypted), inflater, BUFFER)
                : decrypted;
        InputStream top = new Streams.LimitedInputStream(plain, path, PackageFormat.plaintextLimit(path));
        return new EntryInput(verifying, decrypted, inflater, top);
    }

    private LearnSphereException failure(String path, IOException e) {
        if (DamagedPackageException.isCause(e) || e instanceof JacksonException || e instanceof ZipException
                || e instanceof EOFException) {
            return PackageContent.damaged("Entry " + path + " of " + file + " is damaged", e);
        }
        return new InvalidInputException(SharingMessages.FILE_NOT_OPENED, "Cannot read " + path + " of " + file, e);
    }

    // ---- manifest -----------------------------------------------------------------------------------

    private static PackageManifest readManifest(ZipFile zip) {
        ZipEntry entry = zip.getEntry(PackageFormat.MANIFEST);
        if (entry == null) {
            throw new InvalidInputException(SharingMessages.NOT_A_COURSE_FILE, "No manifest in " + zip.getName());
        }
        JsonNode tree;
        try (InputStream in = new Streams.LimitedInputStream(zip.getInputStream(entry), PackageFormat.MANIFEST,
                PackageFormat.MAX_MANIFEST_BYTES)) {
            tree = JSON.readTree(in);
        } catch (IOException e) {
            throw new InvalidInputException(SharingMessages.NOT_A_COURSE_FILE, "Unreadable manifest in " + zip.getName(), e);
        }
        if (tree == null || !tree.isObject() || !PackageFormat.FORMAT.equals(tree.path("format").asText(null))) {
            throw new InvalidInputException(SharingMessages.NOT_A_COURSE_FILE, "Not a course manifest in " + zip.getName());
        }
        JsonNode version = tree.path("formatVersion");
        if (!version.canConvertToInt() || version.asInt() < 1) {
            throw PackageContent.damaged("Invalid format version in " + zip.getName());
        }
        if (version.asInt() > PackageFormat.VERSION) {
            throw new InvalidInputException(SharingMessages.COURSE_FILE_TOO_NEW,
                    "Package format " + version.asInt() + " is newer than " + PackageFormat.VERSION);
        }
        PackageManifest manifest;
        try {
            manifest = Json.mapper().treeToValue(tree, PackageManifest.class);
        } catch (JacksonException | IllegalArgumentException e) {
            throw PackageContent.damaged("Invalid manifest in " + zip.getName(), e);
        }
        validate(manifest);
        return manifest;
    }

    private static void validate(PackageManifest m) {
        require(Ids.isValid(m.packageId()), "invalid package id");
        require(m.versionNumber() >= 1, "version number below 1");
        require(m.title() != null && !m.title().isBlank(), "blank title");
        require(m.coverHue() >= 0 && m.coverHue() <= 359, "cover hue out of range");
        require(m.createdAt() >= 0 && m.moduleCount() >= 0 && m.itemCount() >= 0 && m.documentCount() >= 0,
                "negative counts");
        require(m.documentCount() <= PackageFormat.MAX_DOCUMENTS, "too many documents");
        require(m.entries().size() <= PackageFormat.MAX_ENTRIES - 1, "too many entries");
        Set<String> names = new HashSet<>();
        for (Entry entry : m.entries()) {
            require(entry != null && entry.path() != null && PackageFormat.isKnownEntry(entry.path()),
                    "unexpected entry name");
            require(names.add(entry.path()), "duplicate entry " + entry.path());
            require(entry.size() >= 0 && entry.size() <= PackageFormat.storedLimit(entry.path(), m.isProtected()),
                    "entry " + entry.path() + " is too large");
            require(PackageFormat.isSha256(entry.sha256()), "entry " + entry.path() + " without a valid hash");
        }
        require(names.contains(PackageFormat.COURSE) && names.contains(PackageFormat.PAGES)
                && names.contains(PackageFormat.CHUNKS), "required entries missing");
        Protection p = m.protection();
        if (p != null) {
            require(PackageCrypto.CIPHER_NAME.equals(p.cipher()) && PackageCrypto.KDF_NAME.equals(p.kdf()),
                    "unknown protection");
            require(p.iterations() >= PackageCrypto.MIN_ITERATIONS && p.iterations() <= PackageCrypto.MAX_ITERATIONS,
                    "iterations out of range");
            require(p.segmentSize() == PackageCrypto.SEGMENT_SIZE, "unsupported segment size");
            require(PackageCrypto.isWellFormedKeyCheck(p.keyCheck()), "malformed key check");
            byte[] salt;
            try {
                salt = Base64.getDecoder().decode(Objects.requireNonNullElse(p.salt(), ""));
            } catch (IllegalArgumentException e) {
                throw PackageContent.damaged("malformed salt", e);
            }
            require(salt.length >= MIN_SALT_BYTES && salt.length <= MAX_SALT_BYTES, "salt length out of range");
        }
    }

    /** Every zip entry must be the manifest or listed in it, once, with the size the manifest declares. */
    private static void checkEntries(ZipFile zip, PackageManifest manifest) {
        Set<String> seen = new HashSet<>();
        int count = 0;
        Enumeration<? extends ZipEntry> entries = zip.entries();
        while (entries.hasMoreElements()) {
            ZipEntry entry = entries.nextElement();
            require(++count <= PackageFormat.MAX_ENTRIES, "too many zip entries");
            String name = entry.getName();
            require(seen.add(name), "duplicate zip entry " + name);
            if (name.equals(PackageFormat.MANIFEST)) {
                continue;
            }
            Entry declared = manifest.entry(name)
                    .orElseThrow(() -> PackageContent.damaged("zip entry not in the manifest: " + name));
            require(entry.getSize() == -1 || entry.getSize() == declared.size(), "size of " + name + " differs");
        }
        for (Entry declared : manifest.entries()) {
            require(seen.contains(declared.path()), "manifest entry missing from the zip: " + declared.path());
        }
    }

    private static void require(boolean condition, String detail) {
        if (!condition) {
            throw PackageContent.damaged(detail);
        }
    }

    @Override
    public void close() {
        closeQuietly(zip);
    }

    private static void closeQuietly(ZipFile zip) {
        try {
            zip.close();
        } catch (IOException e) {
            // nothing to recover
        }
    }

    private static void deleteQuietly(Path path) {
        if (path != null) {
            try {
                Files.deleteIfExists(path);
            } catch (IOException e) {
                // a stray temporary file is harmless
            }
        }
    }

    /**
     * One entry's content stream. {@link #finish()} reads every layer to its end so the hash and the last
     * segment are checked even when a parser stopped early.
     */
    private static final class EntryInput extends InputStream {

        private final Streams.VerifyingInputStream verifying;
        private final InputStream decrypted;
        private final Inflater inflater;
        private final InputStream top;

        EntryInput(Streams.VerifyingInputStream verifying, InputStream decrypted, Inflater inflater, InputStream top) {
            this.verifying = verifying;
            this.decrypted = decrypted;
            this.inflater = inflater;
            this.top = top;
        }

        @Override
        public int read() throws IOException {
            return top.read();
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            return top.read(b, off, len);
        }

        /** Stored bytes consumed so far, for progress. */
        long storedBytes() {
            return verifying.count();
        }

        void finish() throws IOException {
            Streams.drain(top);
            if (decrypted != null && decrypted != top && decrypted.read() != -1) {
                throw new DamagedPackageException("Data after the end of a compressed entry");
            }
            Streams.drain(verifying);
            if (!verifying.isVerified()) {
                throw new DamagedPackageException("Entry was not verified");
            }
        }

        @Override
        public void close() throws IOException {
            try {
                top.close();
                if (decrypted != null) {
                    decrypted.close();
                }
            } finally {
                if (inflater != null) {
                    inflater.end();
                }
                verifying.close();
            }
        }
    }
}
