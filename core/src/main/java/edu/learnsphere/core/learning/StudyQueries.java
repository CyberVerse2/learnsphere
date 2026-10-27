package edu.learnsphere.core.learning;

import edu.learnsphere.core.db.Database;
import edu.learnsphere.core.db.StudyScope;
import edu.learnsphere.core.model.ItemType;
import edu.learnsphere.core.model.Rating;
import edu.learnsphere.core.model.ReviewPhase;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.OptionalDouble;
import java.util.Set;
import org.jdbi.v3.core.statement.Query;

/**
 * Aggregate queries behind study planning, mastery and the progress screens. Each method is one SQL
 * statement that does its counting in SQLite; callers combine the results. Queries that take a
 * {@link StudyScope} only look at the courses it names (see {@code StudyScope} for "across courses"); queries
 * about the learner's habit (attempts per day, average answering time) look at every attempt. Only
 * approved items count wherever items are counted, because learners only ever study approved items.
 * Thread-safe.
 */
public final class StudyQueries {

    private final Database db;

    public StudyQueries(Database db) {
        this.db = Objects.requireNonNull(db, "db");
    }

    // ---- concepts -----------------------------------------------------------------------------

    /**
     * One approved item tagged with a concept, with its memory state.
     *
     * @param phase        its phase, or null when it has no review state yet
     * @param lastReviewAt last review, or null
     * @param lastFailed   true when the item's latest attempt was rated Again
     */
    public record ConceptItem(String conceptId, String conceptName, String moduleId, String itemId, ReviewPhase phase,
                              double stability, Long lastReviewAt, boolean lastFailed) {

        /** True when the item has been reviewed at least once. */
        public boolean reviewed() {
            return phase != null && phase != ReviewPhase.NEW && lastReviewAt != null;
        }
    }

    /** Every (concept, approved item) pair in {@code scope} with the item's memory state. */
    public List<ConceptItem> conceptItems(StudyScope scope) {
        String sql = "SELECT ic.concept_id, c.name, c.module_id, i.id AS item_id, r.state, r.stability, r.last_review_at,"
                + " (SELECT a.rating FROM attempts a WHERE a.item_id = i.id"
                + "  ORDER BY a.created_at DESC, a.rowid DESC LIMIT 1) AS last_rating"
                + " FROM item_concepts ic JOIN concepts c ON c.id = ic.concept_id"
                + " JOIN learning_items i ON i.id = ic.item_id" + scope.joins()
                + " LEFT JOIN review_states r ON r.item_id = i.id"
                + " WHERE i.status = 'approved' AND " + scope.condition();
        return db.withHandle(h -> scope.bind(h.createQuery(sql))
                .map((rs, ctx) -> {
                    String state = rs.getString("state");
                    long last = rs.getLong("last_review_at");
                    Long lastReviewAt = rs.wasNull() ? null : last;
                    int lastRating = rs.getInt("last_rating");
                    boolean lastFailed = !rs.wasNull() && lastRating == Rating.AGAIN.value();
                    return new ConceptItem(rs.getString("concept_id"), rs.getString("name"), rs.getString("module_id"),
                            rs.getString("item_id"), state == null ? null : ReviewPhase.fromCode(state),
                            rs.getDouble("stability"), lastReviewAt, lastFailed);
                })
                .list());
    }

    /**
     * Attempts on a concept's approved items.
     *
     * @param attempts      every attempt
     * @param recent        the most recent ones counted (at most the requested number)
     * @param recentCorrect correct ones among the recent
     */
    public record ConceptAttempts(String conceptId, int attempts, int recent, int recentCorrect) {

        /** Share correct among the recent attempts, or null without attempts. */
        public Double recentAccuracy() {
            return recent == 0 ? null : (double) recentCorrect / recent;
        }
    }

