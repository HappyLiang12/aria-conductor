-- Review-flow amendment: settle review asks whose card already left REVIEW
-- (DONE/CANCELLED) - the stale-ask class observed live 2026-10-03/04.
-- LEGACY_GATE only: native ACP permission asks own their lifecycle and must
-- never be decided by a card-state repair.
UPDATE approvals
SET status = 'DENIED',
    reason = 'settled by card state',
    decided_at = CURRENT_TIMESTAMP
WHERE status = 'PENDING'
  AND source = 'LEGACY_GATE'
  AND kanban_item_id IN (
      SELECT id FROM kanban_items WHERE status IN ('DONE', 'CANCELLED')
  );
