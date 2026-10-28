-- Guidance replies: what the study assistant says to a greeting or to a request that names no subject,
-- with the topics it offers (insufficient-evidence replies store topics the same way), and practice replies,
-- which offer a quiz.
CREATE TABLE chat_messages_new (
    id               TEXT    PRIMARY KEY,
    thread_id        TEXT    NOT NULL REFERENCES chat_threads (id) ON DELETE CASCADE,
    role             TEXT    NOT NULL CHECK (role IN ('user', 'assistant')),
    content          TEXT    NOT NULL,
    status           TEXT    NOT NULL CHECK (status IN ('complete', 'insufficient', 'error', 'cancelled', 'guidance', 'practice')),
    citations_json   TEXT    NOT NULL DEFAULT '[]',
    suggestions_json TEXT    NOT NULL DEFAULT '[]',
    created_at       INTEGER NOT NULL
) STRICT;

INSERT INTO chat_messages_new (id, thread_id, role, content, status, citations_json, created_at)
SELECT id, thread_id, role, content, status, citations_json, created_at FROM chat_messages;

DROP TABLE chat_messages;

ALTER TABLE chat_messages_new RENAME TO chat_messages;

CREATE INDEX idx_chat_messages_thread ON chat_messages (thread_id);
