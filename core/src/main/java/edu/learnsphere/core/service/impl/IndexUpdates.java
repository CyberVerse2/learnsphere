package edu.learnsphere.core.service.impl;

import edu.learnsphere.core.db.Repositories;
import edu.learnsphere.core.model.Chunk;
import edu.learnsphere.core.model.Document;
import edu.learnsphere.core.model.DocumentStatus;
import edu.learnsphere.core.model.JobKind;
import edu.learnsphere.core.service.JobQueue;
import edu.learnsphere.core.service.JobSpec;
import edu.learnsphere.core.spi.IndexedChunk;
import edu.learnsphere.core.spi.SearchIndex;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Keeps the search index in step with documents that are removed, renamed or moved. Each change is
 * committed at once. The index can always be rebuilt from the database, so when a change cannot be
 * written it is logged and a background {@link JobKind#REBUILD_INDEX} is queued instead of failing the
 * operation the user asked for. Never call it inside a database transaction.
 */
final class IndexUpdates {

    private static final Logger LOG = LoggerFactory.getLogger(IndexUpdates.class);

    private final SearchIndex index;
    private final Repositories repos;
    private final JobQueue jobs;

    IndexUpdates(SearchIndex index, Repositories repos, JobQueue jobs) {
        this.index = Objects.requireNonNull(index, "index");
        this.repos = Objects.requireNonNull(repos, "repos");
        this.jobs = Objects.requireNonNull(jobs, "jobs");
    }

    /** Removes every passage of a document. */
    void removeDocument(String documentId) {
        apply("remove document " + documentId, () -> index.deleteByDocument(documentId));
    }

    /** Removes every passage of a course. */
    void removeCourse(String courseId) {
        apply("remove course " + courseId, () -> index.deleteByCourse(courseId));
    }

    /**
     * Writes a ready document's passages again, so the title and module the index keeps for them match the
     * database. Documents that are not ready have nothing searchable yet and are left alone.
     */
    void refreshDocument(String documentId) {
        Optional<Document> found = repos.documents().find(documentId);
        if (found.isEmpty() || found.get().status() != DocumentStatus.READY) {
            return;
        }
        Document document = found.get();
        apply("refresh document " + documentId, () -> {
            String model = index.embeddingModel();
            List<IndexedChunk> passages = repos.chunks().listByDocument(documentId).stream()
                    .filter(chunk -> chunk.hasEmbedding() && model.equals(chunk.embeddingModel()))
                    .map(chunk -> toIndexed(chunk, document.title()))
                    .toList();
            if (!passages.isEmpty()) {
                index.upsert(passages);
            }
        });
    }

    static IndexedChunk toIndexed(Chunk chunk, String documentTitle) {
        return new IndexedChunk(chunk.id(), chunk.documentId(), chunk.courseId(), chunk.moduleId(), documentTitle,
                chunk.pageStart(), chunk.pageEnd(), chunk.sectionTitle(), chunk.text(), chunk.embedding());
    }

    private void apply(String what, Runnable change) {
        try {
            change.run();
            index.commit();
        } catch (RuntimeException e) {
            LOG.warn("Could not update the search index ({}); rebuilding it in the background", what, e);
            try {
                jobs.enqueue(JobSpec.of(JobKind.REBUILD_INDEX, null, null).withPriority(JobSpec.BACKGROUND_PRIORITY));
            } catch (RuntimeException queueError) {
                LOG.warn("Could not queue a search index rebuild", queueError);
            }
        }
    }
}
