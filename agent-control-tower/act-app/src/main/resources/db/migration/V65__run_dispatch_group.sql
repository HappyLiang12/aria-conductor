-- V65: dispatch-group linkage -- the Aria turn's run that dispatched a child run.
--
-- Nullable: every run written before this migration, and every run that is not a
-- dispatched child, reads back as NULL. Children NEVER carry conversation_id: the
-- timeline/context select by it, and the researchers' transcripts would pollute the
-- Aria chat; the completed batch is found through idx_runs_dispatched_by instead.
ALTER TABLE runs ADD COLUMN dispatched_by_run_id UUID NULL;
CREATE INDEX idx_runs_dispatched_by ON runs (dispatched_by_run_id);
