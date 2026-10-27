package edu.learnsphere.core.db;

import edu.learnsphere.core.model.Chunk;
import java.time.Clock;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Stream;
import org.jdbi.v3.core.mapper.RowMapper;
import org.jdbi.v3.core.statement.PreparedBatch;

/** Passages of documents with their embeddings. */
public final class ChunkRepository {

    static final RowMapper<Chunk> CHUNK = (rs, ctx) -> new Chunk(
            rs.getString("id"),
            rs.getString("document_id"),
            rs.getString("course_id"),
            rs.getString("module_id"),
            rs.getInt("ordinal"),
            rs.getInt("page_start"),
            rs.getInt("page_end"),
            rs.getString("section_title"),
            rs.getString("text"),
            rs.getInt("token_estimate"),
            EmbeddingCodec.decode(rs.getBytes("embedding")),
            rs.getString("embedding_model"),
            rs.getLong("created_at"),
            rs.getLong("updated_at"));

    /** A chunk together with the title of its document, as search indexing and citations need. */
    public record ChunkWithTitle(Chunk chunk, String documentTitle) {
    }

    private final Database db;
    private final Clock clock;

    public ChunkRepository(Database db, Clock clock) {
        this.db = Objects.requireNonNull(db, "db");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public void insert(Chunk chunk) {
        insertAll(List.of(chunk));
    }

    /** Inserts chunks in one batch. */
    public void insertAll(Collection<Chunk> chunks) {
        if (chunks.isEmpty()) {
            return;
        }
        db.useTransaction(h -> {
            PreparedBatch batch = h.prepareBatch("""
                    INSERT INTO chunks (id, document_id, course_id, module_id, ordinal, page_start, page_end, section_title,
                      text, token_estimate, embedding, embedding_model, created_at, updated_at)
                    VALUES (:id, :documentId, :courseId, :moduleId, :ordinal, :pageStart, :pageEnd, :sectionTitle,
                      :text, :tokens, :embedding, :model, :createdAt, :updatedAt)
                    """);
            for (Chunk c : chunks) {
                batch.bind("id", c.id()).bind("documentId", c.documentId()).bind("courseId", c.courseId())
                        .bind("moduleId", c.moduleId()).bind("ordinal", c.ordinal()).bind("pageStart", c.pageStart())
                        .bind("pageEnd", c.pageEnd()).bind("sectionTitle", c.sectionTitle()).bind("text", c.text())
                        .bind("tokens", c.tokenEstimate()).bind("embedding", EmbeddingCodec.encode(c.embedding()))
                        .bind("model", c.embeddingModel()).bind("createdAt", c.createdAt())
                        .bind("updatedAt", c.updatedAt())
                        .add();
            }
            batch.execute();
        });
    }

    public Optional<Chunk> find(String id) {
        return db.withHandle(h -> h.createQuery("SELECT * FROM chunks WHERE id = :id").bind("id", id).map(CHUNK).findOne());
    }

    /** Chunks with these ids, in no particular order; missing ids are skipped. */
    public List<Chunk> listByIds(Collection<String> ids) {
        if (ids.isEmpty()) {
            return List.of();
        }
        return db.withHandle(h -> h.createQuery("SELECT * FROM chunks WHERE id IN (<ids>)")
                .bindList("ids", List.copyOf(ids)).map(CHUNK).list());
    }

    /** Chunks of a document in reading order. */
    public List<Chunk> listByDocument(String documentId) {
        return db.withHandle(h -> h.createQuery("SELECT * FROM chunks WHERE document_id = :d ORDER BY ordinal")
                .bind("d", documentId).map(CHUNK).list());
    }

    /** Chunks of a module in reading order (documents by import time). */
    public List<Chunk> listByModule(String moduleId) {
        return db.withHandle(h -> h.createQuery("""
                        SELECT c.* FROM chunks c JOIN documents d ON d.id = c.document_id
                        WHERE c.module_id = :m AND d.status = 'ready'
                        ORDER BY d.created_at, c.ordinal
                        """)
                .bind("m", moduleId).map(CHUNK).list());
    }

    /** Every chunk of a course, with embeddings, in reading order. */
    public List<Chunk> listByCourse(String courseId) {
        return db.withHandle(h -> h.createQuery("""
                        SELECT c.* FROM chunks c JOIN documents d ON d.id = c.document_id
                        WHERE c.course_id = :c ORDER BY d.created_at, c.ordinal
                        """)
                .bind("c", courseId).map(CHUNK).list());
    }

    /** Chunks of a document that have no embedding yet (to resume embedding). */
    public List<Chunk> listMissingEmbedding(String documentId) {
        return db.withHandle(h -> h.createQuery(
                        "SELECT * FROM chunks WHERE document_id = :d AND embedding IS NULL ORDER BY ordinal")
                .bind("d", documentId).map(CHUNK).list());
    }

    /** Stores embeddings for many chunks, keyed by chunk id, in one batch. */
    public void updateEmbeddings(Map<String, float[]> embeddings, String model) {
        if (embeddings.isEmpty()) {
            return;
        }
        long now = clock.millis();
        db.useTransaction(h -> {
            PreparedBatch batch = h.prepareBatch(
                    "UPDATE chunks SET embedding = :e, embedding_model = :m, updated_at = :now WHERE id = :id");
            embeddings.forEach((id, vector) ->
                    batch.bind("id", id).bind("e", EmbeddingCodec.encode(vector)).bind("m", model).bind("now", now).add());
            batch.execute();
        });
    }

    public int countByDocument(String documentId) {
        return db.withHandle(h -> h.createQuery("SELECT COUNT(*) FROM chunks WHERE document_id = :d")
                .bind("d", documentId).mapTo(Integer.class).one());
    }

    public int countByCourse(String courseId) {
        return db.withHandle(h -> h.createQuery("SELECT COUNT(*) FROM chunks WHERE course_id = :c")
                .bind("c", courseId).mapTo(Integer.class).one());
    }

    /** Deletes a document's chunks (before chunking it again). Returns how many were deleted. */
    public int deleteByDocument(String documentId) {
        return db.withHandle(h -> h.createUpdate("DELETE FROM chunks WHERE document_id = :d").bind("d", documentId).execute());
    }

    /**
     * Gives {@code action} a lazy stream of every chunk of ready documents that has an embedding from
     * {@code model}, with its document title, in course and reading order. The database handle stays
     * open until {@code action} returns, so the stream can feed {@code SearchIndex.rebuild} without
     * loading every vector into memory. Do not keep the stream after {@code action} returns.
     */
    public <R> R withIndexable(String model, Function<Stream<ChunkWithTitle>, R> action) {
        Objects.requireNonNull(model, "model");
        return db.withHandle(h -> {
            try (Stream<ChunkWithTitle> stream = h.createQuery("""
                            SELECT c.*, d.title AS document_title FROM chunks c JOIN documents d ON d.id = c.document_id
                            WHERE c.embedding IS NOT NULL AND c.embedding_model = :model AND d.status = 'ready'
                            ORDER BY c.course_id, d.created_at, c.ordinal
                            """)
                    .bind("model", model)
                    .map((rs, ctx) -> new ChunkWithTitle(CHUNK.map(rs, ctx), rs.getString("document_title")))
                    .stream()) {
                return action.apply(stream);
            }
        });
    }

    /**
     * Chunks whose embedding is missing or was made by another embedder than {@code currentModel}, oldest
     * documents first, so a change of embedder can re-embed them in batches.
     */
    public List<Chunk> listWithStaleEmbedding(String currentModel, int limit) {
        return db.withHandle(h -> h.createQuery("""
                        SELECT c.* FROM chunks c JOIN documents d ON d.id = c.document_id
                        WHERE c.embedding IS NULL OR c.embedding_model IS NOT :model
                        ORDER BY d.created_at, c.document_id, c.ordinal LIMIT :limit
                        """)
                .bind("model", currentModel).bind("limit", limit).map(CHUNK).list());
    }

    /** How many chunks have no embedding from {@code currentModel}. */
    public int countWithStaleEmbedding(String currentModel) {
        return db.withHandle(h -> h.createQuery(
                        "SELECT COUNT(*) FROM chunks WHERE embedding IS NULL OR embedding_model IS NOT :model")
                .bind("model", currentModel).mapTo(Integer.class).one());
    }

    /** Chunks with these ids and their document titles (for building citations). */
    public List<ChunkWithTitle> listWithTitles(Collection<String> ids) {
        if (ids.isEmpty()) {
            return List.of();
        }
        return db.withHandle(h -> h.createQuery("""
                        SELECT c.*, d.title AS document_title FROM chunks c JOIN documents d ON d.id = c.document_id
                        WHERE c.id IN (<ids>)
                        """)
                .bindList("ids", List.copyOf(ids))
                .map((rs, ctx) -> new ChunkWithTitle(CHUNK.map(rs, ctx), rs.getString("document_title")))
                .list());
    }
}