    /** Attempt counts per concept in {@code scope}, keyed by concept id, with the last {@code recent} counted apart. */
    public Map<String, ConceptAttempts> conceptAttempts(StudyScope scope, int recent) {
        String sql = "SELECT concept_id, COUNT(*) AS total,"
                + " SUM(CASE WHEN rnk <= :recent THEN 1 ELSE 0 END) AS recent,"
                + " SUM(CASE WHEN rnk <= :recent THEN correct ELSE 0 END) AS recent_correct"
                + " FROM (SELECT ic.concept_id, a.correct,"
                + "   ROW_NUMBER() OVER (PARTITION BY ic.concept_id ORDER BY a.created_at DESC, a.id DESC) AS rnk"
                + "   FROM attempts a JOIN item_concepts ic ON ic.item_id = a.item_id"
                + "   JOIN learning_items i ON i.id = a.item_id" + scope.joins()
                + "   WHERE i.status = 'approved' AND " + scope.condition() + ")"
                + " GROUP BY concept_id";
        return db.withHandle(h -> {
            Map<String, ConceptAttempts> result = new HashMap<>();
            scope.bind(h.createQuery(sql)).bind("recent", recent)
                    .map((rs, ctx) -> new ConceptAttempts(rs.getString("concept_id"), rs.getInt("total"),
                            rs.getInt("recent"), rs.getInt("recent_correct")))
                    .forEach(c -> result.put(c.conceptId(), c));
            return result;
        });
    }

    // ---- items --------------------------------------------------------------------------------

    /**
     * Approved items of one module (or with no module, {@code moduleId} null) in a scope.
     *
     * @param approved approved items
     * @param started  of those, reviewed at least once
     * @param due      of those, due at or before the given time
     */
    public record ModuleCounts(String moduleId, int approved, int started, int due) {
    }

    /** Approved, started and due item counts per module in {@code scope}, keyed by module id (null for none). */
    public Map<String, ModuleCounts> itemCountsByModule(StudyScope scope, long now) {
        String sql = "SELECT i.module_id, COUNT(*) AS approved,"
                + " SUM(CASE WHEN r.state IS NOT NULL AND r.state <> 'new' THEN 1 ELSE 0 END) AS started,"
                + " SUM(CASE WHEN r.state IS NOT NULL AND r.state <> 'new' AND r.due_at <= :now THEN 1 ELSE 0 END) AS due"
                + " FROM learning_items i" + scope.joins()
                + " LEFT JOIN review_states r ON r.item_id = i.id"
                + " WHERE i.status = 'approved' AND " + scope.condition()
                + " GROUP BY i.module_id";
        return db.withHandle(h -> {
            Map<String, ModuleCounts> result = new HashMap<>();
            scope.bind(h.createQuery(sql)).bind("now", now)
                    .map((rs, ctx) -> new ModuleCounts(rs.getString("module_id"), rs.getInt("approved"),
                            rs.getInt("started"), rs.getInt("due")))
                    .forEach(c -> result.put(c.moduleId(), c));
            return result;
        });
    }

    /**
     * Ids of the approved items in {@code scope} (only of {@code types} when not empty), in module order then
     * creation order.
     */
    public List<String> approvedItemIds(StudyScope scope, Set<ItemType> types) {
        String sql = "SELECT i.id FROM learning_items i" + scope.joins()
                + " LEFT JOIN modules m ON m.id = i.module_id"
                + " WHERE i.status = 'approved' AND " + scope.condition()
                + (types.isEmpty() ? "" : " AND i.type IN (<types>)")
                + " ORDER BY COALESCE(m.position, 1000000), i.created_at, i.id";
        return db.withHandle(h -> {
            Query q = scope.bind(h.createQuery(sql));
            if (!types.isEmpty()) {
                q.bindList("types", types.stream().map(ItemType::code).toList());
            }
            return q.mapTo(String.class).list();
        });
    }

    /**
     * Ids of approved items in {@code scope} tagged with any of {@code conceptIds} that have been reviewed,
     * are not due at {@code now} and were last reviewed before {@code reviewedBefore}.
     */
    public List<String> reviewedItemIdsForConcepts(StudyScope scope, Collection<String> conceptIds, long now,
                                                   long reviewedBefore) {
        if (conceptIds.isEmpty()) {
            return List.of();
        }
        String sql = "SELECT DISTINCT i.id FROM learning_items i" + scope.joins()
                + " JOIN item_concepts ic ON ic.item_id = i.id"
                + " JOIN review_states r ON r.item_id = i.id"
                + " WHERE i.status = 'approved' AND r.state <> 'new' AND r.due_at > :now"
                + " AND r.last_review_at < :before AND ic.concept_id IN (<concepts>) AND " + scope.condition()
                + " ORDER BY i.id";
        return db.withHandle(h -> scope.bind(h.createQuery(sql)).bind("now", now).bind("before", reviewedBefore)
                .bindList("concepts", List.copyOf(conceptIds)).mapTo(String.class).list());
    }

