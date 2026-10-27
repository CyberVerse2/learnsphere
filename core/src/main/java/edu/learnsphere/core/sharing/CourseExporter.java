package edu.learnsphere.core.sharing;

import edu.learnsphere.core.AppPaths;
import edu.learnsphere.core.db.EmbeddingCodec;
import edu.learnsphere.core.db.Repositories;
import edu.learnsphere.core.error.ConflictException;
import edu.learnsphere.core.error.InvalidInputException;
import edu.learnsphere.core.error.LearnSphereException;
import edu.learnsphere.core.model.Chunk;
import edu.learnsphere.core.model.Concept;
import edu.learnsphere.core.model.Course;
import edu.learnsphere.core.model.CourseModule;
import edu.learnsphere.core.model.CourseVersion;
import edu.learnsphere.core.model.Document;
import edu.learnsphere.core.model.DocumentPage;
import edu.learnsphere.core.model.DocumentStatus;
import edu.learnsphere.core.model.EntityType;
import edu.learnsphere.core.model.LearningItem;
import edu.learnsphere.core.model.Visibility;
import edu.learnsphere.core.security.PasswordHasher;
import edu.learnsphere.core.service.ExportOptions;
import edu.learnsphere.core.service.PackageInfo;
import edu.learnsphere.core.sharing.PackageData.ChunkData;
import edu.learnsphere.core.sharing.PackageData.ConceptData;
import edu.learnsphere.core.sharing.PackageData.CourseFile;
import edu.learnsphere.core.sharing.PackageData.CourseInfo;
import edu.learnsphere.core.sharing.PackageData.DocumentData;
import edu.learnsphere.core.sharing.PackageData.ItemConceptData;
import edu.learnsphere.core.sharing.PackageData.ItemData;
import edu.learnsphere.core.sharing.PackageData.ModuleData;
import edu.learnsphere.core.sharing.PackageData.PageData;
import edu.learnsphere.core.sharing.PackageData.VersionInfo;
import edu.learnsphere.core.util.CancellationToken;
import edu.learnsphere.core.util.Json;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.ToLongFunction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Exports the latest published version of a course created in this profile as a {@code .learnsphere}
 * package: its approved items, modules, concepts and links, its ready documents with their usable pages
 * and passages (with embeddings), and optionally the PDFs. Protected courses are encrypted with a key
 * derived from the code the creator chooses.
 *
 * <p>The course, its version, items, links and document list are read in one short transaction. Pages and
 * passages are then read one document at a time while they are written, outside any transaction, so a
 * large course neither holds the database's write lock nor sits in memory as a whole. A document that was
 * processed again or removed meanwhile fails the export with
 * {@link SharingMessages#COURSE_CHANGED_WHILE_SAVING}. Thread-safe.
 */
public final class CourseExporter {

    private static final Logger LOG = LoggerFactory.getLogger(CourseExporter.class);

    private final Repositories repos;
    private final AppPaths.ProfilePaths paths;
    private final Clock clock;
    private final ToLongFunction<String> limits;

    public CourseExporter(Repositories repos, AppPaths.ProfilePaths paths, Clock clock) {
        this(repos, paths, clock, PackageFormat::plaintextLimit);
    }

    /** An exporter with other content limits per entry name (tests use small ones). */
    CourseExporter(Repositories repos, AppPaths.ProfilePaths paths, Clock clock, ToLongFunction<String> limits) {
        this.repos = Objects.requireNonNull(repos, "repos");
        this.paths = Objects.requireNonNull(paths, "paths");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.limits = Objects.requireNonNull(limits, "limits");
    }

    /**
     * Writes the package to {@code target}, replacing a file there. Wipes the protection code.
     *
     * @throws edu.learnsphere.core.error.NotFoundException for an unknown course
     * @throws ConflictException     for a private, unpublished or imported course, a missing PDF, a course
     *                               too large for a package, or a document that changed while saving
     * @throws InvalidInputException when a protected course has no usable code
     * @throws LearnSphereException  {@link SharingMessages#SAVE_FAILED} when writing fails
     */
    public PackageInfo export(String courseId, Path target, ExportOptions options) {
        Objects.requireNonNull(target, "target");
        ExportOptions opts = options == null ? ExportOptions.standard() : options;
        char[] code = opts.protectionCode();
        try {
            Snapshot snapshot = snapshot(courseId);
            Course course = snapshot.course();
            PackageKey key = null;
            if (course.visibility() == Visibility.PROTECTED) {
                if (!PackageKey.isUsableCode(code)) {
                    throw new InvalidInputException(SharingMessages.PROTECTION_CODE_NEEDED,
                            "Protected course " + courseId + " exported without a usable code");
                }
                key = PackageKey.create(code);
            }
            try {
                PackageManifest manifest = write(snapshot, target, key, opts.includeDocuments());
                repos.audit().append("export_package", EntityType.COURSE, courseId, Json.write(Map.of(
                        "packageId", course.packageId(), "version", manifest.versionNumber(),
                        "protected", manifest.isProtected(), "items", manifest.itemCount(),
                        "documents", manifest.documentCount())));
                long size = Files.size(target);
                return new PackageInfo(manifest.packageId(), manifest.formatVersion(), manifest.title(),
                        manifest.subtitle(), manifest.authorName(), manifest.coverHue(), manifest.versionNumber(),
                        manifest.createdAt(), manifest.moduleCount(), manifest.itemCount(), manifest.documentCount(),
                        size, manifest.isProtected(), course.id(), course.versionNumber(), true);
            } finally {
                if (key != null) {
                    key.close();
                }
            }
        } catch (IOException | UncheckedIOException e) {
            if (PackageWriter.TooLargeException.isCause(e)) {
                throw new ConflictException(SharingMessages.COURSE_TOO_LARGE, "Course " + courseId + " is too large", e);
            }
            throw new LearnSphereException(SharingMessages.SAVE_FAILED, "Export of course " + courseId + " failed", e);
        } finally {
            PasswordHasher.wipe(code);
        }
    }

    private PackageManifest write(Snapshot s, Path target, PackageKey key, boolean includeDocuments) throws IOException {
        Course course = s.course();
        try (PackageWriter writer = PackageWriter.create(target, course.packageId(), s.version().number(), key, limits)) {
            writer.writeJson(PackageFormat.PAGES, (generator, values) -> {
                generator.writeStartObject();
                generator.writeArrayFieldStart("pages");
                for (Document d : s.documents()) {
                    for (DocumentPage page : repos.documents().listUsablePages(d.id())) {
                        values.writeValue(generator, new PageData(d.id(), page.pageNumber(), page.text(),
                                page.source().code(), page.figureNotes(), page.hasImages()));
                    }
                }
                generator.writeEndArray();
                generator.writeEndObject();
            });
            Set<String> chunkIds = new HashSet<>();
            writer.writeJson(PackageFormat.CHUNKS, (generator, values) -> {
                generator.writeStartObject();
                generator.writeArrayFieldStart("chunks");
                for (Document d : s.documents()) {
                    for (Chunk chunk : repos.chunks().listByDocument(d.id())) {
                        values.writeValue(generator, toData(chunk));
                        chunkIds.add(chunk.id());
                    }
                    requireUnchanged(d);
                }
                generator.writeEndArray();
                generator.writeEndObject();
            });
            CourseFile file = courseFile(s, chunkIds, includeDocuments);
            writer.writeJson(PackageFormat.COURSE, file);
            if (includeDocuments) {
                Set<String> files = new LinkedHashSet<>();
                s.documents().forEach(d -> files.add(d.fileSha256()));
                for (String sha : files) {
                    try {
                        writer.writeDocument(sha, paths.pdfFile(sha), CancellationToken.NONE);
                    } catch (NoSuchFileException | PackageWriter.ChangedFileException e) {
                        throw new ConflictException(SharingMessages.PDF_MISSING, "PDF " + sha + " is missing or changed", e);
                    }
                }
            }
            PackageManifest header = new PackageManifest(PackageFormat.FORMAT, PackageFormat.VERSION, course.packageId(),
                    s.version().number(), course.title(), course.subtitle(), course.authorName(), course.coverHue(),
                    clock.millis(), file.modules().size(), file.items().size(), file.documents().size(), null, List.of());
            LOG.debug("Exported course {} version {}: {} items, {} documents, {} passages", course.id(),
                    s.version().number(), file.items().size(), file.documents().size(), chunkIds.size());
            return writer.commit(header);
        }
    }

    /**
     * Fails when a document is no longer ready as it was when the export started (processed again or
     * removed), since its pages and passages may then come from different runs.
     */
    private void requireUnchanged(Document snapshot) {
        Document now = repos.documents().find(snapshot.id()).orElse(null);
        if (now == null || now.status() != DocumentStatus.READY || now.updatedAt() != snapshot.updatedAt()
                || !now.fileSha256().equals(snapshot.fileSha256())) {
            throw new ConflictException(SharingMessages.COURSE_CHANGED_WHILE_SAVING,
                    "Document " + snapshot.id() + " changed during the export");
        }
    }

    /** The course, its latest version, items, links and ready documents, read in one short transaction. */
    private Snapshot snapshot(String courseId) {
        return repos.database().inTransaction(h -> {
            Course course = repos.courses().get(courseId);
            if (!course.isCreatedHere()) {
                throw new ConflictException(SharingMessages.NOT_MADE_HERE, "Course " + courseId + " was imported");
            }
            if (course.visibility() == Visibility.PRIVATE) {
                throw new ConflictException(SharingMessages.PRIVATE_COURSE, "Course " + courseId + " is private");
            }
            CourseVersion version = repos.courses().latestVersion(courseId)
                    .orElseThrow(() -> new ConflictException(SharingMessages.NOT_PUBLISHED,
                            "Course " + courseId + " has no published version"));
            List<LearningItem> items = repos.items().listByIds(repos.courses().versionItemIds(version.id())).stream()
                    .filter(i -> i.courseId().equals(courseId) && i.isApproved())
                    .toList();
            List<Document> documents = repos.documents().listByCourse(courseId).stream()
                    .filter(d -> d.status() == DocumentStatus.READY)
                    .toList();
            return new Snapshot(course, version, repos.modules().listByCourse(courseId),
                    repos.concepts().listByCourse(courseId), items, repos.items().conceptLinks(courseId), documents);
        });
    }

    /** {@code course.json}; item sources point only at passages the package carries. */
    private static CourseFile courseFile(Snapshot s, Set<String> chunkIds, boolean includeDocuments) {
        Course course = s.course();
        Set<String> itemIds = new LinkedHashSet<>();
        List<ItemData> itemData = new ArrayList<>();
        for (LearningItem i : s.items()) {
            itemIds.add(i.id());
            String source = i.sourceChunkId() != null && chunkIds.contains(i.sourceChunkId()) ? i.sourceChunkId() : null;
            itemData.add(new ItemData(i.id(), i.moduleId(), i.type().code(), i.prompt(), i.answer(), i.explanation(),
                    i.distractors(), i.difficulty().value(), source, source == null ? null : i.sourcePage(),
                    i.origin().code(), i.support().code(), i.supportNote(), i.createdAt()));
        }
        List<ItemConceptData> itemConcepts = new ArrayList<>();
        s.links().forEach((itemId, conceptIds) -> {
            if (itemIds.contains(itemId)) {
                conceptIds.forEach(conceptId -> itemConcepts.add(new ItemConceptData(itemId, conceptId)));
            }
        });
        List<DocumentData> documentData = s.documents().stream()
                .map(d -> new DocumentData(d.id(), d.moduleId(), d.title(), d.fileName(), d.fileSha256(), d.byteSize(),
                        d.pageCount(), includeDocuments, d.createdAt()))
                .toList();
        CourseVersion version = s.version();
        return new CourseFile(course.packageId(), version.number(),
                new CourseInfo(course.title(), course.subtitle(), course.description(), course.authorName(),
                        course.visibility().code(), course.coverHue()),
                new VersionInfo(version.number(), version.notes(), version.publishedAt()),
                s.modules().stream().map(m -> new ModuleData(m.id(), m.title(), m.summary(), m.position())).toList(),
                s.concepts().stream().map(c -> new ConceptData(c.id(), c.moduleId(), c.name(), c.description())).toList(),
                itemData, itemConcepts, documentData);
    }

    private static ChunkData toData(Chunk c) {
        byte[] vector = EmbeddingCodec.encode(c.embedding());
        return new ChunkData(c.id(), c.documentId(), c.ordinal(), c.pageStart(), c.pageEnd(), c.sectionTitle(), c.text(),
                c.tokenEstimate(), vector == null ? null : c.embeddingModel(),
                vector == null ? null : Base64.getEncoder().encodeToString(vector));
    }

    private record Snapshot(Course course, CourseVersion version, List<CourseModule> modules, List<Concept> concepts,
                            List<LearningItem> items, Map<String, List<String>> links, List<Document> documents) {
    }
}
