package edu.learnsphere.core.sharing;

import edu.learnsphere.core.db.EmbeddingCodec;
import edu.learnsphere.core.error.InvalidInputException;
import edu.learnsphere.core.model.CodedEnum;
import edu.learnsphere.core.model.Difficulty;
import edu.learnsphere.core.model.ItemOrigin;
import edu.learnsphere.core.model.ItemType;
import edu.learnsphere.core.model.PageSource;
import edu.learnsphere.core.model.SupportStatus;
import edu.learnsphere.core.model.Visibility;
import edu.learnsphere.core.sharing.PackageData.ChunkData;
import edu.learnsphere.core.sharing.PackageData.ConceptData;
import edu.learnsphere.core.sharing.PackageData.CourseFile;
import edu.learnsphere.core.sharing.PackageData.DocumentData;
import edu.learnsphere.core.sharing.PackageData.ItemConceptData;
import edu.learnsphere.core.sharing.PackageData.ItemData;
import edu.learnsphere.core.sharing.PackageData.ModuleData;
import edu.learnsphere.core.sharing.PackageData.PageData;
import edu.learnsphere.core.util.Ids;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * The parsed entries of a package after every cross-check: ids are canonical UUIDs and unique, every
 * reference points inside the package, codes are known, and the counts match the manifest. Anything else
 * is a damaged package. Built with a {@link Builder} as the entries are read; once built it is read-only.
 */
final class PackageContent {

    final PackageManifest manifest;
    final CourseFile course;
    final Visibility visibility;
    /** Modules in position order. */
    final List<ModuleData> modules;
    final Map<String, DocumentData> documents;
    final Map<String, List<PageData>> pagesByDocument;
    final Map<String, List<Passage>> passagesByDocument;
    final Set<String> chunkIds;
    final Map<String, List<String>> conceptsByItem;

    private PackageContent(PackageManifest manifest, CourseFile course, Visibility visibility, List<ModuleData> modules,
                           Map<String, DocumentData> documents, Map<String, List<PageData>> pagesByDocument,
                           Map<String, List<Passage>> passagesByDocument, Set<String> chunkIds,
                           Map<String, List<String>> conceptsByItem) {
        this.manifest = manifest;
        this.course = course;
        this.visibility = visibility;
        this.modules = modules;
        this.documents = documents;
        this.pagesByDocument = pagesByDocument;
        this.passagesByDocument = passagesByDocument;
        this.chunkIds = chunkIds;
        this.conceptsByItem = conceptsByItem;
    }

    static InvalidInputException damaged(String detail) {
        return new InvalidInputException(SharingMessages.COURSE_FILE_DAMAGED, detail);
    }

    static InvalidInputException damaged(String detail, Throwable cause) {
        return new InvalidInputException(SharingMessages.COURSE_FILE_DAMAGED, detail, cause);
    }

