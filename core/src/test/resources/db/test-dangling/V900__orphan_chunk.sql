-- Leaves a chunk pointing at a document that does not exist; the runner must refuse to commit it.
INSERT INTO chunks (id, document_id, course_id, ordinal, page_start, page_end, text, created_at, updated_at)
SELECT 'orphan', 'no-such-document', id, 99, 1, 1, 'orphan', 0, 0 FROM courses LIMIT 1;
CREATE TABLE should_not_exist (x TEXT);
