-- Adds an error code to documents with the standard SQLite table rebuild. Pages and chunks reference
-- documents with ON DELETE CASCADE, so this only keeps them when foreign keys are off.
CREATE TABLE documents_new (
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
                                 'disk_full', 'unexpected', 'unsupported')),
    created_at    INTEGER NOT NULL,
    updated_at    INTEGER NOT NULL
) STRICT;

INSERT INTO documents_new SELECT * FROM documents;
DROP TABLE documents;
ALTER TABLE documents_new RENAME TO documents;

CREATE INDEX idx_documents_course ON documents (course_id);
CREATE INDEX idx_documents_status ON documents (status);
CREATE INDEX idx_documents_module ON documents (module_id);
CREATE INDEX idx_documents_sha ON documents (file_sha256);

-- Statements reach SQLite unchanged, backslashes included.
CREATE VIEW escape_check AS SELECT 'a_b' LIKE 'a\_b' ESCAPE '\' AS matches;