    /**
     * Checks {@code course.json} against the manifest and itself.
     *
     * @return the course's visibility
     * @throws InvalidInputException {@link SharingMessages#COURSE_FILE_DAMAGED} when anything is off
     */
    static Visibility checkCourse(PackageManifest manifest, CourseFile course) {
        require(course.course() != null && course.version() != null, "course.json lacks course or version");
        require(manifest.packageId().equals(course.packageId()), "course.json is for another package");
        require(manifest.versionNumber() == course.versionNumber() && course.version().number() == course.versionNumber(),
                "course.json is for another version");
        require(notBlank(course.course().title()), "course.json has no title");
        require(course.course().coverHue() >= 0 && course.course().coverHue() <= 359, "cover hue out of range");
        Visibility visibility = code(Visibility.class, course.course().visibility(), "visibility");
        require(visibility != Visibility.PRIVATE, "a private course in a package");
        require(manifest.isProtected() == (visibility == Visibility.PROTECTED), "protection does not match visibility");
        require(course.modules().size() == manifest.moduleCount(), "module count differs from manifest");
        require(course.items().size() == manifest.itemCount(), "item count differs from manifest");
        require(course.documents().size() == manifest.documentCount(), "document count differs from manifest");
        require(course.documents().size() <= PackageFormat.MAX_DOCUMENTS, "too many documents");

        Set<String> moduleIds = uniqueIds(course.modules().stream().map(ModuleData::id).toList(), "module");
        for (ModuleData module : course.modules()) {
            require(notBlank(module.title()), "module without title");
        }
        Set<String> conceptIds = uniqueIds(course.concepts().stream().map(ConceptData::id).toList(), "concept");
        for (ConceptData concept : course.concepts()) {
            require(notBlank(concept.name()), "concept without name");
            requireRef(concept.moduleId(), moduleIds, "concept module");
        }
        Set<String> itemIds = uniqueIds(course.items().stream().map(ItemData::id).toList(), "item");
        for (ItemData item : course.items()) {
            requireRef(item.moduleId(), moduleIds, "item module");
            code(ItemType.class, item.type(), "item type");
            code(ItemOrigin.class, item.origin(), "item origin");
            code(SupportStatus.class, item.support(), "item support");
            require(item.prompt() != null && item.answer() != null, "item without prompt or answer");
            require(item.difficulty() >= Difficulty.EASY.value() && item.difficulty() <= Difficulty.HARD.value(),
                    "item difficulty out of range");
            require(item.sourceChunkId() == null || Ids.isValid(item.sourceChunkId()), "invalid source passage id");
        }
        Set<String> links = new HashSet<>();
        for (ItemConceptData link : course.itemConcepts()) {
            require(link != null && itemIds.contains(link.itemId()) && conceptIds.contains(link.conceptId()),
                    "item concept link outside the package");
            require(links.add(link.itemId() + "/" + link.conceptId()), "duplicate item concept link");
        }
        uniqueIds(course.documents().stream().map(DocumentData::id).toList(), "document");
        for (DocumentData document : course.documents()) {
            requireRef(document.moduleId(), moduleIds, "document module");
            require(notBlank(document.title()) && document.fileName() != null, "document without title");
            require(PackageFormat.isSha256(document.sha256()), "document without a valid hash");
            require(document.byteSize() >= 0 && document.pageCount() >= 0, "negative document size");
            require(!document.fileIncluded()
                            || manifest.entry(PackageFormat.documentEntry(document.sha256())).isPresent(),
                    "document file missing from the manifest");
        }
        return visibility;
    }

    /**
     * Starts the content of a package from its {@code course.json}; pages and passages are then added one at a
     * time as they are read, so the JSON entries are never held as a whole.
     *
     * @throws InvalidInputException {@link SharingMessages#COURSE_FILE_DAMAGED} when the course is off
     */
    static Builder builder(PackageManifest manifest, CourseFile course) {
        return new Builder(manifest, course, checkCourse(manifest, course));
    }

    /**
     * A passage of the package with its vector decoded (null when the package carries none), so the base64
     * text of the vectors is never kept.
     */
    record Passage(String id, String documentId, int ordinal, int pageStart, int pageEnd, String sectionTitle,
                   String text, int tokenEstimate, String embeddingModel, float[] embedding) {
    }

    /**
     * Collects and checks the pages and passages of a package as they are read. Every element is checked when
     * it is added; {@link #build} checks what needs all of them (duplicates). Not thread-safe; use once.
     */
    static final class Builder {

        private final PackageManifest manifest;
        private final CourseFile course;
        private final Visibility visibility;
        private final Map<String, DocumentData> documents = new LinkedHashMap<>();
        private final Map<String, List<PageData>> pagesByDocument = new LinkedHashMap<>();
        private final Map<String, List<Passage>> passagesByDocument = new LinkedHashMap<>();
        private final Set<String> chunkIds = new HashSet<>();
        private final Map<String, String> modelNames = new HashMap<>();
        private boolean built;

        private Builder(PackageManifest manifest, CourseFile course, Visibility visibility) {
            this.manifest = manifest;
            this.course = course;
            this.visibility = visibility;
            course.documents().stream()
                    .sorted(Comparator.comparingLong(DocumentData::createdAt).thenComparing(DocumentData::id))
                    .forEach(d -> documents.put(d.id(), d));
        }

        /** Adds one page of {@code pages.json}. */
        void page(PageData page) {
            require(page != null && documents.containsKey(page.documentId()), "page of a document outside the package");
            require(page.pageNumber() >= 1, "page number below 1");
            require(page.text() != null, "page without text");
            code(PageSource.class, page.source(), "page source");
            pagesByDocument.computeIfAbsent(page.documentId(), k -> new ArrayList<>()).add(page);
        }

