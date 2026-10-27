# LearnSphere architecture

LearnSphere is an offline desktop study app for macOS (Apple Silicon) and Windows (x64), written in Java. Creators turn PDFs into courses; learners ask questions answered only from those PDFs (with page citations), practise with quizzes and flashcards, and get spaced reviews scheduled from their own results. Every AI feature runs on the user's machine through bundled llama.cpp servers and open-weight models. Nothing leaves the computer.

## Stack

| Concern | Choice |
|---|---|
| Language / build | Java 25 (Gradle toolchain, auto-provisioned), Gradle 9.8 with Kotlin DSL and a version catalog (`gradle/libs.versions.toml`) |
| UI | JavaFX 27 (the release AtlantaFX 3.0.0 is built against), AtlantaFX 3.0.0 as the base theme with LearnSphere's own stylesheet on top, Ikonli 12.4 (Feather icons), bundled Inter and Newsreader fonts |
| Storage | SQLite (`org.xerial:sqlite-jdbc` 3.53.x) per profile, JDBI 3 for data access, numbered SQL migrations run by our own `Migrations` class |
| Search | Apache Lucene 10.5: BM25 keyword search plus HNSW vector search, merged with reciprocal rank fusion |
| PDF | Apache PDFBox 3.0.8 for text, layout, image detection and page rendering, with the ImageIO decoders for JBIG2 (`jbig2-imageio`) and JPEG 2000 (`jai-imageio-jpeg2000`) page images that scanned PDFs use |
| AI runtime | llama.cpp `llama-server` binaries, one process per model, OpenAI-compatible HTTP on `127.0.0.1` with a random per-launch API key |
| Models | Chat + vision: Qwen3.5 4B Instruct (Q4_K_M GGUF + F16 vision projector), optional Qwen3.5 9B on machines with 16 GB RAM or more. Embeddings: Qwen3-Embedding-0.6B (Q8_0, 1024 dims). Reranking: Qwen3-Reranker-0.6B (Q8_0) |
| JSON / Markdown | Jackson 2.22, commonmark-java 0.31 |
| Logging | SLF4J 2.0 + Logback 1.6: the app logs INFO and above to the console and `<app home>/logs/learnsphere.log` (`app/src/main/resources/logback.xml`, rolled daily and at 10 MB, 14 days kept); tests log WARN and above (`logback-test.xml` in core's test fixtures) |
| Tests | JUnit 6, AssertJ 3.27, Mockito 5, TestFX 4.0.18 on JavaFX's headless platform |
| Packaging | `jpackage` via Gradle tasks in `app/packaging.gradle.kts`: signed and notarized `.dmg` on macOS, `.msi` on Windows |

## Repository layout

```
learnsphere/
  settings.gradle.kts            all modules declared here
  build.gradle.kts               shared conventions (toolchain, tests, encoding)
  gradle/libs.versions.toml      every dependency version lives here
  core/      edu.learnsphere.core       domain, database, services, SPI interfaces
  pdf/       edu.learnsphere.pdf        PDF inspection, extraction, chunking, rendering, passage location
  search/    edu.learnsphere.search     Lucene index and hybrid search
  ai/        edu.learnsphere.ai         llama-server lifecycle, model clients, prompts, fakes
  app/       edu.learnsphere.app        JavaFX application (packaging.gradle.kts: installers)
  testbench/ edu.learnsphere.testbench  model evaluation harness (not shipped)
  packaging/                            macOS entitlements for the signed app
  runtime/                              (git-ignored) llama.cpp binaries and model files for development
  docs/
```

Module dependencies point one way: `pdf`, `search` and `ai` depend on `core`; `app` and `testbench` depend on everything. `core` depends on no other module. Core declares the interfaces it needs in `edu.learnsphere.core.spi`; the other modules implement them; `app` collects the implementations in one `Capabilities` record and hands it to `ProfileServices.create` (see [Service layer](#service-layer-what-the-ui-calls)).

Core also publishes test fixtures (`core/src/testFixtures`, package `edu.learnsphere.core.testing`): `TestDb` (a migrated database in a temp folder with data builders such as `enrolledCourse`, `item` and `reviewed`), `MutableClock`, and the shared `logback-test.xml`. Other modules use them with `testImplementation(testFixtures(project(":core")))`.

## Data on disk

```
<app home>/
  profiles.json                  registry: id, display name, avatar hue, optional password hash, created/last-opened
  app-settings.json              app-wide preferences: theme, reduce motion, last profile, onboarding completed
  logs/                          learnsphere.log and llama-server logs (rotated)
  profiles/<profileId>/
    learnsphere.db               SQLite database for this profile
    index/                       Lucene index (rebuildable from the database)
    files/<sha256>.pdf           imported PDFs, content-addressed
    renders/                     cached page images
```

`<app home>` resolves, in order: system property `learnsphere.home`; macOS `~/Library/Application Support/LearnSphere`; Windows `%LOCALAPPDATA%\LearnSphere`; otherwise `$XDG_DATA_HOME/learnsphere` or `~/.local/share/learnsphere`. Tests and the screenshot tour always set `learnsphere.home` to a temporary directory. All paths are built with `Path.of`/`resolve`, never string concatenation. `AppPaths` in core is the only class that knows this layout.

Appearance, the last opened profile and onboarding are app-wide (`AppPreferencesStore`, `app-settings.json`) because the profile picker and onboarding appear before any profile is open; everything else is a per-profile setting in the profile's database (`SettingsService`). `profiles.json` and `app-settings.json` are written to a temporary file and moved into place, so a crash never leaves half a file.

Profiles are folders, so a profile can be backed up or restored as one zip and profiles never see each other's data. A backup holds `profile.json` (the registry entry), a consistent copy of the database made with `VACUUM INTO` (so it works while the profile is open) and `files/`; the index and renders are rebuilt. Restoring adds the profile, with a new id when its id is already in use, and refuses entries that would land outside the profile folder. Passwords (optional) are PBKDF2-HMAC-SHA256 hashes with a per-profile salt, stored in `profiles.json`. Disk encryption is left to FileVault/BitLocker: the search index holds document text, so encrypting only the database would protect nothing.

## Runtime files (llama.cpp and models)

`RuntimeLocator` (ai module) finds the runtime directory, in order: system property `learnsphere.runtime`; environment variable `LEARNSPHERE_RUNTIME`; the packaged app's `runtime/` folder next to the application jars (jpackage `app` directory); the development folder `runtime/` at the repository root (walk up from the working directory until `settings.gradle.kts` is found).

```
runtime/
  llama/macos-arm64/**/llama-server        (and its .dylib files)
  llama/windows-x64/**/llama-server.exe    (and its .dll files)
  models/chat-4b/Qwen3.5-4B-Q4_K_M.gguf
  models/chat-4b/mmproj-F16.gguf
  models/chat-9b/...                        (optional, same layout)
  models/embed/Qwen3-Embedding-0.6B-Q8_0.gguf
  models/rerank/qwen3-reranker-0.6b-q8_0.gguf
```

Model files are located by glob inside each folder (`*.gguf`, vision projector = file starting with `mmproj`), so swapping quantizations needs no code change.

## AI runtime

`LlamaServerManager` starts three `llama-server` processes when a profile opens, each on a free ephemeral port bound to `127.0.0.1` with `--api-key <random>`:

| Role | Key flags |
|---|---|
| chat | `-m <model> --mmproj <projector> --jinja -c 16384 -ngl 99 --parallel 1 -fa on`; thinking disabled per request (`chat_template_kwargs.enable_thinking=false`) |
| embed | `--embedding --pooling last -c 8192 -ub 8192 -ngl 99` |
| rerank | `--reranking -c 8192 -ub 8192 -ngl 99` |

It polls `/health` until ready, restarts a crashed process up to three times with backoff, writes each process's output to `logs/llama-<role>.log`, and stops all processes on exit (shutdown hook plus explicit `close()`). Exact flags are verified against the bundled llama.cpp build; the build's `--help` output is the reference.

`HardwareProbe` reads total RAM and platform; `ModelChoice` defaults to 9B only when 16 GB+ RAM and the 9B files exist, else 4B. The user can change it in Settings.

The chat process serves one request at a time. `ChatGate` (ai module) orders waiting requests by priority: `INTERACTIVE` (a question the user is watching, short-answer grading) before `USER_WAITING` (generation the user started) before `BACKGROUND` (reading scanned pages, describing figures). Every request carries a `CancellationToken`.

Clients use `java.net.http.HttpClient`: `/v1/chat/completions` (streaming via server-sent events, images as base64 data URLs, `response_format` with a JSON schema for structured output), `/v1/embeddings`, `/v1/rerank`.

Qwen3-Embedding queries are prefixed with `Instruct: Given a student's question, retrieve passages from course material that answer it\nQuery: `; documents are embedded as-is. Vectors are L2-normalized.

## Core domain and database

IDs are random UUID strings. Times are epoch milliseconds (`INTEGER`). A `java.time.Clock` is injected wherever time matters. Booleans are `INTEGER` 0/1. Every table has `created_at`; mutable tables also `updated_at`. Foreign keys are on (`PRAGMA foreign_keys=ON`), journal mode WAL, `busy_timeout` 5000.

Migration `V1__initial.sql` creates:

| Table | Columns (beyond id/created_at/updated_at) |
|---|---|
| `schema_version` | `version`, `applied_at` |
| `settings` | `key` PK, `value` |
| `courses` | `title`, `subtitle`, `description`, `author_name`, `origin` (`local`/`imported`), `visibility` (`private`/`shareable`/`protected`), `status` (`draft`/`published`/`archived`), `cover_hue` (0–359), `version_number` (0 = never published), `published_at`, `package_id` (stable id shared by every copy of a course, used to match updates on import), `last_opened_at` |
| `course_versions` | `course_id`, `number`, `notes`, `published_at` |
| `course_version_items` | `version_id`, `item_id` (which approved items a published version contains) |
| `enrolments` | `course_id` UNIQUE, `enrolled_at`, `status` (`active`/`left`) |
| `modules` | `course_id`, `title`, `summary`, `position` |
| `documents` | `course_id`, `module_id` NULL, `title`, `file_name`, `file_sha256`, `byte_size`, `page_count`, `status` (`queued`/`checking`/`extracting`/`reading`/`indexing`/`needs_review`/`ready`/`failed`), `progress` (0–1), `status_detail`, `error_code` |
| `document_pages` | PK (`document_id`, `page_number`), `text`, `source` (`text_layer`/`ai_read`), `reviewed`, `figure_notes`, `has_images` |
| `chunks` | `document_id`, `course_id`, `module_id`, `ordinal`, `page_start`, `page_end`, `section_title`, `text`, `token_estimate`, `embedding` BLOB (float32 LE), `embedding_model` (the embedder's name, so vectors from another embedder are found and redone) |
| `concepts` | `course_id`, `module_id`, `name`, `description` |
| `learning_items` | `course_id`, `module_id`, `type` (`mcq`/`true_false`/`short_answer`/`flashcard`/`cloze`), `prompt`, `answer`, `explanation`, `options_json`, `difficulty` (1–3), `source_chunk_id`, `source_page`, `origin` (`ai`/`manual`), `status` (`draft`/`approved`/`rejected`/`retired`), `support` (`supported`/`unsupported`/`unchecked`), `support_note`, `embedding` BLOB + `embedding_model` of prompt and answer (near-duplicate check; cleared when the text changes) |
| `item_concepts` | PK (`item_id`, `concept_id`) |
| `review_states` | `item_id` PK, `state` (`new`/`learning`/`review`/`relearning`), `stability`, `difficulty`, `due_at`, `last_review_at`, `reps`, `lapses`, `scheduled_days`, `step` (position in the FSRS learning or relearning steps) |
| `study_sessions` | `course_id` NULL, `kind`, `started_at`, `ended_at` |
| `attempts` | `item_id`, `session_id`, `response`, `correct`, `score` (0–1), `rating` (1–4), `confidence` (1–3), `hint_used`, `duration_ms`, `graded_by` (`rule`/`ai`/`self`), `feedback`, `disputed` |
| `chat_threads` | `course_id`, `title` |
| `chat_messages` | `thread_id`, `role` (`user`/`assistant`), `content`, `status` (`complete`/`insufficient`/`error`/`cancelled`), `citations_json` |
| `jobs` | `kind`, `payload_json`, `status` (`queued`/`running`/`done`/`failed`/`cancelled`), `priority`, `attempts`, `progress`, `message` (progress line, or the `UserMessage` text of a failure), `result_json` (what a finished job produced), `subject_id` |
| `flags` | `item_id`, `reason`, `note`, `source` (`self`/`learner_feedback`), `resolved_at` |
| `class_insights` | `course_id`, `learner_label`, `payload_json`, `imported_at` |
| `audit_log` | `action`, `entity_type`, `entity_id`, `detail` |

Indexes cover: modules by (`course_id`, `position`); documents by `course_id` and `status`; chunks by `document_id` and `course_id`; items by (`course_id`, `status`), `module_id`, `source_chunk_id`; review_states by `due_at`; attempts by `item_id` and `created_at`; chat_messages by `thread_id`; jobs by (`status`, `priority`).

Domain types are Java records in `edu.learnsphere.core.model` mirroring these tables, with enums for every status/type column. Repositories (`edu.learnsphere.core.db.*Repository`) are JDBI-backed classes, one per aggregate, used only by services.

**Transactions.** `Database.inTransaction` takes SQLite's write lock at the start (`BEGIN IMMEDIATE`). Read and compute first, then write in one short transaction; never call an SPI implementation (AI requests, PDF reading, embedding, index writes) or wait on anything slow inside one.

**Migrations.** Scripts live in `core/src/main/resources/db/migration` as `V<n>__<name>.sql`. `Migrations` applies each pending script on its own connection: `PRAGMA foreign_keys=OFF` (it only takes effect outside a transaction), `BEGIN`, every statement through a plain JDBC `Statement` (so SQL such as `ESCAPE '\'` reaches SQLite unchanged), the `schema_version` row, then `PRAGMA foreign_key_check`; any violation rolls the script back. `foreign_keys` is switched back on afterwards in every case. That makes SQLite's table-rebuild recipe (create `x_new`, copy, drop, rename, recreate indexes) safe for tables other rows reference.

**Study scope.** Every scheduling query (`ReviewStateRepository.listDue`, `countDue`, `countDueByCourse`, `dueTimesBetween`, `nextDueAfter`, `listApproved`, and `LearningItemRepository.listUnseen`) takes a `StudyScope`. `StudyScope.enrolled()` is "across courses": only courses with an active enrolment that are not archived, so courses the learner left, archived courses and courses created here but not studied never show up in Study next, the dashboard or the progress overview. `course(id)`, `module(courseId, moduleId)` and `courses(ids)` name courses explicitly (a creator trying out their own course).

## Service layer (what the UI calls)

Interfaces live in `edu.learnsphere.core.service`; implementations in `edu.learnsphere.core.service.impl`. Services are thread-safe and blocking; the UI calls them off the FX thread.

`ProfileServices.create(AppPaths, ProfileRegistry, PasswordHasher, Clock, Capabilities)` is the entry point and returns the `ProfileService`. Opening a profile opens and migrates its database, creates its `EventBus`, and builds a `WorkspaceImpl` from the `Capabilities`: the search index opens through the `SearchIndexFactory` (an index that needs a rebuild queues `REBUILD_INDEX`), unfinished jobs resume and the study assistant starts in the background. Closing the workspace stops jobs, the assistant and the index, then the profile service closes the event bus and database. A second overload takes a `WorkspaceFactory` instead of `Capabilities`; the app's demo mode and tests use it. Settings, the study assistant and the job queue are implemented; the other services of `WorkspaceImpl` throw `UnsupportedOperationException` until their pipelines are written. Long work runs as jobs and reports through `EventBus` events. Methods throw `LearnSphereException` subclasses that carry a `UserMessage` (one short sentence a first-time user can act on) plus a technical cause for logs.

| Service | Responsibilities |
|---|---|
| `ProfileService` (app-wide) | list/create/rename/delete profiles, set or clear password, unlock, backup to zip, restore from zip, open a `Workspace` |
| `Workspace` | an opened profile; exposes every per-profile service below; `close()` releases database, index and AI processes |
| `CourseService` | list (filter: all, studying, creating), get, create, update details, set cover hue, set visibility, archive, delete, publish (freezes approved items into a new `course_versions` row), versions, modules (add, rename, summary, reorder, delete), enrol/leave, mark opened |
| `DocumentService` | import PDF into a course/module (copies file, enqueues ingestion), list, get, remove, retry, pages needing review, edit page text, approve pages, render page image (cached PNG), locate a passage on a page (normalized rectangles for highlighting) |
| `AskService` | threads per course, messages, `ask(threadId, question, AnswerListener)` returning a `Cancellable`; listener gets tokens, then a completed `Answer` with `Citation`s, or an insufficient-evidence result, or an error |
| `GenerationService` | extract concepts for a module, generate items (`GenerationRequest`: course, module, item types, count, difficulty) as a job with progress, regenerate one item |
| `ItemService` | list with filters, get, review queue (drafts with their source passage, paged), create manual item, edit, approve, approve many, reject, retire, delete; flags (add, list, resolve) |
| `PracticeService` | start a session (`STUDY_NEXT`, `MODULE_QUIZ`, `FLASHCARDS`, `WEAK_CONCEPTS`, optional course/module, size), submit an answer, rate a flashcard, use a hint, finish (summary), dispute an AI grade |
| `ProgressService` | home dashboard data, the progress overview across every course being studied, course progress (module completion, concept mastery, accuracy by concept, 14-day review forecast, retention trend), creator item statistics |
| `SharingService` | export course package, inspect package, import package as an `IMPORT_PACKAGE` job (new course or update of an existing one keeping progress; the result is read back with `importResult`), export my progress for a course, import class progress (insights), export/import learner feedback (flags) |
| `SettingsService` | typed per-profile settings: new items per day (default 15), desired retention (default 0.90), session size (default 20), describe figures (default on), model choice, evidence threshold |
| `AssistantService` | AI readiness (`STARTING`, `READY`, `UNAVAILABLE`, `FAILED`) with a user-facing message, restart, current model choice, listener |
| `JobQueue` | enqueue, cancel, list active, resume unfinished jobs on startup, read a finished job's result. One worker thread per kind with queued work: kinds run side by side, jobs of one kind one at a time in priority order. A failed job stores only its `UserMessage` text; closing the workspace puts running jobs back in the queue |

`EventBus` delivers typed events (`CourseChanged`, `DocumentChanged`, `ItemsChanged`, `JobProgress`, `AssistantStatusChanged`, `ProgressChanged`, `SettingChanged`) on one background thread, in order. Subscribers must not block it; the app subscribes through `Fx.subscribe`, which only hands each event to the FX thread. `DocumentChanged` carries everything a status pill shows (stage progress, page counter, failure sentence).

## SPI (implemented outside core)

In `edu.learnsphere.core.spi`:

| Interface | Implemented in | Contract |
|---|---|---|
| `PdfInspector` | pdf | inspect a file: page count, encrypted, damaged, has text layer per page, image coverage per page |
| `PdfTextExtractor` | pdf | page-by-page text with line/block structure and font sizes (`PageText`, `TextBlock`) |
| `Chunker` | pdf | heading-aware chunks (~350 token target, ~60 token overlap, never across documents) with `page_start`/`page_end`/`section_title` |
| `PageRenderer` | pdf | render a page to PNG bytes at a scale |
| `PassageLocator` | pdf | rectangles (normalized 0–1 page coordinates) where a passage appears on a page |
| `SearchIndexFactory` | search | open a profile's index folder for the embedder's dimensions and name; an empty, unreadable or mismatched index opens empty with `needsRebuild()` true |
| `SearchIndex` | search | upsert/delete chunks, delete by document/course, hybrid search with course filter, commit, rebuild from a stream (`ChunkRepository.withIndexable`) |
| `Embedder` | ai | embed texts as `QUERY` or `DOCUMENT`, dimensions |
| `Reranker` | ai | relevance scores (0–1) for (query, passage) pairs |
| `PageReader` | ai | transcribe a page image to Markdown text; flags illegible spans |
| `FigureDescriber` | ai | describe figures and diagrams on a page image |
| `GroundedAnswerer` | ai | stream an answer to a question from numbered passages, citing `[n]`, or report that the passages don't contain the answer |
| `ConceptExtractor` | ai | 5–15 key concepts for a module from its passages |
| `ItemGenerator` | ai | quiz/flashcard drafts from passages and concepts as structured JSON |
| `SupportChecker` | ai | whether a passage supports an item's answer |
| `ShortAnswerGrader` | ai | score (0–1), verdict, feedback and missing points for a short answer against the reference |
| `AiRuntime` | ai | start, stop, status, model choice |

`Capabilities` holds one implementation of each of the 16 interfaces (the search slot is the `SearchIndexFactory`, since the index is per profile); `Capabilities.builder().pdf(…).searchIndexFactory(…).ai(…)` fills it, with `AiCapabilities` bundling the ten AI slots. The ai module also ships deterministic fakes (`edu.learnsphere.ai.fake`) for every AI interface; `FakeAi.create().capabilities()` fills the AI slots, and tests, the screenshot tour and demo data use them so they never need model files.

## Pipelines

### Import a PDF (`IngestionService`, background job)

1. **Check:** size ≤ 300 MB, opens, not encrypted, ≤ 2,000 pages. Copy into `files/` by SHA-256 (the same PDF imported twice reuses the file).
2. **Extract:** text layer per page into `document_pages` (`source=text_layer`).
3. **Read:** pages with under 25 characters of text and an image are rendered at 150 DPI and transcribed by `PageReader` (`source=ai_read`, `reviewed=0`). If any exist, the document waits in `needs_review` until a creator approves or edits those pages; unreviewed AI-read pages are never used as evidence.
4. **Figures:** if the setting is on, pages with significant image coverage get `FigureDescriber` notes, appended to their chunks as `Figure: …` lines.
5. **Chunk, embed, index:** `Chunker` → `Embedder` (batches of 16) → store embeddings in `chunks` → `SearchIndex.upsert` → `ready`.

Every stage is idempotent and updates `documents.status`/`progress`, so an interrupted import resumes where it stopped.

### Answer a question (`AskService`)

1. Confirm the course exists in this profile and is either created here or enrolled.
2. Embed the question (`QUERY`).
3. Hybrid search restricted to the course: BM25 top 40 + vector top 40, reciprocal rank fusion (k = 60), keep 24.
4. Rerank the 24; keep up to 6 passages scoring at or above the evidence threshold (setting `evidence.threshold`, default 0.35, tuned by the testbench). None left → insufficient-evidence answer, and the model is not called.
5. `GroundedAnswerer` streams an answer from passages labelled `[1]…[n]` with document title and page.
6. `CitationValidator` keeps only in-range `[n]` markers; an answer with no valid citation, or the model's own "not in the material" signal, becomes the insufficient-evidence result.
7. Store both messages with citations (`documentId`, `title`, `pageStart`, `pageEnd`, `chunkId`, `snippet`).

### Generate learning items (`GenerationService`)

Concepts are extracted per module first. Item generation retrieves passages per concept, asks `ItemGenerator` for structured drafts (type, prompt, answer, explanation, wrong options for multiple choice, difficulty, passage number, concept names), then: maps passage numbers to `source_chunk_id`/`source_page`; runs `SupportChecker` on each draft (`support`); drops near-duplicates (embedding cosine ≥ 0.92 against existing items in the module). Drafts land as `status=draft`; learners only ever see `approved` items in a published version (or, for a creator studying their own unpublished course, approved items).

### Practice and scheduling (`PracticeService`)

- **Scheduling:** FSRS-6 with its published default parameters and desired retention from settings; `review_states` holds stability, difficulty and due date per item.
- **Ratings:** multiple choice / true-false / cloze are rule-graded. Wrong → Again (1). Right with low confidence or a hint → Hard (2). Right with medium confidence → Good (3). Right with high confidence → Easy (4). Flashcards are self-rated Again/Hard/Good/Easy. Short answers are graded by `ShortAnswerGrader` (score < 0.5 → Again, < 0.75 → Hard, else confidence decides Good/Easy), labelled as AI-graded, and can be disputed (a disputed attempt is re-rated Good and recorded).
- **Mastery:** per concept, the mean current retrievability of reviewed items tagged with it, blended with accuracy over the last 10 attempts; concepts with no reviews have no mastery yet.
- **Study next:** due items (lowest retrievability first), then items from weak concepts (mastery < 0.6), then new items in module order up to the daily new-item limit. A session holds 20 items by default.

### Sharing (`SharingService`)

A `.learnsphere` file is a zip:

```
manifest.json        format version, package_id, course version number, title, author, created_at, file hashes
course.json          course, modules, concepts, approved items of the published version, item→concept links
documents/<sha>.pdf  source PDFs
pages.json           reviewed page text per document
chunks.json          chunk text, pages and embeddings (so import needs no re-processing)
```

Private courses cannot be exported. `protected` courses are exported encrypted: AES-256-GCM with a key from PBKDF2-HMAC-SHA256 (310,000 iterations, random salt) over a code the creator chooses; the importer must enter the code. Importing a package whose `package_id` matches an imported course updates it in place: items keep their ids, new items are added, missing items are retired, `review_states` and attempts are untouched, so the learner's progress carries over. A package whose `package_id` matches a course created in this profile is refused (`ConflictException`; `PackageInfo.createdHere()` lets the UI say so before importing). Imports run as `IMPORT_PACKAGE` jobs; the code of a protected package stays in memory only.

Progress export (`.lsprogress`) contains one learner's per-item attempt summaries for a course under a label the learner chooses; creators import several to see class insights. Feedback export (`.lsfeedback`) carries a learner's flags back to the creator.

## Application (JavaFX)

- `Main` points logging at `<app home>/logs`, then `LearnSphereApp` boots `AppContext` (manual constructor wiring, no DI framework), opens the last used profile, loads fonts, applies the theme and reduce-motion preference from `app-settings.json`, and shows `MainWindow`.
- `AppContext` owns the `ProfileService`, the `AppPreferencesStore` and `workspaceProperty()`: the open `Workspace` (null while none is open), changed on the FX thread by `open(profileId, password)` and `closeWorkspace()`.
- Pattern: each screen implements `View` (`root()`, `onShown()`, `dispose()`), built in code (no FXML). Views call services with `UiTasks.run(work, onSuccessFx, onErrorFx)`: the work runs on `UiExecutors` (four platform threads; SQLite lock waits would pin virtual threads), failures are logged and arrive as a `UserMessage`, and the returned `Cancellable` drops the outcome. Events arrive through `Fx.subscribe(bus, type, onFx)`. A view collects both in a `Disposer` and releases them in `dispose()`.
- `Navigator` switches `Destination`s with a short fade/slide transition and keeps back history of destinations. Destinations are records: `Home`, `Library`, `Course(courseId)`, `Ask(courseId, threadId, focus)`, `Study(SessionRequest)`, `Studio(courseId, tab)`, `Progress(courseId or null for the overview)`, `Settings`, `Profiles`, `Onboarding`; each names its `Route` for the sidebar and the full-screen rule (`STUDY`, `ONBOARDING`, `PROFILES` hide the sidebar). Screens register with `navigator.register(Destination.Course.class, d -> new CoursePage(...))`; the replaced view is disposed after the new one is on screen.
- Demo mode (`-Dlearnsphere.demo=true`, used by `runDemo` and the screenshot tour) opens `DemoWorkspace`: real settings, assistant (the fake runtime) and job queue over the demo profile's database, plus fixed sample courses and progress. Its course and progress services answer the calls the screens make and throw `UnsupportedOperationException` for others; add canned answers there as screens need them.
- Packages: `edu.learnsphere.app.ui.<feature>` per route, shared widgets in `edu.learnsphere.app.ui.components`, theme in `edu.learnsphere.app.theme`, demo data and the screenshot tour in `edu.learnsphere.app.dev`.
- The Mac build uses the system menu bar; Windows shows the menu in the window. Shortcuts use `KeyCombination.SHORTCUT_DOWN`.
- `./gradlew :app:run` starts the app against the real runtime; until the pdf, search and ai modules provide their implementations, profiles open only in demo mode. `./gradlew :app:runDemo` starts it with demo data and fake AI. `./gradlew :app:screenshots` renders every screen in light and dark at 1440×900 into `app/build/screenshots/`, after background loads finish.

See `docs/DESIGN.md` for the visual design.

## Testing

- Unit and integration tests per module (`./gradlew test`), with real SQLite and Lucene in temp directories, AI fakes, and core's test fixtures (`TestDb`, `MutableClock`).
- `app` UI tests run TestFX without a display: the test task sets `glass.platform=Headless`, `prism.order=sw` and `testfx.robot=glass`. `MainWindowSmokeTest` boots the main window on the demo profile and walks the shell. The acceptance tests T01–T08 are defined in [ACCEPTANCE.md](ACCEPTANCE.md); each becomes a TestFX test as its screens land.
- `pdf` renders JBIG2 and JPEG 2000 page images from generated fixtures, so a missing decoder fails the build instead of producing blank pages.
- `./gradlew :ai:integrationTest` runs against the real llama-server and models (skipped when `runtime/` is missing).
- `./gradlew :testbench:run` scores retrieval, citation accuracy, refusal on unanswerable questions, page-reading accuracy, JSON validity and speed for each available chat model, writing a Markdown report to `testbench/build/report.md`.

## Packaging

Packaging tasks live in `app/packaging.gradle.kts` (applied from `app/build.gradle.kts`, which keeps the UI and test setup). Both run `jpackage` from the Java 25 toolchain on the jars of `installDist`, with the JVM options `run` uses (`appJvmArgs`), a runtime of Java SE plus `jdk.incubator.vector`, and the `runtime/` folder (llama.cpp build and models) copied into the app when it exists. Output goes to `app/build/package/`.

- `./gradlew :app:packageMac -Plearnsphere.signingIdentity="Developer ID Application: …"` builds a `.dmg` signed with the hardened runtime and `packaging/macos/entitlements.plist`, notarizes it with the `learnsphere-notary` keychain profile and staples the ticket.
- `./gradlew :app:packageWindows` builds an `.msi` on Windows.
- `-Plearnsphere.packageVersion=<x.y.z>` sets the installer version (default 1.0.0; jpackage on macOS refuses versions starting with 0).

Installers must carry the third-party licence notices. `jai-imageio-jpeg2000` is BSD-3-Clause plus the JJ2000 licence, whose terms go into the notices alongside the others.