    /** Ids of approved items in {@code scope} tagged with any of {@code conceptIds}, in module order then creation order. */
    public List<String> approvedItemIdsForConcepts(StudyScope scope, Collection<String> conceptIds) {
        if (conceptIds.isEmpty()) {
            return List.of();
        }
        String sql = "SELECT i.id FROM learning_items i" + scope.joins()
                + " LEFT JOIN modules m ON m.id = i.module_id"
                + " WHERE i.status = 'approved' AND " + scope.condition()
                + " AND EXISTS (SELECT 1 FROM item_concepts ic WHERE ic.item_id = i.id AND ic.concept_id IN (<concepts>))"
                + " ORDER BY COALESCE(m.position, 1000000), i.created_at, i.id";
        return db.withHandle(h -> scope.bind(h.createQuery(sql)).bindList("concepts", List.copyOf(conceptIds))
                .mapTo(String.class).list());
    }

    /** Concept ids per item for {@code itemIds}, keyed by item id in the order given (untagged items map to an empty set). */
    public Map<String, Set<String>> conceptIdsByItem(Collection<String> itemIds) {
        Map<String, Set<String>> result = new LinkedHashMap<>();
        for (String id : itemIds) {
            result.put(id, new LinkedHashSet<>());
        }
        if (itemIds.isEmpty()) {
            return result;
        }
        db.withHandle(h -> h.createQuery(
                        "SELECT item_id, concept_id FROM item_concepts WHERE item_id IN (<ids>) ORDER BY item_id, concept_id")
                .bindList("ids", List.copyOf(itemIds))
                .map((rs, ctx) -> Map.entry(rs.getString("item_id"), rs.getString("concept_id")))
                .list())
                .forEach(e -> result.computeIfAbsent(e.getKey(), k -> new LinkedHashSet<>()).add(e.getValue()));
        return result;
    }

    // ---- attempts -----------------------------------------------------------------------------

    /**
     * Attempt totals.
     *
     * @param attempts   attempts
     * @param correct    correct attempts
     * @param durationMs time spent answering
     */
    public record AttemptTotals(int attempts, int correct, long durationMs) {

        /** correct / attempts, or null without attempts. */
        public Double accuracy() {
            return attempts == 0 ? null : (double) correct / attempts;
        }
    }

    /** Attempts on the items of {@code scope} (any status: retired items keep their history). */
    public AttemptTotals attemptTotals(StudyScope scope) {
        String sql = "SELECT COUNT(*) AS n, COALESCE(SUM(a.correct), 0) AS correct, COALESCE(SUM(a.duration_ms), 0) AS ms"
                + " FROM attempts a JOIN learning_items i ON i.id = a.item_id" + scope.joins()
                + " WHERE " + scope.condition();
        return db.withHandle(h -> scope.bind(h.createQuery(sql))
                .map((rs, ctx) -> new AttemptTotals(rs.getInt("n"), rs.getInt("correct"), rs.getLong("ms")))
                .one());
    }

    /** Attempts in each range across every course, in the order of {@code ranges}. */
    public int[] attemptsPerRange(List<DayRange> ranges) {
        if (ranges.isEmpty()) {
            return new int[0];
        }
        String sql = valuesCte(ranges.size())
                + " SELECT d.idx, (SELECT COUNT(*) FROM attempts a WHERE a.created_at >= d.lo AND a.created_at < d.hi) AS n"
                + " FROM days d";
        int[] counts = new int[ranges.size()];
        db.withHandle(h -> bindRanges(h.createQuery(sql), ranges)
                .map((rs, ctx) -> new int[] {rs.getInt("idx"), rs.getInt("n")})
                .list())
                .forEach(row -> counts[row[0]] = row[1]);
        return counts;
    }

