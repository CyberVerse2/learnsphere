package edu.learnsphere.core.db;

import java.time.Clock;
import java.util.Objects;

/**
 * Every repository of one profile database, built once and shared. Services take this in their
 * constructor and use {@link #database()} for transactions spanning several repositories:
 *
 * <pre>{@code
 * repos.database().useTransaction(h -> {
 *     repos.items().setStatus(ids, ItemStatus.APPROVED);
 *     repos.audit().append("approve", EntityType.ITEM, null, ids.size() + " items");
 * });
 * }</pre>
 */
public final class Repositories {

    private final Database database;
    private final Clock clock;
    private final CourseRepository courses;
    private final ModuleRepository modules;
    private final DocumentRepository documents;
    private final ChunkRepository chunks;
    private final ConceptRepository concepts;
    private final LearningItemRepository items;
    private final ReviewStateRepository reviews;
    private final StudySessionRepository sessions;
    private final ChatRepository chats;
    private final JobRepository jobs;
    private final FlagRepository flags;
    private final ClassInsightRepository classInsights;
    private final AuditLogRepository audit;
    private final SettingsRepository settings;

    public Repositories(Database database, Clock clock) {
        this.database = Objects.requireNonNull(database, "database");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.courses = new CourseRepository(database, clock);
        this.modules = new ModuleRepository(database, clock);
        this.documents = new DocumentRepository(database, clock);
        this.chunks = new ChunkRepository(database, clock);
        this.concepts = new ConceptRepository(database, clock);
        this.items = new LearningItemRepository(database, clock);
        this.reviews = new ReviewStateRepository(database, clock);
        this.sessions = new StudySessionRepository(database, clock);
        this.chats = new ChatRepository(database, clock);
        this.jobs = new JobRepository(database, clock);
        this.flags = new FlagRepository(database, clock);
        this.classInsights = new ClassInsightRepository(database, clock);
        this.audit = new AuditLogRepository(database, clock);
        this.settings = new SettingsRepository(database, clock);
    }

    public Database database() {
        return database;
    }

    public Clock clock() {
        return clock;
    }

    public CourseRepository courses() {
        return courses;
    }

    public ModuleRepository modules() {
        return modules;
    }

    public DocumentRepository documents() {
        return documents;
    }

    public ChunkRepository chunks() {
        return chunks;
    }

    public ConceptRepository concepts() {
        return concepts;
    }

    public LearningItemRepository items() {
        return items;
    }

    public ReviewStateRepository reviews() {
        return reviews;
    }

    public StudySessionRepository sessions() {
        return sessions;
    }

    public ChatRepository chats() {
        return chats;
    }

    public JobRepository jobs() {
        return jobs;
    }

    public FlagRepository flags() {
        return flags;
    }

    public ClassInsightRepository classInsights() {
        return classInsights;
    }

    public AuditLogRepository audit() {
        return audit;
    }

    public SettingsRepository settings() {
        return settings;
    }
}
