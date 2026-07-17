-- Add conversation_id to runs table for Aria conversation traceability
ALTER TABLE runs ADD COLUMN IF NOT EXISTS conversation_id VARCHAR(36);
CREATE INDEX IF NOT EXISTS idx_runs_conversation ON runs(conversation_id);

-- Add conversation_id to audit_log table for structured audit queries
ALTER TABLE audit_log ADD COLUMN IF NOT EXISTS conversation_id VARCHAR(36);
CREATE INDEX IF NOT EXISTS idx_audit_conversation ON audit_log(conversation_id);

-- Drop legacy aria tables (no longer populated since engine unification PR #137)
DROP TABLE IF EXISTS aria_messages;
DROP TABLE IF EXISTS aria_sessions;
