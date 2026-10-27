-- LearnSphere profile database, version 1.
-- Conventions: ids are UUID text; times are epoch milliseconds; booleans are 0/1; enum columns hold
-- lowercase codes checked against the Java enums in edu.learnsphere.core.model. Tables are STRICT.
-- A child row that cannot outlive its parent is deleted with it (ON DELETE CASCADE); optional
-- references to modules and source passages are cleared instead (ON DELETE SET NULL).

CREATE TABLE IF NOT EXISTS schema_version (
    version    INTEGER PRIMARY KEY,
    name       TEXT    NOT NULL,
    applied_at INTEGER NOT NULL
) STRICT;

CREATE TABLE settings (
    key        TEXT    PRIMARY KEY,
    value      TEXT    NOT NULL,
    created_at INTEGER NOT NULL,
    updated_at INTEGER NOT NULL
) STRICT;

CREATE TABLE courses (
    id             TEXT    PRIMARY KEY,
    title          TEXT    NOT NULL CHECK (length(trim(title)) > 0),
    subtitle       TEXT    NOT NULL DEFAULT '',
    description    TEXT    NOT NULL DEFAULT '',
    author_name    TEXT    NOT NULL DEFAULT '',
    origin         TEXT    NOT NULL CHECK (origin IN ('local', 'imported')),
    visibility     TEXT    NOT NULL CHECK (visibility IN ('private', 'shareable', 'protected')),
    status         TEXT    NOT NULL CHECK (status IN ('draft', 'published', 'archived')),
    cover_hue      INTEGER NOT NULL CHECK (cover_hue BETWEEN 0 AND 359),
    version_number INTEGER NOT NULL DEFAULT 0 CHECK (version_number >= 0),
    published_at   INTEGER,
    package_id     TEXT    NOT NULL,
    last_opened_at INTEGER,
    created_at     INTEGER NOT NULL,
    updated_at     INTEGER NOT NULL
) STRICT;

CREATE INDEX idx_courses_package ON courses (package_id);

CREATE TABLE course_versions (
    id           TEXT    PRIMARY KEY,
    course_id    TEXT    NOT NULL REFERENCES courses (id) ON DELETE CASCADE,
    number       INTEGER NOT NULL CHECK (number >= 1),
    notes        TEXT    NOT NULL DEFAULT '',
    published_at INTEGER NOT NULL,
    created_at   INTEGER NOT NULL,
    UNIQUE (course_id, number)
) STRICT;

CREATE TABLE enrolments (
    id          TEXT    PRIMARY KEY,
    course_id   TEXT    NOT NULL UNIQUE REFERENCES courses (id) ON DELETE CASCADE,
    enrolled_at INTEGER NOT NULL,
    status      TEXT    NOT NULL CHECK (status IN ('active', 'left')),
    created_at  INTEGER NOT NULL,
    updated_at  INTEGER NOT NULL
) STRICT;

CREATE TABLE modules (
    id         TEXT    PRIMARY KEY,
    course_id  TEXT    NOT NULL REFERENCES courses (id) ON DELETE CASCADE,
    title      TEXT    NOT NULL CHECK (length(trim(title)) > 0),
    summary    TEXT    NOT NULL DEFAULT '',
    position   INTEGER NOT NULL,
    created_at INTEGER NOT NULL,
    updated_at INTEGER NOT NULL
) STRICT;

CREATE INDEX idx_modules_course_position ON modules (course_id, position);

CREATE TABLE documents (
    id            TEXT    PRIMARY KEY,
    course_id     TEXT    NOT NULL REFERENCES courses (id) ON DELETE CASCADE,
    module_id     TEXT    REFERENCES modules (id) ON DELETE SET NULL,
    title         TEXT    NOT NULL,
    file_name     TEXT    NOT NULL,
    file_sha256   TEXT    NOT NULL,
    byte_size     INTEGER NOT NULL CHECK (byte_size >= 0),
    page_count    INTEGER NOT NULL DEFAULT 0 CHECK (page_count >= 0),
    status        TEXT    NOT NULL CHECK (status IN ('queued', 'checking', 'extracting', 'reading', 'indexing',
                                                     'needs_review', 'ready', 'failed')),
    progress      REAL    NOT NULL DEFAULT 0 CHECK (progress BETWEEN 0 AND 1),
    status_detail TEXT,
    error_code    TEXT    CHECK (error_code IS NULL OR error_code IN ('too_large', 'too_many_pages', 'damaged',
                                 'encrypted', 'no_text', 'missing_file', 'read_failed', 'prepare_failed',
                                 'disk_full', 'unexpected')),
    created_at    INTEGER NOT NULL,
    updated_at    INTEGER NOT NULL
) STRICT;

