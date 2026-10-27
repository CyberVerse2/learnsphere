package edu.learnsphere.core.db;

import com.fasterxml.jackson.core.type.TypeReference;
import edu.learnsphere.core.error.NotFoundException;
import edu.learnsphere.core.error.UserMessage;
import edu.learnsphere.core.model.Difficulty;
import edu.learnsphere.core.model.ItemOrigin;
import edu.learnsphere.core.model.ItemStatus;
import edu.learnsphere.core.model.ItemType;
import edu.learnsphere.core.model.LearningItem;
import edu.learnsphere.core.model.SupportStatus;
import edu.learnsphere.core.util.Json;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import org.jdbi.v3.core.mapper.RowMapper;
import org.jdbi.v3.core.statement.PreparedBatch;
import org.jdbi.v3.core.statement.Query;

/**
 * Learning items, their concept tags ({@code item_concepts}) and their embeddings. {@link LearningItem}
 * does not carry the embedding; read it with {@link #embeddingsForModule}.
 */
public final class LearningItemRepository {

    private static final TypeReference<List<String>> STRING_LIST = new TypeReference<>() { };

    static final RowMapper<LearningItem> ITEM = (rs, ctx) -> new LearningItem(
            rs.getString("id"),
            rs.getString("course_id"),
            rs.getString("module_id"),
            ItemType.fromCode(rs.getString("type")),
            rs.getString("prompt"),
            rs.getString("answer"),
            rs.getString("explanation"),
            Json.read(rs.getString("options_json"), STRING_LIST),
            Difficulty.fromValue(rs.getInt("difficulty")),
            rs.getString("source_chunk_id"),
            Rows.nullableInt(rs, "source_page"),
            ItemOrigin.fromCode(rs.getString("origin")),
            ItemStatus.fromCode(rs.getString("status")),
            SupportStatus.fromCode(rs.getString("support")),
            rs.getString("support_note"),
            rs.getLong("created_at"),
            rs.getLong("updated_at"));

    private static final String INSERT = """
            INSERT INTO learning_items (id, course_id, module_id, type, prompt, answer, explanation, options_json,
              difficulty, source_chunk_id, source_page, origin, status, support, support_note, created_at, updated_at)
            VALUES (:id, :courseId, :moduleId, :type, :prompt, :answer, :explanation, :options, :difficulty,
              :sourceChunkId, :sourcePage, :origin, :status, :support, :supportNote, :createdAt, :updatedAt)
            """;

    private final Database db;
    private final Clock clock;

