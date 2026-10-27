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
import edu.learnsphere.core.sharing.PackageData.PagesFile;
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
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Exports the latest published version of a course created in this profile as a {@code .learnsphere}
 * package: its approved items, modules, concepts and links, its ready documents with their usable pages
 * and passages (with embeddings), and optionally the PDFs. Protected courses are encrypted with a key
 * derived from the code the creator chooses.
 *
 * <p>The database is read in one short transaction (a consistent snapshot); the package is written
 * afterwards. Thread-safe.
 */
public final class CourseExporter {

    private static final Logger LOG = LoggerFactory.getLogger(CourseExporter.class);

    private final Repositories repos;
    private final AppPaths.ProfilePaths paths;
    private final Clock clock;

    public CourseExporter(Repositories repos, AppPaths.ProfilePaths paths, Clock clock) {
        this.repos = Objects.requireNonNull(repos, "repos");
        this.paths = Objects.requireNonNull(paths, "paths");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /**
     * Writes the package to {@code target}, replacing a file there. Wipes the protection code.
     *
     * @throws edu.learnsphere.core.error.NotFoundException for an unknown course
     * @throws ConflictException     for a private, unpublished or imported course, or a missing PDF
     * @throws InvalidInputException when a protected course has no usable code
     * @throws LearnSphereException  {@link SharingMessages#SAVE_FAILED} when writing fails
     */
    public PackageInfo export(String courseId, Path target, ExportOptions options) {
        Objects.requireNonNull(target, "target");
        ExportOptions opts = options == null ? ExportOptions.standard() : options;
        char[] code = opts.protectionCode();
        try {
            Snapshot snapshot = snapshot(courseId, opts.includeDocuments());
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
                PackageManifest manifest = write(snapshot, target, key);
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
            throw new LearnSphereException(SharingMessages.SAVE_FAILED, "Export of course " + courseId + " failed", e);
        } finally {
            PasswordHasher.wipe(code);
        }
    }

    private PackageManifest write(Snapshot s, Path target, PackageKey key) throws IOException {
        Course course = s.course();
        try (PackageWriter writer = PackageWriter.create(target, course.packageId(), s.version().number(), key)) {
            writer.writeJson(PackageFormat.COURSE, s.courseFile());
            writer.writeJson(PackageFormat.PAGES, new PagesFile(s.pages()));
            writer.writeJson(PackageFormat.CHUNKS, (generator, values) -> {
                generator.writeStartObject();
                generator.writeArrayFieldStart("chunks");
                for (Chunk chunk : s.chunks()) {
                    values.writeValue(generator, toData(chunk));
                }
                generator.writeEndArray();
                generator.writeEndObject();
            });
            for (String sha : s.files()) {
                try {
                    writer.writeDocument(sha, paths.pdfFile(sha), CancellationToken.NONE);
                } catch (NoSuchFileException | PackageWriter.ChangedFileException e) {
                    throw new ConflictException(SharingMessages.PDF_MISSING, "PDF " + sha + " is missing or changed", e);
                }
            }
            CourseFile file = s.courseFile();
            PackageManifest header = new PackageManifest(PackageFormat.FORMAT, PackageFormat.VERSION, course.packageId(),
                    s.version().number(), course.title(), course.subtitle(), course.authorName(), course.coverHue(),
                    clock.millis(), file.modules().size(), file.items().size(), file.documents().size(), null, List.of());
            return writer.commit(header);
        }
    }

    private Snapshot snapshot(String courseId, boolean includeDocuments) {
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

            List<CourseModule> modules = repos.modules().listByCourse(courseId);
            List<Concept> concepts = repos.concepts().listByCourse(courseId);
            List<LearningItem> items = repos.items().listByIds(repos.courses().versionItemIds(version.id())).stream()
                    .filter(i -> i.courseId().equals(courseId) && i.isApproved())
                    .toList();
            Map<String, List<String>> links = repos.items().conceptLinks(courseId);

            List<Document> documents = repos.documents().listByCourse(courseId).stream()
                    .filter(d -> d.status() == DocumentStatus.READY)
                    .toList();
            List<PageData> pages = new ArrayList<>();
            List<Chunk> chunks = new ArrayList<>();
            Set<String> files = new LinkedHashSet<>();
            List<DocumentData> documentData = new ArrayList<>();
            for (Document d : documents) {
                for (DocumentPage page : repos.documents().listUsablePages(d.id())) {
                    pages.add(new PageData(d.id(), page.pageNumber(), page.text(), page.source().code(),
                            page.figureNotes(), page.hasImages()));
                }
                chunks.addAll(repos.chunks().listByDocument(d.id()));
                if (includeDocuments) {
                    files.add(d.fileSha256());
                }
                documentData.add(new DocumentData(d.id(), d.moduleId(), d.title(), d.fileName(), d.fileSha256(),
                        d.byteSize(), d.pageCount(), includeDocuments, d.createdAt()));
            }
            Set<String> chunkIds = new LinkedHashSet<>();
            chunks.forEach(c -> chunkIds.add(c.id()));

            Set<String> itemIds = new LinkedHashSet<>();
            List<ItemData> itemData = new ArrayList<>();
            for (LearningItem i : items) {
                itemIds.add(i.id());
                String source = i.sourceChunkId() != null && chunkIds.contains(i.sourceChunkId()) ? i.sourceChunkId() : null;
                itemData.add(new ItemData(i.id(), i.moduleId(), i.type().code(), i.prompt(), i.answer(), i.explanation(),
                        i.distractors(), i.difficulty().value(), source, source == null ? null : i.sourcePage(),
                        i.origin().code(), i.support().code(), i.supportNote(), i.createdAt()));
            }
            List<ItemConceptData> itemConcepts = new ArrayList<>();
            Map<String, List<String>> exportedLinks = new LinkedHashMap<>(links);
            exportedLinks.keySet().retainAll(itemIds);
            exportedLinks.forEach((itemId, conceptIds) ->
                    conceptIds.forEach(conceptId -> itemConcepts.add(new ItemConceptData(itemId, conceptId))));

            CourseFile courseFile = new CourseFile(course.packageId(), version.number(),
                    new CourseInfo(course.title(), course.subtitle(), course.description(), course.authorName(),
                            course.visibility().code(), course.coverHue()),
                    new VersionInfo(version.number(), version.notes(), version.publishedAt()),
                    modules.stream().map(m -> new ModuleData(m.id(), m.title(), m.summary(), m.position())).toList(),
                    concepts.stream().map(c -> new ConceptData(c.id(), c.moduleId(), c.name(), c.description())).toList(),
                    itemData, itemConcepts, documentData);
            LOG.debug("Exporting course {} version {}: {} items, {} documents, {} passages", courseId, version.number(),
                    itemData.size(), documentData.size(), chunks.size());
            return new Snapshot(course, version, courseFile, pages, chunks, List.copyOf(files));
        });
    }

    private static ChunkData toData(Chunk c) {
        byte[] vector = EmbeddingCodec.encode(c.embedding());
        return new ChunkData(c.id(), c.documentId(), c.ordinal(), c.pageStart(), c.pageEnd(), c.sectionTitle(), c.text(),
                c.tokenEstimate(), vector == null ? null : c.embeddingModel(),
                vector == null ? null : Base64.getEncoder().encodeToString(vector));
    }

    private record Snapshot(Course course, CourseVersion version, CourseFile courseFile, List<PageData> pages,
                            List<Chunk> chunks, List<String> files) {
    }
}