CREATE INDEX idx_documents_course ON documents (course_id);
CREATE INDEX idx_documents_status ON documents (status);
CREATE INDEX idx_documents_module ON documents (module_id);
CREATE INDEX idx_documents_sha ON documents (file_sha256);

CREATE TABLE document_pages (
    document_id  TEXT    NOT NULL REFERENCES documents (id) ON DELETE CASCADE,
    page_number  INTEGER NOT NULL CHECK (page_number >= 1),
    text         TEXT    NOT NULL DEFAULT '',
    source       TEXT    NOT NULL CHECK (source IN ('text_layer', 'ai_read')),
    reviewed     INTEGER NOT NULL DEFAULT 0 CHECK (reviewed IN (0, 1)),
    figure_notes TEXT,
    has_images   INTEGER NOT NULL DEFAULT 0 CHECK (has_images IN (0, 1)),
    created_at   INTEGER NOT NULL,
    updated_at   INTEGER NOT NULL,
    PRIMARY KEY (document_id, page_number)
) STRICT;

CREATE TABLE chunks (
    id              TEXT    PRIMARY KEY,
    document_id     TEXT    NOT NULL REFERENCES documents (id) ON DELETE CASCADE,
    course_id       TEXT    NOT NULL REFERENCES courses (id) ON DELETE CASCADE,
    module_id       TEXT    REFERENCES modules (id) ON DELETE SET NULL,
    ordinal         INTEGER NOT NULL CHECK (ordinal >= 0),
    page_start      INTEGER NOT NULL CHECK (page_start >= 1),
    page_end        INTEGER NOT NULL CHECK (page_end >= page_start),
    section_title   TEXT,
    text            TEXT    NOT NULL,
    token_estimate  INTEGER NOT NULL DEFAULT 0,
    embedding       BLOB,
    embedding_model TEXT,
    created_at      INTEGER NOT NULL,
    updated_at      INTEGER NOT NULL,
    UNIQUE (document_id, ordinal)
) STRICT;

CREATE INDEX idx_chunks_document ON chunks (document_id);
CREATE INDEX idx_chunks_course ON chunks (course_id);
CREATE INDEX idx_chunks_module ON chunks (module_id);

CREATE TABLE concepts (
    id          TEXT    PRIMARY KEY,
    course_id   TEXT    NOT NULL REFERENCES courses (id) ON DELETE CASCADE,
    module_id   TEXT    REFERENCES modules (id) ON DELETE SET NULL,
    name        TEXT    NOT NULL CHECK (length(trim(name)) > 0),
    description TEXT    NOT NULL DEFAULT '',
    created_at  INTEGER NOT NULL,
    updated_at  INTEGER NOT NULL
) STRICT;

CREATE INDEX idx_concepts_course ON concepts (course_id);
CREATE INDEX idx_concepts_module ON concepts (module_id);

CREATE TABLE learning_items (
    id              TEXT    PRIMARY KEY,
    course_id       TEXT    NOT NULL REFERENCES courses (id) ON DELETE CASCADE,
    module_id       TEXT    REFERENCES modules (id) ON DELETE SET NULL,
    type            TEXT    NOT NULL CHECK (type IN ('mcq', 'true_false', 'short_answer', 'flashcard', 'cloze')),
    prompt          TEXT    NOT NULL,
    answer          TEXT    NOT NULL,
    explanation     TEXT    NOT NULL DEFAULT '',
    options_json    TEXT    NOT NULL DEFAULT '[]',
    difficulty      INTEGER NOT NULL DEFAULT 2 CHECK (difficulty BETWEEN 1 AND 3),
    source_chunk_id TEXT    REFERENCES chunks (id) ON DELETE SET NULL,
    source_page     INTEGER,
    origin          TEXT    NOT NULL CHECK (origin IN ('ai', 'manual')),
    status          TEXT    NOT NULL CHECK (status IN ('draft', 'approved', 'rejected', 'retired')),
    support         TEXT    NOT NULL DEFAULT 'unchecked' CHECK (support IN ('supported', 'unsupported', 'unchecked')),
    support_note    TEXT,
    created_at      INTEGER NOT NULL,
    updated_at      INTEGER NOT NULL
) STRICT;

