package edu.learnsphere.core.sharing;

import com.fasterxml.jackson.databind.JsonNode;
import edu.learnsphere.core.error.InvalidInputException;
import edu.learnsphere.core.error.LearnSphereException;
import edu.learnsphere.core.error.UserMessage;
import edu.learnsphere.core.util.Json;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Objects;

/** Reading and writing the small JSON files of progress and feedback sharing. */
final class SharingFiles {

    /** Largest progress or feedback file read. */
    static final long MAX_BYTES = 32L * 1024 * 1024;

    private SharingFiles() {
    }

    /**
     * Writes {@code value} as JSON to {@code target} through a temporary file, so a failed write never leaves
     * a partial file under the chosen name.
     *
     * @throws LearnSphereException {@link SharingMessages#SAVE_FAILED}
     */
    static void write(Path target, Object value) {
        Objects.requireNonNull(target, "target");
        Path absolute = target.toAbsolutePath();
        Path temp = null;
        try {
            Path folder = Files.createDirectories(absolute.getParent());
            temp = Files.createTempFile(folder, ".learnsphere-", ".partial");
            Files.writeString(temp, Json.writePretty(value), StandardCharsets.UTF_8);
            try {
                Files.move(temp, absolute, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temp, absolute, StandardCopyOption.REPLACE_EXISTING);
            }
            temp = null;
        } catch (IOException | RuntimeException e) {
            throw new LearnSphereException(SharingMessages.SAVE_FAILED, "Writing " + absolute + " failed", e);
        } finally {
            if (temp != null) {
                try {
                    Files.deleteIfExists(temp);
                } catch (IOException e) {
                    // a stray temporary file is harmless
                }
            }
        }
    }

    /**
     * Reads a JSON file whose {@code format} field must be {@code format}.
     *
     * @param wrongKind the message for a file that is not of this kind, too large, or unreadable as one
     * @throws InvalidInputException {@code wrongKind}, {@link SharingMessages#FILE_TOO_NEW} for a later
     *                               format version, or {@link SharingMessages#FILE_NOT_OPENED}
     */
    static <T> T read(Path file, String format, Class<T> type, UserMessage wrongKind) {
        Objects.requireNonNull(file, "file");
        byte[] bytes;
        try (InputStream in = Files.newInputStream(file)) {
            bytes = in.readNBytes((int) MAX_BYTES + 1);
        } catch (IOException e) {
            throw new InvalidInputException(SharingMessages.FILE_NOT_OPENED, "Reading " + file + " failed", e);
        }
        if (bytes.length > MAX_BYTES) {
            throw new InvalidInputException(wrongKind, file + " is larger than " + MAX_BYTES + " bytes");
        }
        try {
            JsonNode tree = Json.mapper().readTree(bytes);
            if (tree == null || !tree.isObject() || !format.equals(tree.path("format").asText(null))) {
                throw new InvalidInputException(wrongKind, file + " is not a " + format + " file");
            }
            int version = tree.path("formatVersion").asInt(0);
            if (version > 1) {
                throw new InvalidInputException(SharingMessages.FILE_TOO_NEW, file + " has format version " + version);
            }
            return Json.mapper().treeToValue(tree, type);
        } catch (IOException e) {
            throw new InvalidInputException(wrongKind, file + " is not valid " + format + " JSON", e);
        } catch (IllegalArgumentException e) {
            throw new InvalidInputException(wrongKind, file + " has invalid " + format + " content", e);
        }
    }
}