        /** Adds one passage of {@code chunks.json}, decoding its vector. */
        void chunk(ChunkData chunk) {
            require(chunk != null && Ids.isValid(chunk.id()), "invalid passage id");
            require(chunkIds.add(chunk.id()), "duplicate passage id");
            require(documents.containsKey(chunk.documentId()), "passage of a document outside the package");
            require(chunk.ordinal() >= 0, "negative passage ordinal");
            require(chunk.pageStart() >= 1 && chunk.pageEnd() >= chunk.pageStart(), "invalid passage pages");
            require(chunk.text() != null, "passage without text");
            float[] vector = null;
            if (chunk.embedding() != null) {
                try {
                    vector = EmbeddingCodec.decode(Base64.getDecoder().decode(chunk.embedding()));
                } catch (IllegalArgumentException e) {
                    throw damaged("Invalid vector for passage " + chunk.id(), e);
                }
            }
            String model = chunk.embeddingModel() == null ? null
                    : modelNames.computeIfAbsent(chunk.embeddingModel(), m -> m);
            passagesByDocument.computeIfAbsent(chunk.documentId(), k -> new ArrayList<>())
                    .add(new Passage(chunk.id(), chunk.documentId(), chunk.ordinal(), chunk.pageStart(), chunk.pageEnd(),
                            chunk.sectionTitle(), chunk.text(), chunk.tokenEstimate(), model, vector));
        }

        /**
         * The checked content.
         *
         * @throws InvalidInputException {@link SharingMessages#COURSE_FILE_DAMAGED} for duplicate pages or
         *                               passage ordinals
         */
        PackageContent build() {
            if (built) {
                throw new IllegalStateException("Content already built");
            }
            built = true;
            for (List<PageData> pages : pagesByDocument.values()) {
                pages.sort(Comparator.comparingInt(PageData::pageNumber));
                for (int i = 1; i < pages.size(); i++) {
                    require(pages.get(i).pageNumber() != pages.get(i - 1).pageNumber(), "duplicate page");
                }
            }
            for (List<Passage> passages : passagesByDocument.values()) {
                passages.sort(Comparator.comparingInt(Passage::ordinal));
                for (int i = 1; i < passages.size(); i++) {
                    require(passages.get(i).ordinal() != passages.get(i - 1).ordinal(), "duplicate passage ordinal");
                }
            }
            Map<String, List<String>> conceptsByItem = new LinkedHashMap<>();
            for (ItemConceptData link : course.itemConcepts()) {
                conceptsByItem.computeIfAbsent(link.itemId(), k -> new ArrayList<>()).add(link.conceptId());
            }
            List<ModuleData> modules = course.modules().stream()
                    .sorted(Comparator.comparingInt(ModuleData::position).thenComparing(ModuleData::id))
                    .toList();
            return new PackageContent(manifest, course, visibility, modules, documents, pagesByDocument,
                    passagesByDocument, Set.copyOf(chunkIds), conceptsByItem);
        }
    }

    /** The items' source passage, or null when the package does not carry that passage. */
    String sourceChunkOf(ItemData item) {
        return item.sourceChunkId() != null && chunkIds.contains(item.sourceChunkId()) ? item.sourceChunkId() : null;
    }

    List<PageData> pages(String documentId) {
        return pagesByDocument.getOrDefault(documentId, List.of());
    }

    List<Passage> passages(String documentId) {
        return passagesByDocument.getOrDefault(documentId, List.of());
    }

    private static Set<String> uniqueIds(Collection<String> ids, String what) {
        Set<String> seen = new HashSet<>();
        for (String id : ids) {
            require(Ids.isValid(id), "invalid " + what + " id");
            require(seen.add(id), "duplicate " + what + " id");
        }
        return seen;
    }

    private static void requireRef(String id, Set<String> known, String what) {
        require(id == null || known.contains(id), what + " outside the package");
    }

    private static <E extends Enum<E> & CodedEnum> E code(Class<E> type, String code, String what) {
        try {
            return CodedEnum.fromCode(type, code);
        } catch (IllegalArgumentException e) {
            throw damaged("unknown " + what + ": " + code);
        }
    }

    private static boolean notBlank(String value) {
        return value != null && !value.isBlank();
    }

    private static void require(boolean condition, String detail) {
        if (!condition) {
            throw damaged(Objects.requireNonNull(detail));
        }
    }
}