CREATE INDEX idx_items_course_status ON learning_items (course_id, status);
CREATE INDEX idx_items_module ON learning_items (module_id);
CREATE INDEX idx_items_source_chunk ON learning_items (source_chunk_id);

CREATE TABLE course_version_items (
    version_id TEXT    NOT NULL REFERENCES course_versions (id) ON DELETE CASCADE,
    item_id    TEXT    NOT NULL REFERENCES learning_items (id) ON DELETE CASCADE,
    created_at INTEGER NOT NULL,
    PRIMARY KEY (version_id, item_id)
) STRICT;

CREATE INDEX idx_version_items_item ON course_version_items (item_id);

CREATE TABLE item_concepts (
    item_id    TEXT    NOT NULL REFERENCES learning_items (id) ON DELETE CASCADE,
    concept_id TEXT    NOT NULL REFERENCES concepts (id) ON DELETE CASCADE,
    created_at INTEGER NOT NULL,
    PRIMARY KEY (item_id, concept_id)
) STRICT;

CREATE INDEX idx_item_concepts_concept ON item_concepts (concept_id);

CREATE TABLE review_states (
    item_id        TEXT    PRIMARY KEY REFERENCES learning_items (id) ON DELETE CASCADE,
    state          TEXT    NOT NULL CHECK (state IN ('new', 'learning', 'review', 'relearning')),
    stability      REAL    NOT NULL DEFAULT 0,
    difficulty     REAL    NOT NULL DEFAULT 0,
    due_at         INTEGER NOT NULL,
    last_review_at INTEGER,
    reps           INTEGER NOT NULL DEFAULT 0 CHECK (reps >= 0),
    lapses         INTEGER NOT NULL DEFAULT 0 CHECK (lapses >= 0),
    scheduled_days INTEGER NOT NULL DEFAULT 0 CHECK (scheduled_days >= 0),
    created_at     INTEGER NOT NULL,
    updated_at     INTEGER NOT NULL
) STRICT;

CREATE INDEX idx_review_states_due ON review_states (due_at);

CREATE TABLE study_sessions (
    id         TEXT    PRIMARY KEY,
    course_id  TEXT    REFERENCES courses (id) ON DELETE CASCADE,
    module_id  TEXT    REFERENCES modules (id) ON DELETE SET NULL,
    kind       TEXT    NOT NULL CHECK (kind IN ('study_next', 'module_quiz', 'flashcards', 'weak_concepts')),
    started_at INTEGER NOT NULL,
    ended_at   INTEGER,
    created_at INTEGER NOT NULL,
    updated_at INTEGER NOT NULL
) STRICT;

CREATE INDEX idx_sessions_course ON study_sessions (course_id);
CREATE INDEX idx_sessions_started ON study_sessions (started_at);

CREATE TABLE attempts (
    id          TEXT    PRIMARY KEY,
    item_id     TEXT    NOT NULL REFERENCES learning_items (id) ON DELETE CASCADE,
    session_id  TEXT    NOT NULL REFERENCES study_sessions (id) ON DELETE CASCADE,
    response    TEXT    NOT NULL DEFAULT '',
    correct     INTEGER NOT NULL CHECK (correct IN (0, 1)),
    score       REAL    NOT NULL CHECK (score BETWEEN 0 AND 1),
    rating      INTEGER NOT NULL CHECK (rating BETWEEN 1 AND 4),
    confidence  INTEGER CHECK (confidence IS NULL OR confidence BETWEEN 1 AND 3),
    hint_used   INTEGER NOT NULL DEFAULT 0 CHECK (hint_used IN (0, 1)),
    duration_ms INTEGER NOT NULL DEFAULT 0 CHECK (duration_ms >= 0),
    graded_by   TEXT    NOT NULL CHECK (graded_by IN ('rule', 'ai', 'self')),
    feedback    TEXT,
    disputed    INTEGER NOT NULL DEFAULT 0 CHECK (disputed IN (0, 1)),
    created_at  INTEGER NOT NULL,
    updated_at  INTEGER NOT NULL
) STRICT;

