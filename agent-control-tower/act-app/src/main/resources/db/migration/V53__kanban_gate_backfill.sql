-- V52 left qa-gate-labeled cards untouched: the removed QA Gate column was
-- rendered from labels regardless of status, so a TODO card with a qa-gate
-- label silently fell into Todo. Route them to REVIEW, the attention column
-- where an operator decides whether the work re-enters the flow.
UPDATE kanban_items SET status = 'REVIEW'
 WHERE status = 'TODO' AND LOWER(COALESCE(labels, '')) LIKE '%qa-gate%';
