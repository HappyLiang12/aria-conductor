-- Optimistic locking for kanban_items: concurrent board moves (two operators
-- dragging the same card) must fail with HTTP 409 instead of silently
-- last-writer-wins. JPA increments this column on every update.
ALTER TABLE kanban_items ADD COLUMN version BIGINT NOT NULL DEFAULT 0;
