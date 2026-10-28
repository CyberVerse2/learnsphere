package edu.learnsphere.core.db;

import edu.learnsphere.core.model.Chunk;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
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

    /**
     * Up to {@code limit} chunks of a course's ready PDFs picked at random, from those with at least
     * {@code minLength} characters of text (passages to teach from when a message names no subject).
     */
    public List<Chunk> sample(String courseId, int limit, int minLength) {
        return db.withHandle(h -> h.createQuery("""
                        SELECT c.* FROM chunks c JOIN documents d ON d.id = c.document_id
                        WHERE c.course_id = :c AND d.status = 'ready' AND length(c.text) >= :min
                        ORDER BY random() LIMIT :limit
                        """)
                .bind("c", courseId).bind("min", minLength).bind("limit", limit).map(CHUNK).list());
    }

    /** Files these passages under a module, or none with {@code null}. */
    public void setModule(Collection<String> ids, String moduleId) {
        if (ids.isEmpty()) {
            return;
        }
        db.useHandle(h -> h.createUpdate("UPDATE chunks SET module_id = :m, updated_at = :now WHERE id IN (<ids>)")
                .bind("m", moduleId).bind("now", clock.millis()).bindList("ids", List.copyOf(ids)).execute());
    }

    /** Section headings of a course's ready PDFs in reading order, repeats included (topics to offer). */
    public List<String> sectionTitles(String courseId) {
        return db.withHandle(h -> h.createQuery("""
                        SELECT c.section_title FROM chunks c JOIN documents d ON d.id = c.document_id
                        WHERE c.course_id = :c AND d.status = 'ready' AND c.section_title IS NOT NULL
                        ORDER BY d.created_at, c.ordinal
                        """)
                .bind("c", courseId).mapTo(String.class).list());
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

    /**
     * Deletes a document's chunks. Learning items drafted from them lose their source passage, so a document
     * that is chunked again goes through {@link #replaceForDocument} instead. Returns how many were deleted.
     */
    public int deleteByDocument(String documentId) {
        return db.withHandle(h -> h.createUpdate("DELETE FROM chunks WHERE document_id = :d").bind("d", documentId).execute());
    }

    /**
     * True when the document has chunks and one of its pages changed after the oldest of them was made (a
     * creator corrected the text of a page read from a scanned image, or figure notes were added), so its
     * chunks must be made again and swapped in with {@link #replaceForDocument}. Times compare strictly: a
     * page written in the same millisecond as the chunks counts as older.
     */
    public boolean isOutdated(String documentId) {
        return db.withHandle(h -> h.createQuery("""
                        SELECT EXISTS (SELECT 1 FROM document_pages p
                          WHERE p.document_id = :d
                            AND p.updated_at > (SELECT MIN(c.created_at) FROM chunks c WHERE c.document_id = :d))
                        """)
                .bind("d", documentId).mapTo(Integer.class).one()) > 0;
    }

    /**
     * Replaces every chunk of a document with {@code fresh} in one transaction, keeping learning items linked
     * to a source passage: an item drafted from an old chunk is pointed at the new chunk that covers its
     * source page (or, without one, the first page of its old chunk), preferring the one that overlaps the
     * old chunk's pages most, then the one nearest its old position. When no new chunk covers that page the
     * nearest one by page is used. Items keep no source only when {@code fresh} is empty. Item rows are not
     * otherwise changed. Returns how many items were linked to a new chunk.
     *
     * @throws IllegalArgumentException when a chunk of {@code fresh} belongs to another document
     */
    public int replaceForDocument(String documentId, List<Chunk> fresh) {
        Objects.requireNonNull(documentId, "documentId");
        List<Chunk> replacements = List.copyOf(fresh);
        for (Chunk chunk : replacements) {
            if (!documentId.equals(chunk.documentId())) {
                throw new IllegalArgumentException("Chunk " + chunk.id() + " belongs to document " + chunk.documentId());
            }
        }
        return db.inTransaction(h -> {
            Map<String, Span> before = new HashMap<>();
            h.createQuery("SELECT id, ordinal, page_start, page_end FROM chunks WHERE document_id = :d")
                    .bind("d", documentId)
                    .map((rs, ctx) -> new Span(rs.getString("id"), rs.getInt("ordinal"), rs.getInt("page_start"),
                            rs.getInt("page_end")))
                    .forEach(span -> before.put(span.id(), span));
            List<SourceLink> links = h.createQuery("""
                            SELECT i.id, i.source_chunk_id, i.source_page FROM learning_items i
                            JOIN chunks c ON c.id = i.source_chunk_id
                            WHERE c.document_id = :d
                            """)
                    .bind("d", documentId)
                    .map((rs, ctx) -> {
                        int stored = rs.getInt("source_page");
                        Integer page = rs.wasNull() ? null : stored;
                        return new SourceLink(rs.getString("id"), rs.getString("source_chunk_id"), page);
                    })
                    .list();
            // Items lose their link here (ON DELETE SET NULL) and get the new one below, in the same transaction.
            h.createUpdate("DELETE FROM chunks WHERE document_id = :d").bind("d", documentId).execute();
            insertAll(replacements);
            if (links.isEmpty() || replacements.isEmpty()) {
                return 0;
            }
            SpanFinder finder = new SpanFinder(replacements.stream().map(Span::of).toList());
            PreparedBatch relink = h.prepareBatch("UPDATE learning_items SET source_chunk_id = :c WHERE id = :id");
            for (SourceLink link : links) {
                Span old = before.get(link.chunkId());
                int page = link.sourcePage() != null ? link.sourcePage() : old.pageStart();
                relink.bind("id", link.itemId()).bind("c", finder.closest(old, page).id()).add();
            }
            relink.execute();
            return links.size();
        });
    }

    /** Where a chunk sits in its document. */
    private record Span(String id, int ordinal, int pageStart, int pageEnd) {

        static Span of(Chunk chunk) {
            return new Span(chunk.id(), chunk.ordinal(), chunk.pageStart(), Math.max(chunk.pageStart(), chunk.pageEnd()));
        }

        int distanceTo(int page) {
            return page < pageStart ? pageStart - page : Math.max(0, page - pageEnd);
        }

        int overlap(Span other) {
            return Math.max(0, Math.min(pageEnd, other.pageEnd) - Math.max(pageStart, other.pageStart) + 1);
        }
    }

    /** An item and the chunk and page it was drafted from. */
    private record SourceLink(String itemId, String chunkId, Integer sourcePage) {
    }

    /** Picks the new chunk an item drafted from an old chunk is linked to. */
    private static final class SpanFinder {

        private final List<Span> spans;
        private final Map<Integer, List<Span>> byPage = new HashMap<>();

        SpanFinder(List<Span> spans) {
            this.spans = spans;
            for (Span span : spans) {
                for (int page = span.pageStart(); page <= span.pageEnd(); page++) {
                    byPage.computeIfAbsent(page, p -> new ArrayList<>()).add(span);
                }
            }
        }

        Span closest(Span old, int page) {
            List<Span> covering = byPage.getOrDefault(page, spans);
            Comparator<Span> order = Comparator.comparingInt((Span s) -> s.distanceTo(page))
                    .thenComparing(Comparator.comparingInt((Span s) -> s.overlap(old)).reversed())
                    .thenComparingInt(s -> Math.abs(s.ordinal() - old.ordinal()))
                    .thenComparingInt(Span::ordinal);
            return covering.stream().min(order).orElseThrow();
        }
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
