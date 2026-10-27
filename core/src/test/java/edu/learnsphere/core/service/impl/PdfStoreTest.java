package edu.learnsphere.core.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import edu.learnsphere.core.AppPaths;
import edu.learnsphere.core.model.Course;
import edu.learnsphere.core.model.Document;
import edu.learnsphere.core.testing.TestDb;
import edu.learnsphere.core.util.Ids;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PdfStoreTest {

    @TempDir
    Path dir;

    private TestDb t;
    private AppPaths.ProfilePaths paths;
    private PdfStore store;
    private Path source;

    @BeforeEach
    void setUp() throws IOException {
        t = TestDb.open(dir.resolve("db"));
        paths = AppPaths.at(dir.resolve("home")).profile(Ids.newId());
        store = new PdfStore(paths, t.repos.documents());
        source = Files.writeString(dir.resolve("abc.pdf"), "abc");
    }

    @AfterEach
    void tearDown() {
        t.close();
    }

    @Test
    void storesFilesByTheirContent() throws IOException {
        String sha = "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad";
        assertThat(PdfStore.sha256(source)).isEqualTo(sha);

        String stored;
        try (PdfStore.Staged staged = store.stage(source)) {
            assertThat(staged.sha256()).isEqualTo(sha);
            assertThat(staged.byteSize()).isEqualTo(3);
            stored = store.commit(staged, staged::sha256);
        }

        assertThat(stored).isEqualTo(sha);
        assertThat(store.contains(sha)).isTrue();
        assertThat(store.file(sha)).hasContent("abc");
        assertThat(filesInStore()).containsExactly(store.file(sha));
    }

    @Test
    void aFailedWriteRemovesOnlyAFileItStored() throws IOException {
        PdfStore.Staged first = store.stage(source);
        assertThatThrownBy(() -> store.commit(first, () -> {
            throw new IllegalStateException("insert failed");
        })).isInstanceOf(IllegalStateException.class);
        assertThat(filesInStore()).isEmpty();

        Document document = document(PdfStore.sha256(source));
        Files.writeString(store.file(document.fileSha256()), "abc");
        PdfStore.Staged second = store.stage(source);
        assertThatThrownBy(() -> store.commit(second, () -> {
            throw new IllegalStateException("insert failed");
        })).isInstanceOf(IllegalStateException.class);
        assertThat(filesInStore()).containsExactly(store.file(document.fileSha256()));
    }

    @Test
    void deletesFilesNoDocumentUsesAndTheirPageImages() throws IOException {
        Document kept = document(PdfStore.sha256(source));
        Document removed = document(kept.fileSha256());
        Files.writeString(store.file(kept.fileSha256()), "abc");
        Path image = Files.writeString(store.renderFile(removed.id(), 1, 1f), "png");

        store.delete(List.of(kept.fileSha256()), List.of(removed.id()), () -> t.repos.documents().delete(removed.id()));
        assertThat(store.contains(kept.fileSha256())).isTrue();
        assertThat(image).doesNotExist();

        store.delete(List.of(kept.fileSha256()), List.of(kept.id()), () -> t.repos.documents().delete(kept.id()));
        assertThat(store.contains(kept.fileSha256())).isFalse();
    }

    @Test
    void deletesCopiesAnEarlierRunLeftHalfWritten() throws IOException {
        Document document = document(PdfStore.sha256(source));
        Path stored = Files.writeString(store.file(document.fileSha256()), "abc");
        Path image = Files.writeString(store.renderFile(document.id(), 1, 1f), "png");
        Path halfImported = Files.writeString(paths.filesDir().resolve(PdfStore.IMPORT_PREFIX + "123"
                + PdfStore.PARTIAL_SUFFIX), "ab");
        Path halfRendered = Files.writeString(image.resolveSibling(PdfStore.RENDER_PREFIX + "456"
                + PdfStore.PARTIAL_SUFFIX), "pn");
        Path unrelated = Files.writeString(paths.filesDir().resolve("notes.partial"), "keep");

        PdfStore reopened = new PdfStore(paths, t.repos.documents());

        assertThat(halfImported).doesNotExist();
        assertThat(halfRendered).doesNotExist();
        assertThat(stored).hasContent("abc");
        assertThat(image).hasContent("png");
        assertThat(unrelated).exists();
        assertThat(reopened.removeLeftovers()).isZero();
    }

    private Document document(String sha) {
        Course course = t.course("Biology " + Ids.newId());
        Document document = Document.queued(course.id(), null, "Notes", "Notes.pdf", sha, 3, t.now());
        t.repos.documents().insert(document);
        return document;
    }

    private List<Path> filesInStore() throws IOException {
        try (Stream<Path> files = Files.list(paths.filesDir())) {
            return files.toList();
        }
    }
}
