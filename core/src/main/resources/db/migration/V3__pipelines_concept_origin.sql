-- Who wrote each concept: 'ai' for concepts the study assistant extracted from a module's PDFs, 'manual'
-- for concepts a creator added or edited. Extracting concepts again replaces only AI concepts that no item
-- is tagged with, so a creator's own concepts always survive.
ALTER TABLE concepts ADD COLUMN origin TEXT NOT NULL DEFAULT 'manual' CHECK (origin IN ('ai', 'manual'));

CREATE INDEX idx_concepts_module_origin ON concepts (module_id, origin);