    public LearningItemRepository(Database db, Clock clock) {
        this.db = Objects.requireNonNull(db, "db");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    // ---- items --------------------------------------------------------------------------------

    public void insert(LearningItem item) {
        insertAll(List.of(item));
    }

    public void insertAll(Collection<LearningItem> items) {
        if (items.isEmpty()) {
            return;
        }
        db.useTransaction(h -> {
            PreparedBatch batch = h.prepareBatch(INSERT);
            for (LearningItem i : items) {
                batch.bind("id", i.id()).bind("courseId", i.courseId()).bind("moduleId", i.moduleId())
                        .bind("type", i.type().code()).bind("prompt", i.prompt()).bind("answer", i.answer())
                        .bind("explanation", i.explanation()).bind("options", Json.write(i.distractors()))
                        .bind("difficulty", i.difficulty().value()).bind("sourceChunkId", i.sourceChunkId())
                        .bind("sourcePage", i.sourcePage()).bind("origin", i.origin().code())
                        .bind("status", i.status().code()).bind("support", i.support().code())
                        .bind("supportNote", i.supportNote()).bind("createdAt", i.createdAt())
                        .bind("updatedAt", i.updatedAt())
                        .add();
            }
            batch.execute();
        });
    }

    /**
     * Writes every mutable column and stamps {@code updated_at}. A changed prompt or answer clears the
     * stored embedding, which no longer matches the text.
     */
    public boolean update(LearningItem i) {
        return db.withHandle(h -> h.createUpdate("""
                        UPDATE learning_items SET
                          embedding = CASE WHEN prompt = :prompt AND answer = :answer THEN embedding END,
                          embedding_model = CASE WHEN prompt = :prompt AND answer = :answer THEN embedding_model END,
                          module_id = :moduleId, type = :type, prompt = :prompt, answer = :answer,
                          explanation = :explanation, options_json = :options, difficulty = :difficulty,
                          source_chunk_id = :sourceChunkId, source_page = :sourcePage, origin = :origin, status = :status,
                          support = :support, support_note = :supportNote, updated_at = :now
                        WHERE id = :id
                        """)
                .bind("id", i.id()).bind("moduleId", i.moduleId()).bind("type", i.type().code())
                .bind("prompt", i.prompt()).bind("answer", i.answer()).bind("explanation", i.explanation())
                .bind("options", Json.write(i.distractors())).bind("difficulty", i.difficulty().value())
                .bind("sourceChunkId", i.sourceChunkId()).bind("sourcePage", i.sourcePage())
                .bind("origin", i.origin().code()).bind("status", i.status().code())
                .bind("support", i.support().code()).bind("supportNote", i.supportNote())
                .bind("now", clock.millis())
                .execute()) > 0;
    }

    public Optional<LearningItem> find(String id) {
        return db.withHandle(h -> h.createQuery("SELECT * FROM learning_items WHERE id = :id").bind("id", id).map(ITEM).findOne());
    }

    /** @throws NotFoundException when there is no such item */
    public LearningItem get(String id) {
        return find(id).orElseThrow(() -> NotFoundException.of(UserMessage.ITEM_NOT_FOUND, "item", id));
    }

    /** Items with these ids, in the order given; missing ids are skipped. */
    public List<LearningItem> listByIds(Collection<String> ids) {
        if (ids.isEmpty()) {
            return List.of();
        }
        Map<String, LearningItem> byId = new HashMap<>();
        db.withHandle(h -> h.createQuery("SELECT * FROM learning_items WHERE id IN (<ids>)")
                .bindList("ids", List.copyOf(ids)).map(ITEM).list()).forEach(i -> byId.put(i.id(), i));
        List<LearningItem> ordered = new ArrayList<>();
        for (String id : ids) {
            LearningItem item = byId.remove(id);
            if (item != null) {
                ordered.add(item);
            }
        }
        return ordered;
    }

    /** Items matching {@code query}, in module order, then creation order. */
    public List<LearningItem> list(ItemQuery query) {
        StringBuilder sql = new StringBuilder(
                "SELECT i.* FROM learning_items i LEFT JOIN modules m ON m.id = i.module_id WHERE 1 = 1");
        if (query.courseId() != null) {
            sql.append(" AND i.course_id = :courseId");
        }
        if (query.moduleId() != null) {
            sql.append(" AND i.module_id = :moduleId");
        }
        if (!query.statuses().isEmpty()) {
            sql.append(" AND i.status IN (<statuses>)");
        }
        if (!query.types().isEmpty()) {
            sql.append(" AND i.type IN (<types>)");
        }
        if (!query.support().isEmpty()) {
            sql.append(" AND i.support IN (<support>)");
        }
        if (query.origin() != null) {
            sql.append(" AND i.origin = :origin");
        }
        if (query.conceptId() != null) {
            sql.append(" AND EXISTS (SELECT 1 FROM item_concepts ic WHERE ic.item_id = i.id AND ic.concept_id = :conceptId)");
        }
        boolean search = query.search() != null && !query.search().isBlank();
        if (search) {
            sql.append(" AND (i.prompt LIKE :search ESCAPE '!' OR i.answer LIKE :search ESCAPE '!'"
                    + " OR i.explanation LIKE :search ESCAPE '!')");
        }
        if (query.flaggedOnly()) {
            sql.append(" AND EXISTS (SELECT 1 FROM flags f WHERE f.item_id = i.id AND f.resolved_at IS NULL)");
        }
        sql.append(" ORDER BY COALESCE(m.position, 1000000), i.created_at, i.id");
        if (query.limit() > 0) {
            sql.append(" LIMIT :limit OFFSET :offset");
        }
        return db.withHandle(h -> {
            Query q = h.createQuery(sql.toString());
            if (query.courseId() != null) {
                q.bind("courseId", query.courseId());
            }
            if (query.moduleId() != null) {
                q.bind("moduleId", query.moduleId());
            }
            if (!query.statuses().isEmpty()) {
                q.bindList("statuses", query.statuses().stream().map(ItemStatus::code).toList());
            }
            if (!query.types().isEmpty()) {
                q.bindList("types", query.types().stream().map(ItemType::code).toList());
            }
            if (!query.support().isEmpty()) {
                q.bindList("support", query.support().stream().map(SupportStatus::code).toList());
            }
            if (query.origin() != null) {
                q.bind("origin", query.origin().code());
            }
            if (query.conceptId() != null) {
                q.bind("conceptId", query.conceptId());
            }
            if (search) {
                q.bind("search", "%" + escapeLike(query.search().strip()) + "%");
            }
            if (query.limit() > 0) {
                q.bind("limit", query.limit()).bind("offset", query.offset());
            }
            return q.map(ITEM).list();
        });
    }

    /** Items of a course with any of {@code statuses} (all statuses when none are given). */
    public List<LearningItem> listByCourse(String courseId, ItemStatus... statuses) {
        return list(ItemQuery.forCourse(courseId).withStatuses(statuses));
    }

    /** Items of a module with any of {@code statuses} (all statuses when none are given). */
    public List<LearningItem> listByModule(String moduleId, ItemStatus... statuses) {
        return list(ItemQuery.all().withModule(moduleId).withStatuses(statuses));
    }

    /** Items generated from a passage. */
    public List<LearningItem> listBySourceChunk(String chunkId) {
        return db.withHandle(h -> h.createQuery("SELECT * FROM learning_items WHERE source_chunk_id = :c ORDER BY created_at")
                .bind("c", chunkId).map(ITEM).list());
    }

    /**
     * Approved items in {@code scope} that have never been reviewed (no review state, or state new), in
     * module order then creation order: the queue of new items for study sessions.
     */
    public List<LearningItem> listUnseen(StudyScope scope, int limit) {
        String sql = "SELECT i.* FROM learning_items i" + scope.joins()
                + " LEFT JOIN review_states r ON r.item_id = i.id"
                + " LEFT JOIN modules m ON m.id = i.module_id"
                + " WHERE i.status = 'approved' AND (r.item_id IS NULL OR r.state = 'new') AND " + scope.condition()
                + " ORDER BY COALESCE(m.position, 1000000), i.created_at, i.id LIMIT :limit";
        return db.withHandle(h -> scope.bind(h.createQuery(sql)).bind("limit", limit).map(ITEM).list());
    }

    /** How many approved items in {@code scope} have never been reviewed. */
    public int countUnseen(StudyScope scope) {
        String sql = "SELECT COUNT(*) FROM learning_items i" + scope.joins()
                + " LEFT JOIN review_states r ON r.item_id = i.id"
                + " WHERE i.status = 'approved' AND (r.item_id IS NULL OR r.state = 'new') AND " + scope.condition();
        return db.withHandle(h -> scope.bind(h.createQuery(sql)).mapTo(Integer.class).one());
    }

    /** Item counts per status for a course. Every status is present (0 when none). */
    public Map<ItemStatus, Integer> countByStatus(String courseId) {
        Map<ItemStatus, Integer> counts = new EnumMap<>(ItemStatus.class);
        for (ItemStatus status : ItemStatus.values()) {
            counts.put(status, 0);
        }
        db.withHandle(h -> h.createQuery("SELECT status, COUNT(*) AS n FROM learning_items WHERE course_id = :c GROUP BY status")
                        .bind("c", courseId)
                        .map((rs, ctx) -> Map.entry(ItemStatus.fromCode(rs.getString("status")), rs.getInt("n")))
                        .list())
                .forEach(e -> counts.put(e.getKey(), e.getValue()));
        return counts;
    }

    /** How many items were drafted from passages of a document. */
    public int countBySourceDocument(String documentId) {
        return db.withHandle(h -> h.createQuery("""
                        SELECT COUNT(*) FROM learning_items i JOIN chunks c ON c.id = i.source_chunk_id
                        WHERE c.document_id = :d
                        """)
                .bind("d", documentId).mapTo(Integer.class).one());
    }

    /** Drafts of a course whose source passage does not support the answer. */
    public int countUnsupportedDrafts(String courseId) {
        return db.withHandle(h -> h.createQuery("""
                        SELECT COUNT(*) FROM learning_items
                        WHERE course_id = :c AND status = 'draft' AND support = 'unsupported'
                        """)
                .bind("c", courseId).mapTo(Integer.class).one());
    }

    /** How many items of a course have at least one open flag. */
    public int countFlagged(String courseId) {
        return db.withHandle(h -> h.createQuery("""
                        SELECT COUNT(*) FROM learning_items i
                        WHERE i.course_id = :c
                          AND EXISTS (SELECT 1 FROM flags f WHERE f.item_id = i.id AND f.resolved_at IS NULL)
                        """)
                .bind("c", courseId).mapTo(Integer.class).one());
    }

    public boolean setStatus(String id, ItemStatus status) {
        return setStatus(List.of(id), status) > 0;
    }

    /** Sets the status of many items; returns how many changed. */
    public int setStatus(Collection<String> ids, ItemStatus status) {
        if (ids.isEmpty()) {
            return 0;
        }
        return db.withHandle(h -> h.createUpdate(
                        "UPDATE learning_items SET status = :s, updated_at = :now WHERE id IN (<ids>) AND status <> :s")
                .bind("s", status.code()).bindList("ids", List.copyOf(ids)).bind("now", clock.millis())
                .execute());
    }

    public boolean setSupport(String id, SupportStatus support, String note) {
        return db.withHandle(h -> h.createUpdate(
                        "UPDATE learning_items SET support = :s, support_note = :n, updated_at = :now WHERE id = :id")
                .bind("id", id).bind("s", support.code()).bind("n", note).bind("now", clock.millis())
                .execute()) > 0;
    }

    public boolean delete(String id) {
        return db.withHandle(h -> h.createUpdate("DELETE FROM learning_items WHERE id = :id").bind("id", id).execute()) > 0;
    }

    // ---- embeddings ---------------------------------------------------------------------------

    /**
     * Stores item embeddings (prompt and answer, embedded as a passage) keyed by item id, tagged with the
     * embedder's name. Used to drop near-duplicate drafts without embedding every existing item again.
     */
    public void updateEmbeddings(Map<String, float[]> embeddings, String model) {
        if (embeddings.isEmpty()) {
            return;
        }
        Objects.requireNonNull(model, "model");
        long now = clock.millis();
        db.useTransaction(h -> {
            PreparedBatch batch = h.prepareBatch(
                    "UPDATE learning_items SET embedding = :e, embedding_model = :m, updated_at = :now WHERE id = :id");
            embeddings.forEach((id, vector) ->
                    batch.bind("id", id).bind("e", EmbeddingCodec.encode(vector)).bind("m", model).bind("now", now).add());
            batch.execute();
        });
    }

    /**
     * Embeddings made by {@code model} for the items of a module that are drafts or approved, keyed by item
     * id (the near-duplicate check). Items without one, or with one from another embedder, are absent.
     */
    public Map<String, float[]> embeddingsForModule(String moduleId, String model) {
        return db.withHandle(h -> {
            Map<String, float[]> result = new LinkedHashMap<>();
            h.createQuery("""
                            SELECT id, embedding FROM learning_items
                            WHERE module_id = :m AND embedding IS NOT NULL AND embedding_model = :model
                              AND status IN ('draft', 'approved')
                            ORDER BY created_at, id
                            """)
                    .bind("m", moduleId).bind("model", model)
                    .map((rs, ctx) -> Map.entry(rs.getString("id"), EmbeddingCodec.decode(rs.getBytes("embedding"))))
                    .forEach(e -> result.put(e.getKey(), e.getValue()));
            return result;
        });
    }

    /**
     * Draft and approved items of a module with no embedding from {@code model} (never embedded, or embedded
     * by another embedder), oldest first.
     */
    public List<LearningItem> listMissingEmbedding(String moduleId, String model, int limit) {
        return db.withHandle(h -> h.createQuery("""
                        SELECT * FROM learning_items
                        WHERE module_id = :m AND status IN ('draft', 'approved')
                          AND (embedding IS NULL OR embedding_model IS NOT :model)
                        ORDER BY created_at, id LIMIT :limit
                        """)
                .bind("m", moduleId).bind("model", model).bind("limit", limit).map(ITEM).list());
    }

    // ---- concept tags -------------------------------------------------------------------------

    /** Replaces the concepts an item is tagged with. */
    public void setConcepts(String itemId, Collection<String> conceptIds) {
        long now = clock.millis();
        db.useTransaction(h -> {
            h.createUpdate("DELETE FROM item_concepts WHERE item_id = :i").bind("i", itemId).execute();
            if (conceptIds.isEmpty()) {
                return;
            }
            PreparedBatch batch = h.prepareBatch(
                    "INSERT OR IGNORE INTO item_concepts (item_id, concept_id, created_at) VALUES (:i, :c, :t)");
            for (String conceptId : conceptIds) {
                batch.bind("i", itemId).bind("c", conceptId).bind("t", now).add();
            }
            batch.execute();
        });
    }

    /** Ids of the concepts an item is tagged with. */
    public List<String> conceptIds(String itemId) {
        return db.withHandle(h -> h.createQuery("SELECT concept_id FROM item_concepts WHERE item_id = :i ORDER BY concept_id")
                .bind("i", itemId).mapTo(String.class).list());
    }

    /** Concept ids per item for every tagged item of a course, keyed by item id. */
    public Map<String, List<String>> conceptLinks(String courseId) {
        return db.withHandle(h -> {
            Map<String, List<String>> links = new LinkedHashMap<>();
            h.createQuery("""
                            SELECT ic.item_id, ic.concept_id FROM item_concepts ic
                            JOIN learning_items i ON i.id = ic.item_id
                            WHERE i.course_id = :c ORDER BY ic.item_id, ic.concept_id
                            """)
                    .bind("c", courseId)
                    .map((rs, ctx) -> Map.entry(rs.getString("item_id"), rs.getString("concept_id")))
                    .forEach(e -> links.computeIfAbsent(e.getKey(), k -> new ArrayList<>()).add(e.getValue()));
            return links;
        });
    }

    /** Ids of the items tagged with a concept. */
    public List<String> itemIdsForConcept(String conceptId) {
        return db.withHandle(h -> h.createQuery("SELECT item_id FROM item_concepts WHERE concept_id = :c ORDER BY item_id")
                .bind("c", conceptId).mapTo(String.class).list());
    }

    /** Ids of the items tagged with any of the concepts. */
    public Set<String> itemIdsForConcepts(Collection<String> conceptIds) {
        if (conceptIds.isEmpty()) {
            return Set.of();
        }
        return Set.copyOf(db.withHandle(h -> h.createQuery("SELECT DISTINCT item_id FROM item_concepts WHERE concept_id IN (<ids>)")
                .bindList("ids", List.copyOf(conceptIds)).mapTo(String.class).list()));
    }

    /**
     * Escapes LIKE wildcards with {@code !} (Jdbi's statement parser treats a backslash as its own escape
     * character, so the SQL uses {@code ESCAPE '!'}).
     */
    static String escapeLike(String text) {
        return text.replace("!", "!!").replace("%", "!%").replace("_", "!_");
    }
}
