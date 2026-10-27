package edu.learnsphere.core.sharing;

import edu.learnsphere.core.AppPaths;
import edu.learnsphere.core.db.EmbeddingCodec;
import edu.learnsphere.core.db.Repositories;
import edu.learnsphere.core.error.ConflictException;
import edu.learnsphere.core.error.InvalidInputException;
import edu.learnsphere.core.error.LearnSphereException;
import edu.learnsphere.core.error.UserMessage;
import edu.learnsphere.core.events.ChangeType;
import edu.learnsphere.core.events.CourseChanged;
import edu.learnsphere.core.events.DocumentChanged;
import edu.learnsphere.core.events.EventBus;
import edu.learnsphere.core.events.ItemsChanged;
import edu.learnsphere.core.events.ProgressChanged;
import edu.learnsphere.core.model.Chunk;
import edu.learnsphere.core.model.Concept;
import edu.learnsphere.core.model.Course;
import edu.learnsphere.core.model.CourseModule;
import edu.learnsphere.core.model.CourseOrigin;
import edu.learnsphere.core.model.CourseStatus;
import edu.learnsphere.core.model.CourseVersion;
import edu.learnsphere.core.model.Difficulty;
import edu.learnsphere.core.model.Document;
import edu.learnsphere.core.model.DocumentPage;
import edu.learnsphere.core.model.DocumentStatus;
import edu.learnsphere.core.model.EntityType;
import edu.learnsphere.core.model.ItemOrigin;
import edu.learnsphere.core.model.ItemStatus;
import edu.learnsphere.core.model.ItemType;
import edu.learnsphere.core.model.LearningItem;
import edu.learnsphere.core.model.PageSource;
import edu.learnsphere.core.model.SupportStatus;
import edu.learnsphere.core.service.ImportOutcome;
import edu.learnsphere.core.service.ImportResult;
import edu.learnsphere.core.service.JobContext;
import edu.learnsphere.core.service.PackageInfo;
import edu.learnsphere.core.sharing.PackageData.ChunkData;
import edu.learnsphere.core.sharing.PackageData.ConceptData;
import edu.learnsphere.core.sharing.PackageData.CourseFile;
import edu.learnsphere.core.sharing.PackageData.CourseInfo;
import edu.learnsphere.core.sharing.PackageData.DocumentData;
import edu.learnsphere.core.sharing.PackageData.ItemData;
import edu.learnsphere.core.sharing.PackageData.ModuleData;
import edu.learnsphere.core.sharing.PackageData.PageData;
import edu.learnsphere.core.sharing.SharingQueries.ChunkKey;
import edu.learnsphere.core.sharing.SharingQueries.Table;
import edu.learnsphere.core.spi.EmbedKind;
import edu.learnsphere.core.spi.Embedder;
import edu.learnsphere.core.spi.IndexedChunk;
import edu.learnsphere.core.spi.SearchIndex;
import edu.learnsphere.core.util.CancellationToken;
import edu.learnsphere.core.util.Ids;
import edu.learnsphere.core.util.Json;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Imports course packages: {@link #inspect} and {@link #check} before the job is queued, {@link #run} as
 * the {@code IMPORT_PACKAGE} job.
 *
 * <p>A run reads and verifies every entry, copies the PDFs (streamed and hash-checked, into the profile's
 * content-addressed {@code files/}), embeds only passages whose stored vectors do not fit this profile's
 * search, then writes the course in one transaction and finally updates the search index. A new package
 * becomes an imported, enrolled course; a package whose {@code package_id} matches an imported course
 * updates it in place: items keep their ids, new ones are added, missing ones are retired, review states
 * and attempts are untouched, and only documents whose content changed are replaced and re-indexed.
 *
 * <p>The run is idempotent: an interrupted run starts again from the top, reuses copied PDFs, finds the
 * database already up to date when the transaction had committed, and then re-indexes the whole course
 * (a second attempt cannot know how far indexing got). Thread-safe; the job queue runs one import at a time.
 */
public final class CourseImporter {

    private static final Logger LOG = LoggerFactory.getLogger(CourseImporter.class);

    private static final int EMBED_BATCH = 16;
    private static final int INDEX_BATCH = 256;
    /** Free space kept beyond the PDFs being copied. */
    private static final long SPACE_MARGIN = 64L * 1024 * 1024;

    private static final double CHECKED = 0.05;
    private static final double COPIED = 0.6;
    private static final double PREPARED = 0.8;
    private static final double STORED = 0.85;

    private final Repositories repos;
    private final AppPaths.ProfilePaths paths;
    private final EventBus events;
    private final Clock clock;
    private final SearchIndex index;
    private final Embedder embedder;
    private final SharingQueries queries;

    public CourseImporter(Repositories repos, AppPaths.ProfilePaths paths, EventBus events, Clock clock, SearchIndex index,
                          Embedder embedder) {
        this.repos = Objects.requireNonNull(repos, "repos");
        this.paths = Objects.requireNonNull(paths, "paths");
        this.events = Objects.requireNonNull(events, "events");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.index = Objects.requireNonNull(index, "index");
        this.embedder = Objects.requireNonNull(embedder, "embedder");
        this.queries = new SharingQueries(repos.database());
    }

    /** A package that passed {@link #check}, with the key of a protected one (the caller closes it). */
    public record Checked(PackageInfo info, PackageKey key) {
    }

    /** What a package holds and how it relates to this profile; works without the code. */
    public PackageInfo inspect(Path file) {
        try (PackageReader reader = PackageReader.open(file)) {
            return info(reader);
        }
    }

    /**
     * Everything that can be checked before the import job: the manifest, whether the package may be
     * imported here, the code, and the integrity of the JSON entries. Does not wipe {@code code}.
     *
     * @throws InvalidInputException for a file that is not a valid package, or a missing or wrong code
     * @throws ConflictException     for a course created here, an older version than the installed one
     *                               (unless {@code allowOlder}), or ids that belong to another course
     */
    public Checked check(Path file, char[] code, boolean allowOlder) {
        try (PackageReader reader = PackageReader.open(file)) {
            PackageInfo info = info(reader);
            requireImportable(info, allowOlder);
            PackageKey key = reader.unlock(code);
            try {
                CourseFile course = reader.readCourse(key);
                PackageContent.checkCourse(reader.manifest(), course);
                reader.verify(PackageFormat.PAGES, key);
                reader.verify(PackageFormat.CHUNKS, key);
                queries.requireNoClash(info.existingCourseId(), courseIds(course));
                return new Checked(info, key);
            } catch (RuntimeException e) {
                if (key != null) {
                    key.close();
                }
                throw e;
            }
        }
    }

    /**
     * Imports the package as the running job: reports progress, honours cancellation until the database
     * commit, and returns what it did.
     *
     * @param key the key of a protected package (from {@link #check}), or null
     */
    public ImportResult run(Path file, PackageKey key, boolean allowOlder, JobContext job) {
        Progress progress = new Progress(job);
        CancellationToken token = job.token();
        progress.report(0, "Checking the course…");
        List<String> copied = new ArrayList<>();
        try (PackageReader reader = PackageReader.open(file)) {
            PackageManifest manifest = reader.manifest();
            if (manifest.isProtected() && key == null) {
                // A run interrupted by closing LearnSphere may have stored the course but not finished
                // indexing it; the stored passages are enough to finish that without the code.
                repos.courses().findByPackageId(manifest.packageId())
                        .filter(c -> !c.isCreatedHere())
                        .ifPresent(c -> reindexCourse(c.id(), progress));
                throw new InvalidInputException(SharingMessages.IMPORT_INTERRUPTED,
                        "No key in memory for protected package " + manifest.packageId());
            }
            requireImportable(info(reader), allowOlder);
            PackageContent content = PackageContent.of(manifest, reader.readCourse(key), reader.readPages(key),
                    reader.readChunks(key));
            token.throwIfCancelled();

            Optional<Course> existing = repos.courses().findByPackageId(manifest.packageId());
            String existingId = existing.map(Course::id).orElse(null);
            queries.requireNoClash(existingId, allIds(content));
            String courseId = existingId != null ? existingId : Ids.newId();
            Map<String, String> previousSha = previousShas(existingId);
            Set<String> changed = changedDocuments(existingId, content);
            progress.report(CHECKED, "Checking the course…");

            copyDocuments(reader, key, content, token, progress, copied);
            Map<String, List<Chunk>> passages = preparePassages(content, courseId, changed, token, progress);
            token.throwIfCancelled();

            progress.report(PREPARED, existing.isPresent() ? "Updating the course…" : "Adding the course to your library…");
            Applied applied = apply(content, courseId, existingId, passages, changed, allowOlder);
            LOG.info("Imported package {} version {} as course {}: {}", manifest.packageId(), manifest.versionNumber(),
                    courseId, applied.result());

            progress.report(STORED, "Finishing up…");
            updateIndex(applied, content, passages, job.job().attempts() > 1, progress);
            cleanUp(applied, previousSha);
            publish(applied);
            progress.report(1, "Finishing up…");
            return applied.result();
        } finally {
            removeUnusedCopies(copied);
        }
    }

    // ---- checks -------------------------------------------------------------------------------------

    private PackageInfo info(PackageReader reader) {
        PackageManifest m = reader.manifest();
        Optional<Course> existing = repos.courses().findByPackageId(m.packageId());
        return new PackageInfo(m.packageId(), m.formatVersion(), m.title(), m.subtitle(), m.authorName(), m.coverHue(),
                m.versionNumber(), m.createdAt(), m.moduleCount(), m.itemCount(), m.documentCount(), reader.byteSize(),
                m.isProtected(), existing.map(Course::id).orElse(null), existing.map(Course::versionNumber).orElse(null),
                existing.map(Course::isCreatedHere).orElse(false));
    }

    private static void requireImportable(PackageInfo info, boolean allowOlder) {
        requireImportable(info.packageId(), info.versionNumber(), info.existingCourseId(), info.installedVersion(),
                info.createdHere(), allowOlder);
    }

    private static void requireImportable(String packageId, int versionNumber, String courseId, Integer installedVersion,
                                          boolean createdHere, boolean allowOlder) {
        if (createdHere) {
            throw new ConflictException(SharingMessages.COURSE_MADE_HERE,
                    "Package " + packageId + " belongs to course " + courseId + " created here");
        }
        if (!allowOlder && installedVersion != null && versionNumber < installedVersion) {
            throw new ConflictException(SharingMessages.NEWER_VERSION_INSTALLED, "Package version " + versionNumber
                    + " is older than installed version " + installedVersion);
        }
    }

    private static Map<Table, List<String>> courseIds(CourseFile course) {
        Map<Table, List<String>> ids = new EnumMap<>(Table.class);
        ids.put(Table.MODULES, course.modules().stream().map(ModuleData::id).toList());
        ids.put(Table.CONCEPTS, course.concepts().stream().map(ConceptData::id).toList());
        ids.put(Table.ITEMS, course.items().stream().map(ItemData::id).toList());
        ids.put(Table.DOCUMENTS, course.documents().stream().map(DocumentData::id).toList());
        return ids;
    }

    private static Map<Table, List<String>> allIds(PackageContent content) {
        Map<Table, List<String>> ids = courseIds(content.course);
        ids.put(Table.CHUNKS, List.copyOf(content.chunkIds));
        return ids;
    }

    // ---- documents ----------------------------------------------------------------------------------

    private Map<String, String> previousShas(String courseId) {
        if (courseId == null) {
            return Map.of();
        }
        return repos.documents().listByCourse(courseId).stream()
                .collect(Collectors.toMap(Document::id, Document::fileSha256));
    }

    /** Documents of the package that are new or differ from the stored ones (all of them for a new course). */
    private Set<String> changedDocuments(String courseId, PackageContent content) {
        if (courseId == null) {
            return Set.copyOf(content.documents.keySet());
        }
        Map<String, Document> local = repos.documents().listByCourse(courseId).stream()
                .collect(Collectors.toMap(Document::id, Function.identity()));
        Set<String> changed = new LinkedHashSet<>();
        for (DocumentData d : content.documents.values()) {
            Document stored = local.get(d.id());
            if (stored == null || stored.status() != DocumentStatus.READY
                    || !fingerprint(d, content).equals(fingerprint(stored))) {
                changed.add(d.id());
            }
        }
        return changed;
    }

    private static String fingerprint(DocumentData d, PackageContent content) {
        DocumentFingerprint f = DocumentFingerprint.of(d.id(), d.moduleId(), d.title(), d.fileName(), d.sha256(),
                d.byteSize(), d.pageCount(), d.createdAt());
        for (PageData p : content.pages(d.id())) {
            f.page(p.pageNumber(), p.source(), p.text(), p.figureNotes(), p.hasImages());
        }
        for (ChunkData c : content.chunks(d.id())) {
            f.chunk(new ChunkKey(c.id(), c.ordinal(), c.pageStart(), c.pageEnd(), c.sectionTitle(), c.text(),
                    c.tokenEstimate(), true));
        }
        return f.finish();
    }

    private String fingerprint(Document d) {
        DocumentFingerprint f = DocumentFingerprint.of(d.id(), d.moduleId(), d.title(), d.fileName(), d.fileSha256(),
                d.byteSize(), d.pageCount(), d.createdAt());
        for (DocumentPage p : repos.documents().listPages(d.id())) {
            f.page(p.pageNumber(), p.source().code(), p.text(), p.figureNotes(), p.hasImages());
        }
        for (ChunkKey c : queries.chunkKeys(d.id(), index.embeddingModel(), index.dimensions())) {
            f.chunk(c);
        }
        return f.finish();
    }

    /** Copies (or, when already stored, only verifies) every PDF the package carries. */
    private void copyDocuments(PackageReader reader, PackageKey key, PackageContent content, CancellationToken token,
                               Progress progress, List<String> copied) {
        Map<String, Long> files = new LinkedHashMap<>();
        for (DocumentData d : content.documents.values()) {
            if (d.fileIncluded()) {
                files.putIfAbsent(d.sha256(), content.manifest.entry(PackageFormat.documentEntry(d.sha256()))
                        .orElseThrow().size());
            }
        }
        if (files.isEmpty()) {
            return;
        }
        long total = Math.max(1, files.values().stream().mapToLong(Long::longValue).sum());
        requireSpace(files);
        long[] done = {0};
        int number = 0;
        for (Map.Entry<String, Long> file : files.entrySet()) {
            number++;
            String message = "Copying PDFs… " + number + " of " + files.size();
            progress.report(CHECKED + (COPIED - CHECKED) * done[0] / total, message);
            boolean created = reader.copyDocument(file.getKey(), key, paths.pdfFile(file.getKey()), token, bytes -> {
                done[0] += bytes;
                progress.report(CHECKED + (COPIED - CHECKED) * Math.min(1.0, (double) done[0] / total), message);
            });
            if (created) {
                copied.add(file.getKey());
            }
        }
    }

    private void requireSpace(Map<String, Long> files) {
        long needed = files.entrySet().stream()
                .filter(e -> !Files.exists(paths.pdfFile(e.getKey())))
                .mapToLong(Map.Entry::getValue)
                .sum();
        if (needed == 0) {
            return;
        }
        try {
            long free = Files.getFileStore(paths.filesDir()).getUsableSpace();
            if (free < needed + SPACE_MARGIN) {
                throw new LearnSphereException(UserMessage.DISK_FULL, "Need " + needed + " bytes, " + free + " free");
            }
        } catch (IOException e) {
            LOG.debug("Free space unknown for {}", paths.filesDir(), e);
        }
    }

    /** Removes PDFs this run copied that no document uses (the run failed before or without storing them). */
    private void removeUnusedCopies(List<String> copied) {
        for (String sha : copied) {
            try {
                if (repos.documents().countBySha(sha) == 0) {
                    Files.deleteIfExists(paths.pdfFile(sha));
                }
            } catch (IOException | RuntimeException e) {
                LOG.warn("Could not remove unused PDF {}", sha, e);
            }
        }
    }

    // ---- passages -----------------------------------------------------------------------------------

    /**
     * The passages of changed documents as rows of {@code courseId}. Stored vectors are used as they are when
     * they come from this profile's embedder; any other passage is embedded here.
     */
    private Map<String, List<Chunk>> preparePassages(PackageContent content, String courseId, Set<String> changed,
                                                     CancellationToken token, Progress progress) {
        long now = clock.millis();
        Map<String, List<Chunk>> byDocument = new LinkedHashMap<>();
        List<Chunk> missing = new ArrayList<>();
        for (String documentId : changed) {
            DocumentData d = content.documents.get(documentId);
            List<Chunk> chunks = new ArrayList<>();
            for (ChunkData c : content.chunks(documentId)) {
                float[] vector = decode(c);
                boolean usable = vector != null && index.embeddingModel().equals(c.embeddingModel())
                        && vector.length == index.dimensions();
                Chunk chunk = new Chunk(c.id(), documentId, courseId, d.moduleId(), c.ordinal(), c.pageStart(),
                        c.pageEnd(), c.sectionTitle(), c.text(), c.tokenEstimate(), usable ? vector : null,
                        usable ? c.embeddingModel() : null, now, now);
                chunks.add(chunk);
                if (!usable) {
                    missing.add(chunk);
                }
            }
            byDocument.put(documentId, chunks);
        }
        if (!missing.isEmpty()) {
            LOG.info("Embedding {} imported passages for {}", missing.size(), index.embeddingModel());
            Map<String, Chunk> embedded = embed(missing, token, progress);
            byDocument.replaceAll((documentId, chunks) ->
                    chunks.stream().map(c -> embedded.getOrDefault(c.id(), c)).toList());
        }
        return byDocument;
    }

    private Map<String, Chunk> embed(List<Chunk> chunks, CancellationToken token, Progress progress) {
        Map<String, Chunk> embedded = new HashMap<>();
        long now = clock.millis();
        for (int from = 0; from < chunks.size(); from += EMBED_BATCH) {
            token.throwIfCancelled();
            List<Chunk> batch = chunks.subList(from, Math.min(chunks.size(), from + EMBED_BATCH));
            progress.report(COPIED + (PREPARED - COPIED) * from / chunks.size(),
                    "Preparing passages… " + from + " of " + chunks.size());
            List<float[]> vectors = embedder.embed(batch.stream().map(Chunk::text).toList(), EmbedKind.DOCUMENT, token);
            if (vectors.size() != batch.size()) {
                throw LearnSphereException.unexpected("Embedder returned " + vectors.size() + " vectors for "
                        + batch.size() + " passages", null);
            }
            for (int i = 0; i < batch.size(); i++) {
                Chunk c = batch.get(i);
                embedded.put(c.id(), c.withEmbedding(vectors.get(i), embedder.modelName(), now));
            }
        }
        return embedded;
    }

    private static float[] decode(ChunkData c) {
        if (c.embedding() == null) {
            return null;
        }
        try {
            return EmbeddingCodec.decode(Base64.getDecoder().decode(c.embedding()));
        } catch (IllegalArgumentException e) {
            throw PackageContent.damaged("Invalid vector for passage " + c.id(), e);
        }
    }

    // ---- the transaction ----------------------------------------------------------------------------

    /** What the transaction did. */
    private record Applied(ImportResult result, boolean created, List<Document> removedDocuments,
                           Set<String> changedDocuments, Map<String, String> documentTitles) {

        String courseId() {
            return result.courseId();
        }
    }

    private record ItemChanges(int added, int updated, int retired) {
    }

    private Applied apply(PackageContent content, String courseId, String plannedExistingId,
                          Map<String, List<Chunk>> passages, Set<String> changed, boolean allowOlder) {
        return repos.database().inTransaction(h -> {
            long now = clock.millis();
            PackageManifest m = content.manifest;
            Optional<Course> existing = repos.courses().findByPackageId(m.packageId());
            if (!Objects.equals(existing.map(Course::id).orElse(null), plannedExistingId)) {
                throw LearnSphereException.unexpected("Course for package " + m.packageId() + " changed during import", null);
            }
            existing.ifPresent(c -> requireImportable(m.packageId(), m.versionNumber(), c.id(), c.versionNumber(),
                    c.isCreatedHere(), allowOlder));

            CourseInfo info = content.course.course();
            Course course = new Course(courseId, info.title().strip(), info.subtitle(), info.description(), info.authorName(),
                    CourseOrigin.IMPORTED, content.visibility,
                    existing.filter(c -> c.status() == CourseStatus.ARCHIVED).isPresent()
                            ? CourseStatus.ARCHIVED : CourseStatus.PUBLISHED,
                    info.coverHue(), m.versionNumber(), content.course.version().publishedAt(), m.packageId(),
                    existing.map(Course::lastOpenedAt).orElse(null), existing.map(Course::createdAt).orElse(now), now);
            if (existing.isEmpty()) {
                repos.courses().insert(course);
            } else {
                repos.courses().update(course);
            }
            int structureChanges = syncModules(courseId, content, now) + syncConcepts(courseId, content, now);
            List<Document> removed = syncDocuments(courseId, content, passages, changed, now);
            ItemChanges items = syncItems(courseId, content, now);
            syncVersion(courseId, content, now);
            repos.courses().enrol(courseId, now);

            boolean current = existing.isPresent() && existing.get().versionNumber() == m.versionNumber()
                    && items.added() == 0 && items.updated() == 0 && items.retired() == 0
                    && removed.isEmpty() && changed.isEmpty() && structureChanges == 0;
            ImportOutcome outcome = existing.isEmpty() ? ImportOutcome.CREATED
                    : current ? ImportOutcome.ALREADY_CURRENT : ImportOutcome.UPDATED;
            ImportResult result = new ImportResult(outcome, courseId, course.title(), m.versionNumber(), items.added(),
                    items.updated(), items.retired());
            repos.audit().append("import_package", EntityType.COURSE, courseId, Json.write(Map.of(
                    "packageId", m.packageId(), "version", m.versionNumber(), "outcome", outcome.name(),
                    "added", items.added(), "updated", items.updated(), "retired", items.retired(),
                    "documentsChanged", changed.size(), "documentsRemoved", removed.size())));
            Map<String, String> titles = new HashMap<>();
            content.documents.values().forEach(d -> titles.put(d.id(), d.title()));
            return new Applied(result, existing.isEmpty(), removed, changed, titles);
        });
    }

    /** Brings the course's modules in line with the package; returns how many changed. */
    private int syncModules(String courseId, PackageContent content, long now) {
        Set<String> wanted = content.modules.stream().map(ModuleData::id).collect(Collectors.toSet());
        int changes = 0;
        for (CourseModule module : repos.modules().listByCourse(courseId)) {
            if (!wanted.contains(module.id())) {
                repos.modules().delete(module.id());
                changes++;
            }
        }
        Map<String, CourseModule> stored = byId(repos.modules().listByCourse(courseId), CourseModule::id);
        int position = 0;
        for (ModuleData d : content.modules) {
            String summary = Objects.requireNonNullElse(d.summary(), "");
            CourseModule current = stored.get(d.id());
            if (current == null) {
                repos.modules().insert(new CourseModule(d.id(), courseId, d.title(), summary, position, now, now));
                changes++;
            } else if (!current.title().equals(d.title()) || !current.summary().equals(summary)
                    || current.position() != position) {
                repos.modules().update(new CourseModule(d.id(), courseId, d.title(), summary, position,
                        current.createdAt(), now));
                changes++;
            }
            position++;
        }
        return changes;
    }

    /** Brings the course's concepts in line with the package; returns how many changed. */
    private int syncConcepts(String courseId, PackageContent content, long now) {
        List<ConceptData> concepts = content.course.concepts();
        Set<String> wanted = concepts.stream().map(ConceptData::id).collect(Collectors.toSet());
        Map<String, Concept> stored = byId(repos.concepts().listByCourse(courseId), Concept::id);
        int changes = 0;
        for (Concept concept : stored.values()) {
            if (!wanted.contains(concept.id())) {
                repos.concepts().delete(concept.id());
                changes++;
            }
        }
        for (ConceptData d : concepts) {
            String description = Objects.requireNonNullElse(d.description(), "");
            Concept current = stored.get(d.id());
            if (current == null) {
                repos.concepts().insert(new Concept(d.id(), courseId, d.moduleId(), d.name(), description, now, now));
                changes++;
            } else if (!Objects.equals(current.moduleId(), d.moduleId())) {
                // Concepts cannot move between modules in place; the item tags are set again below.
                repos.concepts().delete(d.id());
                repos.concepts().insert(new Concept(d.id(), courseId, d.moduleId(), d.name(), description,
                        current.createdAt(), now));
                changes++;
            } else if (!current.name().equals(d.name()) || !current.description().equals(description)) {
                repos.concepts().update(d.id(), d.name(), description);
                changes++;
            }
        }
        return changes;
    }

    /** Replaces changed documents with their pages and passages, removes missing ones; returns those removed. */
    private List<Document> syncDocuments(String courseId, PackageContent content, Map<String, List<Chunk>> passages,
                                         Set<String> changed, long now) {
        Map<String, Document> stored = byId(repos.documents().listByCourse(courseId), Document::id);
        List<Document> removed = new ArrayList<>();
        for (Document document : stored.values()) {
            if (!content.documents.containsKey(document.id())) {
                repos.documents().delete(document.id());
                removed.add(document);
            }
        }
        for (String documentId : changed) {
            DocumentData d = content.documents.get(documentId);
            if (stored.containsKey(documentId)) {
                repos.documents().delete(documentId);
            }
            repos.documents().insert(new Document(d.id(), courseId, d.moduleId(), d.title(), d.fileName(), d.sha256(),
                    d.byteSize(), d.pageCount(), DocumentStatus.READY, 1, null, null, d.createdAt(), now));
            repos.documents().upsertPages(content.pages(documentId).stream()
                    .map(p -> new DocumentPage(documentId, p.pageNumber(), p.text(), PageSource.fromCode(p.source()), true,
                            p.figureNotes(), p.hasImages(), now, now))
                    .toList());
            repos.chunks().insertAll(passages.getOrDefault(documentId, List.of()));
        }
        return removed;
    }

    private ItemChanges syncItems(String courseId, PackageContent content, long now) {
        // Read after the documents changed: replacing passages cleared the items' links to them.
        Map<String, LearningItem> stored = byId(repos.items().listByCourse(courseId), LearningItem::id);
        Map<String, List<String>> storedLinks = repos.items().conceptLinks(courseId);
        List<LearningItem> inserts = new ArrayList<>();
        int updated = 0;
        for (ItemData d : content.course.items()) {
            String source = content.sourceChunkOf(d);
            LearningItem wanted = new LearningItem(d.id(), courseId, d.moduleId(), ItemType.fromCode(d.type()), d.prompt(),
                    d.answer(), d.explanation(), d.distractors(), Difficulty.fromValue(d.difficulty()), source,
                    source == null ? null : d.sourcePage(), ItemOrigin.fromCode(d.origin()), ItemStatus.APPROVED,
                    SupportStatus.fromCode(d.support()), d.supportNote(), d.createdAt(), now);
            LearningItem current = stored.get(d.id());
            if (current == null) {
                inserts.add(wanted);
            } else if (!sameContent(current, wanted)) {
                repos.items().update(wanted);
                updated++;
            }
        }
        repos.items().insertAll(inserts);
        Set<String> wantedIds = content.course.items().stream().map(ItemData::id).collect(Collectors.toSet());
        List<String> retire = stored.values().stream()
                .filter(i -> !wantedIds.contains(i.id()) && i.status() != ItemStatus.RETIRED)
                .map(LearningItem::id)
                .toList();
        int retired = repos.items().setStatus(retire, ItemStatus.RETIRED);
        for (ItemData d : content.course.items()) {
            List<String> wanted = sorted(content.conceptsByItem.getOrDefault(d.id(), List.of()));
            if (!wanted.equals(sorted(storedLinks.getOrDefault(d.id(), List.of())))) {
                repos.items().setConcepts(d.id(), wanted);
            }
        }
        return new ItemChanges(inserts.size(), updated, retired);
    }

    private void syncVersion(String courseId, PackageContent content, long now) {
        int number = content.manifest.versionNumber();
        boolean known = repos.courses().listVersions(courseId).stream().anyMatch(v -> v.number() == number);
        if (!known) {
            CourseVersion version = new CourseVersion(Ids.newId(), courseId, number, content.course.version().notes(),
                    content.course.version().publishedAt(), now);
            repos.courses().insertVersion(version);
            repos.courses().addVersionItems(version.id(), content.course.items().stream().map(ItemData::id).toList());
        }
    }

    private static boolean sameContent(LearningItem a, LearningItem b) {
        return Objects.equals(a.moduleId(), b.moduleId()) && a.type() == b.type() && a.prompt().equals(b.prompt())
                && a.answer().equals(b.answer()) && a.explanation().equals(b.explanation())
                && a.distractors().equals(b.distractors()) && a.difficulty() == b.difficulty()
                && Objects.equals(a.sourceChunkId(), b.sourceChunkId()) && Objects.equals(a.sourcePage(), b.sourcePage())
                && a.origin() == b.origin() && a.status() == b.status() && a.support() == b.support()
                && Objects.equals(a.supportNote(), b.supportNote());
    }

    // ---- after the commit ---------------------------------------------------------------------------

    /**
     * Brings the search index in line: removed and changed documents leave it, changed documents come back
     * with their passages. A repeated attempt re-indexes the whole course. Not cancellable: the course is
     * already stored, and an interrupted run re-indexes everything when it runs again.
     */
    private void updateIndex(Applied applied, PackageContent content, Map<String, List<Chunk>> passages, boolean full,
                             Progress progress) {
        if (full) {
            reindexCourse(applied.courseId(), progress);
            return;
        }
        List<IndexedChunk> upserts = new ArrayList<>();
        applied.removedDocuments().forEach(d -> index.deleteByDocument(d.id()));
        for (String documentId : applied.changedDocuments()) {
            index.deleteByDocument(documentId);
            passages.getOrDefault(documentId, List.of()).stream()
                    .filter(this::searchable)
                    .forEach(c -> upserts.add(indexed(c, applied.documentTitles().get(documentId))));
        }
        upsert(upserts, progress);
        if (!content.chunkIds.isEmpty() && upserts.isEmpty() && !applied.changedDocuments().isEmpty()) {
            LOG.warn("No passages of course {} could be indexed", applied.courseId());
        }
    }

    /** Replaces everything the index holds for a course with its stored, searchable passages. */
    private void reindexCourse(String courseId, Progress progress) {
        index.deleteByCourse(courseId);
        Map<String, String> titles = repos.documents().listByCourse(courseId).stream()
                .filter(d -> d.status() == DocumentStatus.READY)
                .collect(Collectors.toMap(Document::id, Document::title));
        List<IndexedChunk> upserts = new ArrayList<>();
        repos.chunks().listByCourse(courseId).stream()
                .filter(c -> titles.containsKey(c.documentId()) && searchable(c))
                .forEach(c -> upserts.add(indexed(c, titles.get(c.documentId()))));
        upsert(upserts, progress);
    }

    private void upsert(List<IndexedChunk> upserts, Progress progress) {
        for (int from = 0; from < upserts.size(); from += INDEX_BATCH) {
            progress.report(STORED + (1 - STORED) * from / upserts.size(), "Finishing up…");
            index.upsert(upserts.subList(from, Math.min(upserts.size(), from + INDEX_BATCH)));
        }
        index.commit();
    }

    private boolean searchable(Chunk c) {
        return c.embedding() != null && c.embedding().length == index.dimensions()
                && index.embeddingModel().equals(c.embeddingModel());
    }

    private static IndexedChunk indexed(Chunk c, String documentTitle) {
        return new IndexedChunk(c.id(), c.documentId(), c.courseId(), c.moduleId(), documentTitle, c.pageStart(),
                c.pageEnd(), c.sectionTitle(), c.text(), c.embedding());
    }

    /** Deletes PDFs and page images that only removed or replaced documents used. */
    private void cleanUp(Applied applied, Map<String, String> previousSha) {
        Set<String> shas = new LinkedHashSet<>();
        Set<String> renders = new LinkedHashSet<>();
        for (Document d : applied.removedDocuments()) {
            shas.add(d.fileSha256());
            renders.add(d.id());
        }
        for (String documentId : applied.changedDocuments()) {
            String before = previousSha.get(documentId);
            if (before != null && !repos.documents().find(documentId).map(Document::fileSha256).orElse("").equals(before)) {
                shas.add(before);
                renders.add(documentId);
            }
        }
        for (String sha : shas) {
            try {
                if (repos.documents().countBySha(sha) == 0) {
                    Files.deleteIfExists(paths.pdfFile(sha));
                }
            } catch (IOException | RuntimeException e) {
                LOG.warn("Could not remove PDF {}", sha, e);
            }
        }
        for (String documentId : renders) {
            deleteRecursively(paths.rendersDir().resolve(documentId));
        }
    }

    private static void deleteRecursively(Path folder) {
        if (!Files.isDirectory(folder)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(folder)) {
            for (Path path : walk.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        } catch (IOException | RuntimeException e) {
            LOG.warn("Could not remove page images in {}", folder, e);
        }
    }

    private void publish(Applied applied) {
        String courseId = applied.courseId();
        ChangeType type = applied.created() ? ChangeType.CREATED : ChangeType.UPDATED;
        for (Document d : applied.removedDocuments()) {
            events.publish(DocumentChanged.of(d.id(), courseId, d.status(), d.progress(), ChangeType.DELETED));
        }
        for (String documentId : applied.changedDocuments()) {
            events.publish(DocumentChanged.of(documentId, courseId, DocumentStatus.READY, 1, type));
        }
        events.publish(new CourseChanged(courseId, type));
        events.publish(new ItemsChanged(courseId, null, Set.of(), type));
        events.publish(new ProgressChanged(courseId));
    }

    private static <T> Map<String, T> byId(List<T> rows, Function<T, String> id) {
        Map<String, T> map = new LinkedHashMap<>();
        rows.forEach(row -> map.put(id.apply(row), row));
        return map;
    }

    private static List<String> sorted(List<String> ids) {
        return ids.stream().sorted().toList();
    }

    /** Progress reporting that skips updates smaller than one percent of the same line. */
    private static final class Progress {

        private final JobContext job;
        private double last = -1;
        private String lastMessage;

        Progress(JobContext job) {
            this.job = job;
        }

        void report(double fraction, String message) {
            double clamped = Math.max(0, Math.min(1, fraction));
            if (message.equals(lastMessage) && clamped - last < 0.01 && clamped < 1) {
                return;
            }
            last = clamped;
            lastMessage = message;
            job.progress(clamped, message);
        }
    }
}
