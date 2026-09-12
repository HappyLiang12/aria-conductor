-- Kanban HITL redesign (spec: docs/superpowers/specs/2026-09-08-kanban-hitl-redesign-design.md)
-- 1. Card fields for pickup orchestration.
ALTER TABLE kanban_items ADD COLUMN agent_template_id VARCHAR(100);
ALTER TABLE kanban_items ADD COLUMN last_error VARCHAR(500);

-- 2. Replace label-hack columns with real statuses BEFORE the frontend stops
--    reading labels. BACKLOG/REVIEW are enum-string values (status is VARCHAR).
UPDATE kanban_items SET status = 'BACKLOG'
 WHERE status = 'TODO' AND LOWER(COALESCE(labels, '')) LIKE '%backlog%';
UPDATE kanban_items SET status = 'REVIEW'
 WHERE status = 'TODO' AND LOWER(COALESCE(labels, '')) LIKE '%review%';
UPDATE kanban_items SET status = 'IN_PROGRESS'
 WHERE status = 'IN_PROGRESS'
   AND (LOWER(COALESCE(labels, '')) LIKE '%qa-gate%' OR LOWER(COALESCE(labels, '')) LIKE '%gate%');
UPDATE kanban_items SET status = 'REVIEW' WHERE status = 'BLOCKED';

-- 3. Approval ask columns (HITL decision panel).
ALTER TABLE approvals ADD COLUMN kanban_item_id VARCHAR(36);
ALTER TABLE approvals ADD COLUMN ask_type VARCHAR(20) NOT NULL DEFAULT 'APPROVAL';
ALTER TABLE approvals ADD COLUMN context_md TEXT;
ALTER TABLE approvals ADD COLUMN options_json TEXT;
ALTER TABLE approvals ADD COLUMN answer TEXT;
CREATE INDEX idx_approvals_kanban_item ON approvals (kanban_item_id);