CREATE INDEX idx_attempts_item ON attempts (item_id);
CREATE INDEX idx_attempts_created ON attempts (created_at);
CREATE INDEX idx_attempts_session ON attempts (session_id);

CREATE TABLE chat_threads (
    id         TEXT    PRIMARY KEY,
    course_id  TEXT    NOT NULL REFERENCES courses (id) ON DELETE CASCADE,
    title      TEXT    NOT NULL,
    created_at INTEGER NOT NULL,
    updated_at INTEGER NOT NULL
) STRICT;

CREATE INDEX idx_chat_threads_course ON chat_threads (course_id);

CREATE TABLE chat_messages (
    id             TEXT    PRIMARY KEY,
    thread_id      TEXT    NOT NULL REFERENCES chat_threads (id) ON DELETE CASCADE,
    role           TEXT    NOT NULL CHECK (role IN ('user', 'assistant')),
    content        TEXT    NOT NULL,
    status         TEXT    NOT NULL CHECK (status IN ('complete', 'insufficient', 'error', 'cancelled')),
    citations_json TEXT    NOT NULL DEFAULT '[]',
    created_at     INTEGER NOT NULL
) STRICT;

CREATE INDEX idx_chat_messages_thread ON chat_messages (thread_id);

CREATE TABLE jobs (
    id           TEXT    PRIMARY KEY,
    kind         TEXT    NOT NULL CHECK (kind IN ('ingest_document', 'extract_concepts', 'generate_items',
                                                  'regenerate_item', 'rebuild_index', 'import_package')),
    payload_json TEXT    NOT NULL DEFAULT '{}',
    status       TEXT    NOT NULL CHECK (status IN ('queued', 'running', 'done', 'failed', 'cancelled')),
    priority     INTEGER NOT NULL DEFAULT 0,
    attempts     INTEGER NOT NULL DEFAULT 0 CHECK (attempts >= 0),
    progress     REAL    NOT NULL DEFAULT 0 CHECK (progress BETWEEN 0 AND 1),
    message      TEXT,
    subject_id   TEXT,
    created_at   INTEGER NOT NULL,
    updated_at   INTEGER NOT NULL
) STRICT;

CREATE INDEX idx_jobs_status_priority ON jobs (status, priority);
CREATE INDEX idx_jobs_subject ON jobs (subject_id);

CREATE TABLE flags (
    id          TEXT    PRIMARY KEY,
    item_id     TEXT    NOT NULL REFERENCES learning_items (id) ON DELETE CASCADE,
    reason      TEXT    NOT NULL CHECK (reason IN ('wrong_answer', 'unclear', 'not_in_material', 'typo', 'other')),
    note        TEXT    NOT NULL DEFAULT '',
    source      TEXT    NOT NULL CHECK (source IN ('self', 'learner_feedback')),
    resolved_at INTEGER,
    created_at  INTEGER NOT NULL,
    updated_at  INTEGER NOT NULL
) STRICT;

CREATE INDEX idx_flags_item ON flags (item_id);

CREATE TABLE class_insights (
    id            TEXT    PRIMARY KEY,
    course_id     TEXT    NOT NULL REFERENCES courses (id) ON DELETE CASCADE,
    learner_label TEXT    NOT NULL,
    payload_json  TEXT    NOT NULL,
    imported_at   INTEGER NOT NULL,
    created_at    INTEGER NOT NULL
) STRICT;

CREATE INDEX idx_class_insights_course ON class_insights (course_id);

CREATE TABLE audit_log (
    id          TEXT    PRIMARY KEY,
    action      TEXT    NOT NULL,
    entity_type TEXT    NOT NULL CHECK (entity_type IN ('profile', 'course', 'course_version', 'module', 'document',
                                                        'concept', 'item', 'flag', 'setting', 'package')),
    entity_id   TEXT,
    detail      TEXT,
    created_at  INTEGER NOT NULL
) STRICT;

CREATE INDEX idx_audit_log_entity ON audit_log (entity_type, entity_id);
CREATE INDEX idx_audit_log_created ON audit_log (created_at);
