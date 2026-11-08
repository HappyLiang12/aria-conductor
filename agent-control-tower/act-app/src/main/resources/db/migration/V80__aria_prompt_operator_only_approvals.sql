-- V80: an existing Aria row keeps advertising decide_approval (R4).
--
-- The sync chat path reads the assistant's system prompt from agents.config
-- (AgentLoopEngine prefers config.systemPrompt), and AriaDefaultAgentInitializer is
-- create-only: a row created before the prompt correction is never rewritten. A fresh
-- database has no Aria row here (the initializer seeds the corrected constant after
-- Flyway), so this statement is a no-op there.
--
-- The WHERE clause makes the correction one-time: it only runs while the row still
-- carries the old claim. The rest of the operator-edited prompt is left untouched.

UPDATE agents
SET config = REPLACE(
        REPLACE(config,
            '- decide_approval: Approve or reject a pending action (requires id, decision, reason)\n',
            ''),
        '- list_pending_approvals: List actions waiting for human approval\n',
        '- list_pending_approvals: List actions waiting for human approval. Approval decisions belong to the operator in the dashboard; you cannot decide them, and no tool lets you.\n')
WHERE id = CAST('00000000-0000-0000-0000-000000000001' AS UUID)
  AND config LIKE '%- decide_approval: Approve or reject a pending action%';