    /**
     * Reviews and recalled reviews (rated Hard or better) on each day on items of {@code scope}.
     *
     * <p>A review is an item's first answer of the day when the item had been answered on an earlier day: a
     * recall of something learned before. An item's very first answer and its later answers the same day
     * (another try after Again, learning steps minutes apart) are left out, so each item counts at most once a
     * day.
     *
     * @param days whole days in the learner's time zone ({@link DayRange#days})
     * @return per day, {@code [reviews, recalled]}
     */
    public int[][] retentionPerDay(StudyScope scope, List<DayRange> days) {
        int[][] result = new int[days.size()][2];
        if (days.isEmpty()) {
            return result;
        }
        String sql = valuesCte(days.size())
                + " SELECT d.idx, COUNT(*) AS reviews, SUM(CASE WHEN a.rating >= 2 THEN 1 ELSE 0 END) AS recalled"
                + " FROM days d JOIN attempts a ON a.created_at >= d.lo AND a.created_at < d.hi"
                + " JOIN learning_items i ON i.id = a.item_id" + scope.joins()
                + " WHERE " + scope.condition()
                + " AND EXISTS (SELECT 1 FROM attempts b WHERE b.item_id = a.item_id AND b.created_at < d.lo)"
                + " AND NOT EXISTS (SELECT 1 FROM attempts b WHERE b.item_id = a.item_id AND b.created_at >= d.lo"
                + "   AND (b.created_at < a.created_at OR (b.created_at = a.created_at AND b.rowid < a.rowid)))"
                + " GROUP BY d.idx";
        db.withHandle(h -> scope.bind(bindRanges(h.createQuery(sql), days))
                .map((rs, ctx) -> new int[] {rs.getInt("idx"), rs.getInt("reviews"), rs.getInt("recalled")})
                .list())
                .forEach(row -> {
                    result[row[0]][0] = row[1];
                    result[row[0]][1] = row[2];
                });
        return result;
    }

    /** Mean answering time of the latest {@code limit} attempts across courses, or empty without attempts. */
    public OptionalDouble averageAttemptMs(int limit) {
        Double avg = db.withHandle(h -> h.createQuery(
                        "SELECT AVG(duration_ms) FROM (SELECT duration_ms FROM attempts WHERE duration_ms > 0"
                                + " ORDER BY created_at DESC LIMIT :limit)")
                .bind("limit", limit).mapTo(Double.class).findOne().orElse(null));
        return avg == null ? OptionalDouble.empty() : OptionalDouble.of(avg);
    }

    // ---- forecast -----------------------------------------------------------------------------

    /**
     * Reviewed approved items of {@code scope} falling due in each range; the first range also counts
     * everything due before it (overdue).
     */
    public int[] duePerRange(StudyScope scope, List<DayRange> ranges) {
        if (ranges.isEmpty()) {
            return new int[0];
        }
        List<DayRange> open = new ArrayList<>(ranges);
        DayRange first = open.getFirst();
        open.set(0, new DayRange(first.first(), Long.MIN_VALUE, first.end()));
        String sql = valuesCte(open.size())
                + " SELECT d.idx, COUNT(r.item_id) AS n FROM days d"
                + " JOIN review_states r ON r.due_at >= d.lo AND r.due_at < d.hi"
                + " JOIN learning_items i ON i.id = r.item_id" + scope.joins()
                + " WHERE i.status = 'approved' AND r.state <> 'new' AND " + scope.condition()
                + " GROUP BY d.idx";
        int[] counts = new int[ranges.size()];
        db.withHandle(h -> scope.bind(bindRanges(h.createQuery(sql), open))
                .map((rs, ctx) -> new int[] {rs.getInt("idx"), rs.getInt("n")})
                .list())
                .forEach(row -> counts[row[0]] = row[1]);
        return counts;
    }

    // ---- helpers ------------------------------------------------------------------------------

    /** {@code WITH days(idx, lo, hi) AS (VALUES (0, :lo0, :hi0), …)}. */
    private static String valuesCte(int count) {
        StringBuilder sql = new StringBuilder("WITH days(idx, lo, hi) AS (VALUES ");
        for (int i = 0; i < count; i++) {
            if (i > 0) {
                sql.append(", ");
            }
            sql.append('(').append(i).append(", :lo").append(i).append(", :hi").append(i).append(')');
        }
        return sql.append(')').toString();
    }

    private static Query bindRanges(Query query, List<DayRange> ranges) {
        for (int i = 0; i < ranges.size(); i++) {
            query.bind("lo" + i, ranges.get(i).start()).bind("hi" + i, ranges.get(i).end());
        }
        return query;
    }
}
